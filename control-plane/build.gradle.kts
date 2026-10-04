import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
}

kotlin {
  android {
    namespace = "com.carlom.klardrop.control"
    compileSdk = 37
    minSdk = 24
  }
  jvm("desktopJvm") {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }
  }
  linuxX64().binaries.all {
    // See the note in common/src/nativeInterop/cinterop/avahi.def: ld.lld's built-in
    // search list does not include Debian/Ubuntu's multiarch /usr/lib/<triplet>, so
    // without these this module's own test binary fails to link its sqlite3.
    linkerOpts(
      "-L/usr/lib/x86_64-linux-gnu",
      "-L/usr/lib/aarch64-linux-gnu",
    )
  }
  // arm64 Linux builds only on an aarch64 host — see `hostCanBuildLinuxArm64`.
  if (rootProject.extra["hostCanBuildLinuxArm64"] as Boolean) {
    linuxArm64().binaries.all {
      linkerOpts(
        "-L/usr/lib/x86_64-linux-gnu",
        "-L/usr/lib/aarch64-linux-gnu",
      )
    }
  }
  // The native macOS app (iosApp/KlardropMac, SwiftUI + presentation.framework) is a
  // *separate framework*, not a JVM app: :presentation's KlardropBootstrap cannot reference
  // ControlPlane because :control-plane already depends on :presentation, so wiring the host
  // from :presentation would be a dependency cycle. This target is the way out — a second,
  // static KMP framework that the Xcode target links alongside presentation.framework and that
  // MacApp.swift imports to start/bind the shared server. It is not dead weight: it is the
  // exact code the shipped macOS app runs.
  macosArm64 {
    binaries.framework {
      baseName = "control_plane"
      isStatic = true
      // The framework's public API is `ControlPlane.start(Klardrop)` and
      // `bind(DiscoveryController, Klardrop)` — types this module does not own.
      // Without `export` the generated header cannot name them and MacApp.swift
      // fails to compile; :presentation exports :klardrop-common for the same
      // reason.
      export(project(":presentation"))
    }
  }
  applyDefaultHierarchyTemplate()

  // macOS link settings, mirroring :presentation's macosArm64 block. :presentation's klib
  // inherits `-framework Sentry` from :klardrop-common's cinterop klib, so this framework
  // needs the same synthetic sentry-cocoa search path at link time (the framework is produced
  // by `podBuildSentryMacos`, which the macOS jobs run first), plus sqlite3 for sqldelight.
  // Kept as plain linkerOpts rather than the sentry-kmp plugin: this module ships no CocoaPod
  // of its own, it is consumed straight from `build/bin/macosArm64/<config>Framework` by the
  // KlardropMac target's FRAMEWORK_SEARCH_PATHS (same mechanism the Sentry framework uses).
  val macosSentrySyntheticBuild =
    rootProject.file("common/build/cocoapods/synthetic/macos/build")
  val macosSentryPaths = listOf("Debug", "Release")
    .map { File(macosSentrySyntheticBuild, "$it/Sentry").absolutePath }
  macosArm64().binaries.all {
    macosSentryPaths.forEach { linkerOpts("-F", it) }
    linkerOpts("-lsqlite3")
    // sentry-cocoa is a *dynamic* framework, so anything linked against it records
    // `@rpath/Sentry.framework/Sentry` and dyld needs a matching LC_RPATH to resolve it at
    // run time — `-F` above only helps while linking. The shipped app embeds Sentry through
    // CocoaPods; the Gradle-run macOS test .kexe has no such embedding and would abort with
    // "Library not loaded" before a single test in `ControlPlaneMacosTest` ran. Same fix, and
    // the same reason, as :presentation's macosArm64 block.
    if (this is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable) {
      macosSentryPaths.forEach { linkerOpts("-rpath", it) }
    }
  }

  sourceSets {
    commonMain {
      dependencies {
        implementation(project(":klardrop-common"))
        implementation(project(":presentation"))
        implementation(deps.kotlinx.coroutines.core)
        implementation(deps.kotlinx.serialization.json)
        implementation(deps.ktor.network)
      }
    }
    val desktopJvmMain by getting {
      dependencies {
        // QrMatrixRenderer.desktopJvm.kt — same QR encoder compose-ui already uses; there is
        // no native macOS or linux artifact of qrcode-kotlin (see QrMatrixRenderer.linux.kt),
        // so it is JVM/Android-only.
        implementation(deps.qrcode.kotlin)
      }
    }
    commonTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(deps.kotlinx.coroutines.test)
        implementation(deps.ktor.network)
      }
    }
  }

  // `posixMain` holds the control-file implementation the Linux and macOS engines share
  // verbatim. It has to be declared by hand: the default hierarchy template's groups are
  // native / apple / linux / macos, and there is no group covering {linux, macos}, so a
  // `unixMain` never exists. Without this line `src/posixMain` would simply not be compiled —
  // Gradle would build a different set of files and report no error at all, which is exactly
  // the kind of silently-dead code this module must not ship. `linuxMain` and `macosMain`
  // both come from the template; only the shared one is ours.
  sourceSets.apply {
    val posix = create("posixMain") { dependsOn(getByName("commonMain")) }
    getByName("linuxMain").dependsOn(posix)
    getByName("macosMain").dependsOn(posix)
  }
}

// resolveControlFilePath() (JVM) writes to $XDG_RUNTIME_DIR/klardrop/control.json. Without
// this, tests that exercise it (LoopbackHttpServerTest, ControlPlaneUpdateTest) write to and
// delete the REAL control.json of whatever klardrop daemon happens to be running on the
// machine under the developer's own XDG_RUNTIME_DIR — this has actually happened. Point every
// test JVM at an isolated directory instead.
tasks.withType<Test>().configureEach {
  environment("XDG_RUNTIME_DIR", temporaryDir.absolutePath)
}
