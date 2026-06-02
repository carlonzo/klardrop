import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
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
  iosArm64()
  iosSimulatorArm64()
  applyDefaultHierarchyTemplate()

  // :presentation depends on :klardrop-common which declares pod("Bugsnag");
  // the iOS klib inherits the `-framework Bugsnag` linker option from the
  // cinterop klib but not the framework-search-path that the cocoapods plugin
  // scoped to :klardrop-common. Forward both Debug and Release search paths for
  // device + simulator so any iOS link step can resolve Bugsnag. The Phase 0
  // green gate only runs compile (not link) tasks, but this keeps future link
  // tasks working.
  val bugsnagSyntheticBuild =
    rootProject.file("common/build/cocoapods/synthetic/ios/build")
  val deviceBugsnagPaths = listOf("Debug-iphoneos", "Release-iphoneos")
    .map { File(bugsnagSyntheticBuild, "$it/Bugsnag").absolutePath }
  val simBugsnagPaths = listOf("Debug-iphonesimulator", "Release-iphonesimulator")
    .map { File(bugsnagSyntheticBuild, "$it/Bugsnag").absolutePath }

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
    deviceBugsnagPaths.forEach { linkerOpts("-F", it) }
    if (isMacOsHost) linkerOpts("-F", deviceSdkSubFrameworks)
    linkerOpts("-lsqlite3")
  }
  iosSimulatorArm64().binaries.all {
    simBugsnagPaths.forEach { linkerOpts("-F", it) }
    if (isMacOsHost) linkerOpts("-F", simSdkSubFrameworks)
    linkerOpts("-lsqlite3")
  }

  sourceSets {

    commonMain {
      dependencies {
        api(project(":klardrop-common"))
        api(deps.kotlinx.coroutines.core)
        api(deps.filekit.core)
        api(deps.kotlinx.io.core)
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
