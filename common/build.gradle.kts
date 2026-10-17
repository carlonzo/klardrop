plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
  alias(deps.plugins.sqldelight)
  alias(deps.plugins.sentry.kmp)
}

kotlin {
  explicitApi()
  android {
    namespace = "com.klardrop.common"
    compileSdk = 37
    minSdk = 23
    withHostTestBuilder { }.configure {
      isReturnDefaultValues = true
    }
  }
  jvm("desktopJvm")
  macosArm64()
  iosArm64()
  iosSimulatorArm64()
  linuxX64 {
    compilations.getByName("main") {
      cinterops {
        val avahi by creating {
          defFile(project.file("src/nativeInterop/cinterop/avahi.def"))
        }
        val spawn by creating {
          defFile(project.file("src/nativeInterop/cinterop/spawn.def"))
        }
        val openssl by creating {
          defFile(project.file("src/nativeInterop/cinterop/openssl.def"))
        }
        val sdbus by creating {
          defFile(project.file("src/nativeInterop/cinterop/sdbus.def"))
        }
      }
    }
    binaries.all {
      linkerOpts(
        "-Wl,--as-needed",
        // Kotlin/Native invokes ld.lld directly, and lld's built-in search list is the
        // plain /usr/lib, /usr/lib64, /lib, /lib64. Debian and Ubuntu are multiarch:
        // libsqlite3.so, libssl.so, libavahi-*.so and libsystemd.so live in
        // /usr/lib/<triplet>, which that list does not include — so a link there fails
        // with "unable to find library -lsqlite3" and friends. Arch and Fedora put the
        // same libraries in /usr/lib, which is why this only ever showed up on CI.
        // This target's triplet only. linuxArm64 lists the aarch64 dir instead:
        // searching both would let an x64 cross-compile pick up x86_64 libraries.
        "-L/usr/lib/x86_64-linux-gnu",
        // Kotlin/Native links against its bundled older glibc sysroot, and the host libavahi/libsqlite3 reference newer glibc symbol versions that resolve at runtime.
        "--allow-shlib-undefined",
        "-lsqlite3",
        // QR browser share TLS server (LanTlsListener.linux.kt) — system OpenSSL 3.x, same "link
        // against the host's version" approach as avahi/sqlite3 above.
        "-lssl",
        "-lcrypto",
        // BLE spike (ble/linux sd-bus): BlueZ over the system bus. Same host-link approach.
        "-lsystemd",
      )
    }
  }
  // Declared only when this build can produce linuxArm64 — see `hostCanBuildLinuxArm64`.
  if (rootProject.extra["hostCanBuildLinuxArm64"] as Boolean) {
    linuxArm64 {
      compilations.getByName("main") {
        cinterops {
          val avahi by creating {
            defFile(project.file("src/nativeInterop/cinterop/avahi.def"))
          }
          val spawn by creating {
            defFile(project.file("src/nativeInterop/cinterop/spawn.def"))
          }
          val openssl by creating {
            defFile(project.file("src/nativeInterop/cinterop/openssl.def"))
          }
          val sdbus by creating {
            defFile(project.file("src/nativeInterop/cinterop/sdbus.def"))
          }
        }
      }
      binaries.all {
        linkerOpts(
          "-Wl,--as-needed",
          // aarch64 multiarch dir only — see the linuxX64 note above. The release job
          // cross-compiles this target on ubuntu-24.04 with the :arm64 dev packages
          // installed; a native aarch64 host has the same directory.
          "-L/usr/lib/aarch64-linux-gnu",
          "--allow-shlib-undefined",
          "-lsqlite3",
          "-lssl",
          "-lcrypto",
          "-lsystemd",
        )
      }
    }
  }

  tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    val testWorkDir = layout.buildDirectory.dir("tmp/native-test").get().asFile
    workingDir = testWorkDir.absolutePath
    doFirst {
      testWorkDir.mkdirs()
    }
  }

  applyDefaultHierarchyTemplate()

  // The sentry-kmp plugin links sentry-cocoa from Xcode's SwiftPM integration:
  // the sentry-cocoa package reference lives in iosApp.xcodeproj (exact 8.58.2,
  // matching the kmp 0.27.0 cinterop), and the plugin finds Sentry.xcframework in
  // the default DerivedData at link time (`sentryKmp.linker.xcodeprojPath` points
  // it at iosApp.xcodeproj). No Kotlin source change — nothing imports `cocoapods.*`.

  // Tells the sentry-kmp plugin where the Xcode project lives so its
  // DerivedData strategy can find Sentry.xcframework (the sentry-cocoa package
  // reference lives in iosApp.xcodeproj, pinned to the version the kmp cinterop
  // was built against). Gradle-run
  // Apple test executables link against that same framework copy.
  sentryKmp {
    linker {
      xcodeprojPath.set(rootProject.file("iosApp/iosApp.xcodeproj").absolutePath)
    }
  }

  sourceSets {

    commonMain {
      dependencies {
        implementation(project(":protos")) // wire plugin + applyDefaultHierarchyTemplate does not work
        implementation(deps.kotlinx.coroutines.core)

        implementation(deps.ktor.network)

        implementation(deps.kotlinx.io.core)
        implementation(deps.kotlinx.io.okio)

        implementation(deps.androidx.datastore.core)
        implementation(deps.androidx.datastore.core.okio)
        implementation(deps.kotlinx.serialization.protobuf)
        implementation(deps.ukey2)
        implementation(deps.filekit.core)
        implementation(deps.sqldelight.runtime)
        implementation(deps.sqldelight.coroutines.extensions)
        
        // Cryptography for trust features
        implementation(deps.cryptography.core)
        implementation(deps.cryptography.provider.optimal)
        implementation(deps.cryptography.random)
      }
    }

    commonTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(deps.turbine)
        implementation(deps.kotlinx.coroutines.test)
      }

      kotlin.srcDir("src/integrationCommonTest/kotlin")
    }

    val androidMain by getting {
      dependencies {
        implementation(deps.kotlinx.coroutines.android)
        // Used directly by androidMain (ContextCompat, NotificationCompat, FileProvider,
        // SharedPreferences.edit). It used to arrive transitively via com.anggrayudi:storage,
        // so removing that unused dependency requires declaring this one explicitly.
        implementation(deps.androidx.core)
        implementation(deps.sqldelight.android.driver)
      }
    }

    val androidHostTest by getting {
      dependencies {
        implementation(deps.sqldelight.sqlite.driver)
      }
    }

    val appleMain by getting {
      dependencies {
        implementation(deps.sqldelight.native.driver)
      }
    }

    val linuxMain by getting {
      dependencies {
        implementation(deps.sqldelight.native.driver)
        // Sentry envelope JSON for the linuxX64 crash sender (SentryLinuxSender.linux.kt) —
        // the linuxX64 Sentry SDK klib is a no-op stub, so this target builds its own envelopes.
        implementation(deps.kotlinx.serialization.json)
      }
    }

    matching { it.name.startsWith("ios") || it.name.startsWith("macos") || it.name.startsWith("linux") }.configureEach {
      languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }

    val desktopJvmMain by getting {
      dependencies {
        implementation(deps.jmdns)
        implementation(deps.jna)
        implementation(deps.jna.platform)
        implementation(deps.sqldelight.sqlite.driver)
        implementation(deps.kotlinx.serialization.json)
        // Linux BLE: BlueZ over D-Bus (system bus, unix-socket transport).
        implementation(deps.dbus.java.core)
        implementation(deps.dbus.java.transport.native.unixsocket)
      }
    }

    all {
      languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
      languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
      languageSettings.optIn("kotlin.time.ExperimentalTime")
    }
  }

  targets.all {
    compilations.all {
      compileTaskProvider.configure {
        compilerOptions {
          freeCompilerArgs.add("-Xexpect-actual-classes")
        }
      }
    }
  }
}


