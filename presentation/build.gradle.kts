import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
  // Also applied here, not just to :klardrop-common. :presentation's Apple binaries —
  // including the Gradle-run test executables — inherit `-framework Sentry` from the
  // klardrop-common cinterop klib, so they need the plugin's sentry-cocoa link
  // configuration too. Without it the link fails with "framework 'Sentry' not found".
  alias(deps.plugins.sentry.kmp)
  id("co.touchlab.skie") version "0.10.15"
}

kotlin {

  android {
    namespace = "com.klardrop.presentation"
    compileSdk = 37
    minSdk = 23
  }
  jvm("desktopJvm") {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }
  }
  iosArm64 {
    binaries.framework {
      baseName = "presentation"
      isStatic = true
      export(project(":klardrop-common"))
    }
  }
  iosSimulatorArm64 {
    binaries.framework {
      baseName = "presentation"
      isStatic = true
      export(project(":klardrop-common"))
    }
  }
  macosArm64 {
    binaries.framework {
      baseName = "presentation"
      isStatic = true
      export(project(":klardrop-common"))
    }
  }
  applyDefaultHierarchyTemplate()

  // Direct Xcode integration: :presentation builds a static Obj-C framework
  // (`isStatic = true` below) that Xcode links straight into the app targets via
  // the `embedAndSignAppleFrameworkForXcode` Run Script phase — no CocoaPods,
  // no podspec, no `syncFramework`. This is the same integration swift-export
  // requires, so step 3 keeps this wiring and only swaps the export pipeline.
  //
  // Sentry: with no `kotlin("native.cocoapods")` plugin applied, the sentry-kmp
  // plugin links sentry-cocoa from Xcode's SwiftPM integration instead (Sentry
  // 8.58.2, pinned in Package.swift to match the kmp 0.27.0 cinterop). It finds
  // Sentry.xcframework in DerivedData via `sentryKmp.linker.xcodeprojPath`
  // below, and adds `-F`/`-rpath` itself for both framework and
  // test-executable links — so no manual `-F`/`-rpath` forwarding is needed.

  // Tells the sentry-kmp plugin where the Xcode project lives so its
  // DerivedData strategy can find Sentry.xcframework (installed via SwiftPM in
  // Xcode, pinned to the version the kmp cinterop was built against). Gradle-run
  // Apple test executables link against that same framework copy.
  sentryKmp {
    linker {
      xcodeprojPath.set(rootProject.file("iosApp/iosApp.xcodeproj").absolutePath)
    }
  }

  sourceSets {

    commonMain {
      dependencies {
        api(project(":klardrop-common"))
        api(deps.kotlinx.coroutines.core)
        api(deps.filekit.core)
        api(deps.kotlinx.io.core)
        implementation(deps.kotlinx.serialization.json)
      }
    }

    commonTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(deps.turbine)
        implementation(deps.kotlinx.coroutines.test)
        // ktor-network types (InetSocketAddress) appear in the VisibleDevices interface
        // faked by DiscoveryControllerPairingQueueTest.
        implementation(deps.ktor.network)
      }
    }

    all {
      languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
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
}
