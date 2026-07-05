import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
  kotlin("native.cocoapods")
  id("co.touchlab.skie") version "0.10.13"
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

  // macOS inherits the same `-framework Bugsnag` linker option from the
  // :klardrop-common Bugsnag cinterop klib (the cocoapods plugin applies the
  // Bugsnag pod to every native target). Forward the macOS synthetic Bugsnag
  // framework-search-path so the macOS framework link resolves it, plus the
  // sqlite3 hack mirroring iOS. The synthetic macOS build emits to a plain
  // `Debug`/`Release` dir (no `-macosx` SDK suffix like iOS).
  val macosBugsnagSyntheticBuild =
    rootProject.file("common/build/cocoapods/synthetic/macos/build")
  val macosBugsnagPaths = listOf("Debug", "Release")
    .map { File(macosBugsnagSyntheticBuild, "$it/Bugsnag").absolutePath }
  macosArm64().binaries.all {
    macosBugsnagPaths.forEach { linkerOpts("-F", it) }
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

    commonTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(deps.turbine)
        implementation(deps.kotlinx.coroutines.test)
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