sqldelight {
  databases {
    create("AppDatabase") {
      packageName.set("com.carlom.klardrop.common.database")
    }
  }
}

// Single source of truth for the app version. The git tag drives everything:
// CI passes `-Pklardrop.version=X.Y.Z` (derived from the `vX.Y.Z` tag); local
// builds fall back to a sentinel so a dev build never masquerades as a release.
// The value is baked into a generated KlardropVersion.kt visible to all targets.
//
// `klardropVersion` is an alias for the same value, NOT a second knob: the Apple frameworks
// are not produced by the workflow's own `./gradlew` invocation — xcodebuild runs
// `embedSwiftExportForXcode` from the "Embed Kotlin presentation.framework"
// Run Script phase, which inherits the process environment but none of the workflow's
// `-P` flags. So the Apple jobs also export
// ORG_GRADLE_PROJECT_klardropVersion, and dots are not usable in an env var name. The dotted
// property is checked first and therefore still wins wherever `-Pklardrop.version=` is passed.
// Without the alias every Apple build embeds the "0.0.0-dev" fallback and its crashes reach
// Sentry under a release that no release pipeline ever creates.
val klardropVersion: String = providers.gradleProperty("klardrop.version")
  .orElse(providers.gradleProperty("klardropVersion"))
  .getOrElse("0.0.0-dev")
