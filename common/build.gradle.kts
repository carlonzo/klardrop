plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
  alias(deps.plugins.sqldelight)
  alias(deps.plugins.sentry.kmp)
  kotlin("native.cocoapods")
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

  // The sentry-kmp plugin adds the `Sentry` pod to this block for as long as we are
  // still on the CocoaPods integration. Once :presentation moves to swift-export the
  // whole block goes away and the plugin links sentry-cocoa from SwiftPM instead —
  // no Kotlin source change either way, since nothing imports `cocoapods.*` anymore.
  cocoapods {
    version = rootProject.version.toString()
    ios.deploymentTarget = "17.0"
    osx.deploymentTarget = "14.0"
  }

  sourceSets {

    commonMain {
      dependencies {
        implementation(project(":protos")) // wire plugin + applyDefaultHierarchyTemplate does not work
        implementation(deps.kotlinx.coroutines.core)

        implementation(deps.ktor.network)
        implementation(deps.ktor.serialization.protobuf)

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
val klardropVersion: String = providers.gradleProperty("klardrop.version").getOrElse("0.0.0-dev")
// Update channel baked into the build: "stable" (default) or "nightly". Decides which
// latest.json the in-app updater polls, so a nightly build self-updates to newer
// nightlies instead of the stable release. CI passes `-Pklardrop.updateChannel=nightly`
// for nightly desktop builds only.
val klardropUpdateChannel: String = providers.gradleProperty("klardrop.updateChannel").getOrElse("stable")

val generateKlardropVersion by tasks.registering {
  val outputDir = layout.buildDirectory.dir("generated/version")
  outputs.dir(outputDir)
  // Captured as locals so the task action stays configuration-cache safe.
  val versionValue = klardropVersion
  val channelValue = klardropUpdateChannel
  inputs.property("version", versionValue)
  inputs.property("channel", channelValue)
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
      }
      """.trimIndent() + "\n"
    )
  }
}

kotlin.sourceSets.commonMain {
  kotlin.srcDir(generateKlardropVersion)
}
