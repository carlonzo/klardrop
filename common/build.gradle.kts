plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
  alias(deps.plugins.sqldelight)
  alias(deps.plugins.sentry.kmp)
}

kotlin {
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

    matching { it.name.startsWith("ios") || it.name.startsWith("macos") }.configureEach {
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
// `embedAndSignAppleFrameworkForXcode` from the "Embed Kotlin presentation.framework"
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
// invocation — xcodebuild runs `embedAndSignAppleFrameworkForXcode` from the Run Script
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
      object KlardropVersion {
        const val VERSION: String = "$versionValue"
        const val UPDATE_CHANNEL: String = "$channelValue"

        /**
         * True when nothing passed `klardrop.version`, i.e. this is a developer build rather
         * than one CI produced. Drives [APP_NAME] so a local build is distinguishable from an
         * installed release — in the launcher, in the tray, and in the peer list on the LAN.
         */
        const val IS_LOCAL_BUILD: Boolean = $isLocalValue

        /** User-visible app name. Suffixed on local builds so two installs can't be confused. */
        const val APP_NAME: String = "$appNameValue"

        /** Sentry DSN, injected by CI. Empty in local and pull-request builds. */
        const val SENTRY_DSN: String = "$sentryDsnValue"
      }
      """.trimIndent() + "\n"
    )
  }
}

kotlin.sourceSets.commonMain {
  kotlin.srcDir(generateKlardropVersion)
}
