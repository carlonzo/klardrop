package com.carlom.klardrop.control

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import platform.posix.setenv
import platform.posix.stat
import platform.posix.unsetenv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The macOS-native half of the control plane, exercised on a macOS runner.
 *
 * The route logic is shared with the JVM host and is covered there. What only a macOS-native
 * run can show is the *platform* half, and this file sticks to things that genuinely differ
 * from the JVM host:
 *
 *  - `posixMain`'s write/protect/delete — `mkdir` → `fchmod 0600` → write, and the
 *    token-scoped delete — running on Darwin;
 *  - `ControlFile.macos.kt`'s `controlFileDirectory()`, i.e. the App Group lookup through
 *    `NSFileManager.containerURLForSecurityApplicationGroupIdentifier`;
 *  - `platformUnavailableCapabilities`, which is non-empty only on this host;
 *  - `LoopbackHttpServer` binding a loopback port through ktor-network on Darwin.
 *
 * The control file is pinned to a temp path with `KLARDROP_CONTROL_FILE` for the tests that
 * write it, because the Gradle-run test binary carries no app-group entitlement and cannot
 * reach the real container. Every code path under test there is the production one.
 */
@OptIn(ExperimentalForeignApi::class)
class ControlPlaneMacosTest {

  private val tempDir = "${SystemTemporaryDirectory}klardrop-control-plane-macos-test"
  private val tempControlFile = "$tempDir/klardrop/control.json"
  private var pinned = false

  /**
   * `unixWriteControlFile` creates the ONE directory it needs, not a whole path, because
   * in production that directory is always under a root the platform already made
   * (`$XDG_RUNTIME_DIR`, `$HOME`). A pinned path with a root of its own therefore has to
   * bring that root with it — otherwise the mkdir is ENOENT, not EEXIST, and the write
   * fails closed with "cannot create control directory".
   */
  @BeforeTest
  fun createTempRoot() {
    SystemFileSystem.createDirectories(Path(tempDir))
  }

  @AfterTest
  fun tearDown() {
    if (pinned) {
      unsetenv(CONTROL_FILE_ENV)
      pinned = false
    }
    deleteTree(Path(tempDir))
  }

  @Test
  fun theControlFileIsOwnerOnlyAndIsOnlyDeletedByItsOwnToken() {
    setenv(CONTROL_FILE_ENV, tempControlFile, 1)
    pinned = true

    assertEquals(tempControlFile, resolveControlFilePath())

    writeControlFile(4321, "token-abc", PRODUCTION_CAPABILITIES)

    assertTrue(SystemFileSystem.exists(Path(tempControlFile)), "control.json must be published")
    val published = SystemFileSystem.source(Path(tempControlFile)).buffered().use { it.readByteArray() }
    assertEquals(
      controlFileJson(4321, "token-abc", PRODUCTION_CAPABILITIES),
      published.decodeToString(),
    )

    // The token is a bearer credential for this device's identity and its files. Anything
    // readable by another user on the machine makes the whole loopback API forgeable.
    val mode = memScoped {
      val st = alloc<stat>()
      assertEquals(0, stat(tempControlFile, st.ptr), "stat() failed on $tempControlFile")
      st.st_mode.toUInt() and 0x1FFu
    }
    assertEquals(
      0x180u,
      mode,
      "control.json must be exactly rw------- (0600), was 0${mode.toString(8)}",
    )

    // A daemon restarting must not delete a *different* live daemon's file.
    deleteControlFile("some-other-daemons-token")
    assertTrue(
      SystemFileSystem.exists(Path(tempControlFile)),
      "a foreign token must never delete the file",
    )

    deleteControlFile("token-abc")
    assertFalse(SystemFileSystem.exists(Path(tempControlFile)), "its own token must delete it")
  }

