import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
  kotlin("native.cocoapods")
  // Also applied here, not just to :klardrop-common. :presentation's Apple binaries —
  // including the Gradle-run test executables — inherit `-framework Sentry` from the
  // klardrop-common cinterop klib, so they need the plugin's sentry-cocoa link
  // configuration too. Without it the link fails with "framework 'Sentry' not found"
  // even though podBuildSentryIosSimulator has run.
  alias(deps.plugins.sentry.kmp)
  id("co.touchlab.skie") version "0.10.14"
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
      export(project(":klardrop-common"))
    }
  }
  iosSimulatorArm64 {
    binaries.framework {
      baseName = "presentation"
      export(project(":klardrop-common"))
    }
  }
  macosArm64 {
    binaries.framework {
      baseName = "presentation"
      export(project(":klardrop-common"))
    }
  }
  applyDefaultHierarchyTemplate()

  cocoapods {
    version = rootProject.version.toString()
    homepage = "https://github.com/carlonzo/klardrop"
    summary = "Presentation (business + UI-state) module for Klardrop"
    ios.deploymentTarget = "17.0"
    osx.deploymentTarget = "14.0"
    podfile = project.file("../iosApp/Podfile")
    framework {
      baseName = "presentation"
      isStatic = true
      export(project(":klardrop-common"))
    }
  }

  // :presentation depends on :klardrop-common, which the sentry-kmp plugin gives a
  // `Sentry` pod; the iOS klib inherits the `-framework Sentry` linker option from the
  // cinterop klib but not the framework-search-path that the cocoapods plugin scoped to
  // :klardrop-common. Forward both Debug and Release search paths for device + simulator
  // so any iOS link step can resolve it. All of this disappears with the CocoaPods
  // integration itself once :presentation moves to swift-export.
  val sentrySyntheticBuild =
    rootProject.file("common/build/cocoapods/synthetic/ios/build")
  val deviceSentryPaths = listOf("Debug-iphoneos", "Release-iphoneos")
    .map { File(sentrySyntheticBuild, "$it/Sentry").absolutePath }
  val simSentryPaths = listOf("Debug-iphonesimulator", "Release-iphonesimulator")
    .map { File(sentrySyntheticBuild, "$it/Sentry").absolutePath }

  // Xcode 16+'s iOS SDK auto-links UIUtilities (a SubFramework) from headers
  // that some pods compile against. The SubFrameworks directory isn't on the
  // default kotlinc-native framework search path, so add it explicitly per
  // target so `-framework UIUtilities` resolves.
  // xcode-select only exists on macOS hosts; iOS targets can't link off macOS,
  // so resolve the SDK path lazily and skip the lookup on Linux/Windows CI.
  val isMacOsHost = org.gradle.internal.os.OperatingSystem.current().isMacOsX
  val xcodeDeveloper = if (isMacOsHost) {
    providers.exec {
      commandLine("xcode-select", "-p")
    }.standardOutput.asText.get().trim()
  } else {
    ""
  }
  val deviceSdkSubFrameworks =
    "$xcodeDeveloper/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk/System/Library/SubFrameworks"
  val simSdkSubFrameworks =
    "$xcodeDeveloper/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk/System/Library/SubFrameworks"

  iosArm64().binaries.all {
    deviceSentryPaths.forEach { linkerOpts("-F", it) }
    if (isMacOsHost) linkerOpts("-F", deviceSdkSubFrameworks)
    linkerOpts("-lsqlite3")
  }
  iosSimulatorArm64().binaries.all {
    simSentryPaths.forEach { linkerOpts("-F", it) }
    if (isMacOsHost) linkerOpts("-F", simSdkSubFrameworks)
    linkerOpts("-lsqlite3")
    // sentry-cocoa is a dynamic framework, so the linked binary records
    // `@rpath/Sentry.framework/Sentry` and dyld needs a matching LC_RPATH to
    // resolve it at run time. The shipped app framework embeds it via
    // CocoaPods, but the Gradle-run simulator test .kexe has no such embedding,
    // so it aborts with "Library not loaded". Point an rpath at the synthetic
    // framework dir for test executables only (the shipped framework must not
    // carry a CI build-dir rpath).
    if (this is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable) {
      simSentryPaths.forEach { linkerOpts("-rpath", it) }
    }
  }

  // macOS inherits the same `-framework Sentry` linker option from the
  // :klardrop-common cinterop klib (the cocoapods plugin applies the pod to every
  // native target). Forward the macOS synthetic framework-search-path so the macOS
  // framework link resolves it, plus the sqlite3 hack mirroring iOS. The synthetic
  // macOS build emits to a plain `Debug`/`Release` dir (no `-macosx` SDK suffix).
  //
  // Note this path exists at all only because CocoaPods is still how the Apple targets
  // get sentry-cocoa. Unlike Bugsnag, the *Kotlin* side no longer depends on it —
  // macosMain uses the Sentry KMP macosArm64 artifact, not a `cocoapods.*` import.
  val macosSentrySyntheticBuild =
    rootProject.file("common/build/cocoapods/synthetic/macos/build")
  val macosSentryPaths = listOf("Debug", "Release")
    .map { File(macosSentrySyntheticBuild, "$it/Sentry").absolutePath }
  macosArm64().binaries.all {
    macosSentryPaths.forEach { linkerOpts("-F", it) }
    linkerOpts("-lsqlite3")
    // Same dynamic-framework rpath fix as iOS simulator: the macOS test .kexe run
    // by Gradle needs an LC_RPATH to resolve @rpath/Sentry.framework/Sentry.
    if (this is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable) {
      macosSentryPaths.forEach { linkerOpts("-rpath", it) }
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