// Update channel baked into the build: "stable" (default) or "nightly". Decides which
// latest.json the in-app updater polls, so a nightly build self-updates to newer
// nightlies instead of the stable release. CI passes `-Pklardrop.updateChannel=nightly`
// for nightly desktop builds only.
val klardropUpdateChannel: String = providers.gradleProperty("klardrop.updateChannel").getOrElse("stable")
// Sentry DSN, baked in at compile time. Deliberately NOT checked in: this repository is
// public, and a DSN is a write-only ingest endpoint that anyone can post events to. Keeping
// it out of the tree means a scraper cannot lift it from GitHub and burn the event quota.
// (It is still recoverable from a shipped binary, so the real backstop is Sentry-side
// rate limiting + inbound filters — this only removes the zero-effort path.)
//
// Deliberately property-based rather than dot-separated: CI passes it as the environment
// variable ORG_GRADLE_PROJECT_klardropSentryDsn, and dots are not portable in env names.
// That matters because the Apple frameworks are not built by the workflow's own `./gradlew`
// invocation — xcodebuild runs `embedSwiftExportForXcode` from the Run Script
// phase, which inherits the environment but none of the workflow's `-P` flags.
//
// Empty by default, which is what every local and pull-request build gets. `initCrashReporter`
// treats an empty DSN as "crash reporting disabled", so a dev build cannot report at all.
val klardropSentryDsn: String = providers.gradleProperty("klardropSentryDsn").getOrElse("")

val generateKlardropVersion by tasks.registering {
  val outputDir = layout.buildDirectory.dir("generated/version")
  outputs.dir(outputDir)
  // Captured as locals so the task action stays configuration-cache safe.
  val versionValue = klardropVersion
  val channelValue = klardropUpdateChannel
  val sentryDsnValue = klardropSentryDsn
  val isLocalValue = klardropVersion == "0.0.0-dev"
  val appNameValue = if (isLocalValue) "Klardrop debug" else "Klardrop"
  inputs.property("version", versionValue)
  inputs.property("appName", appNameValue)
  inputs.property("channel", channelValue)
  inputs.property("sentryDsn", sentryDsnValue)
  doLast {
    val pkgDir = outputDir.get().asFile.resolve("com/carlom/klardrop/common")
    pkgDir.mkdirs()
    pkgDir.resolve("KlardropVersion.kt").writeText(
      """
      package com.carlom.klardrop.common

      /** Generated from the `klardrop.version` Gradle property — do not edit. */
      public object KlardropVersion {
        public const val VERSION: String = "$versionValue"
        public const val UPDATE_CHANNEL: String = "$channelValue"

        /**
         * True when nothing passed `klardrop.version`, i.e. this is a developer build rather
         * than one CI produced. Drives [APP_NAME] so a local build is distinguishable from an
         * installed release — in the launcher, in the tray, and in the peer list on the LAN.
         */
        public const val IS_LOCAL_BUILD: Boolean = $isLocalValue

        /** User-visible app name. Suffixed on local builds so two installs can't be confused. */
        public const val APP_NAME: String = "$appNameValue"

        /** Sentry DSN, injected by CI. Empty in local and pull-request builds. */
        public const val SENTRY_DSN: String = "$sentryDsnValue"
      }
      """.trimIndent() + "\n"
    )
  }
}

tasks.matching { it.name == "generateCommonMainAppDatabaseInterface" }.configureEach {
  val outputDir = layout.buildDirectory.dir("generated/sqldelight/code/AppDatabase/commonMain")
  doLast {
    val dir = outputDir.get().asFile
    if (!dir.exists()) return@doLast
    dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
      val name = file.name
      if (name == "AppDatabase.kt") {
        val original = file.readText()
        val rewritten = original
          .replace("public interface ", "internal interface ")
          .replace("public ", "")
        if (rewritten != original) file.writeText(rewritten)
      } else if (name.endsWith("Queries.kt") || name == "AppDatabaseImpl.kt") {
        val original = file.readText()
        val rewritten = original.replace("public ", "internal ")
        if (rewritten != original) file.writeText(rewritten)
      }
    }
  }
}

kotlin.sourceSets.commonMain {
  kotlin.srcDir(generateKlardropVersion)
}