  /**
   * The macOS-only directory lookup and the fail-closed decision built on it.
   *
   * A Gradle-run test binary carries no app-group entitlement, so `controlFileDirectory()`
   * normally answers null — which is exactly the situation the daemon must refuse rather than
   * publish, so the guard is genuinely driven here. Run from the signed app the container does
   * resolve, and the same test then asserts the path the Rust client searches.
   */
  @Test
  fun theControlFilePathFollowsTheAppGroupContainerAndTheHostFailsClosedWithoutOne() {
    unsetenv(CONTROL_FILE_ENV)
    pinned = false

    val directory = controlFileDirectory()
    if (directory == null) {
      assertNull(resolveControlFilePath(), "no container must mean no path, never a guess")
      val refusal = assertFailsWith<IllegalStateException> { controlFilePathToPublish() }
      val reason = assertNotNull(refusal.message)
      assertTrue(
        reason.contains("unauthenticated"),
        "the refusal must say what it prevents: $reason",
      )
    } else {
      assertEquals("$directory/control.json", resolveControlFilePath())
      assertTrue(
        directory.endsWith("D7T5425WSW.group.com.carlom.Klardrop"),
        "macOS must publish into the App Group the app already shares with its extensions, " +
          "not into an arbitrary directory: $directory",
      )
      assertEquals("$directory/control.json", controlFilePathToPublish())
    }
  }

  @Test
  fun aHostWithAPinnedPrivateLocationPublishesThere() {
    setenv(CONTROL_FILE_ENV, tempControlFile, 1)
    pinned = true

    assertEquals(tempControlFile, resolveControlFilePath())
    assertEquals(tempControlFile, controlFilePathToPublish())
  }

  @Test
  fun qrShareIsWithheldRatherThanAdvertisedAndFailing() {
    // The app is sandboxed and cannot execute qrencode, and qrcode-kotlin has no macOS
    // artifact. QrMatrixRenderer.macos.kt has the full reasoning. The debug variant is not
    // asserted here: KlardropBootstrap always builds ApplicationInfo(isDebug = false) on Apple,
    // so a macOS host can never publish a debug capability list.
    assertEquals(setOf("qr-share"), platformUnavailableCapabilities)

    val release = forBuild(isDebug = false)
    assertFalse("qr-share" in release, "a route this host cannot serve must not be advertised")
    assertEquals(
      PRODUCTION_CAPABILITIES.filterNot { it in platformUnavailableCapabilities },
      release,
    )
  }

  @Test
  fun qrRenderingFailsLoudlyInsteadOfReturningAnUnreadableCode() {
    val failure = assertFailsWith<UnsupportedOperationException> {
      renderQrMatrix("https://klardrop.test/share/abc")
    }
    // Bound to a local rather than smart-cast: `Throwable.message` is an open property, and
    // Kotlin 2.4 no longer smart-casts those, so reading it twice off `failure` fails to
    // compile even though `assertNotNull` proved it non-null on the previous line.
    val message = assertNotNull(failure.message)
    assertTrue(
      message.contains("sandbox"),
      "the failure must name the reason so it is not debugged as transient: $message",
    )
  }

  @Test
  fun theLoopbackServerBindsAnEphemeralPortOnDarwin() = runBlocking {
    // The routes are shared with the JVM host; what this proves is that ktor-network's
    // selector really does bind on this platform, which no Linux or JVM run can show.
    val server = LoopbackHttpServer(
      host = "127.0.0.1",
      port = 0,
      authToken = "macos-fixture-token",
      dispatcher = Dispatchers.Default,
    ) { _ -> HttpResponse(200, """{"ok":true}""") }
    server.start()
    try {
      assertTrue(server.boundPort > 0, "the listener must report its live port")
    } finally {
      server.stop()
    }
  }

  private companion object {
    const val CONTROL_FILE_ENV = "KLARDROP_CONTROL_FILE"
  }
}

/** kotlinx-io's recursive delete, spelled out so the fixture does not depend on its arity. */
private fun deleteTree(path: Path) {
  if (!SystemFileSystem.exists(path)) return
  if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
    SystemFileSystem.list(path).forEach { deleteTree(it) }
  }
  SystemFileSystem.delete(path)
}