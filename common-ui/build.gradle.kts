import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.compose.compiler)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
  kotlin("native.cocoapods")
}

kotlin {

  android {
    namespace = "com.klardrop.common.ui"
    compileSdk = 37
    minSdk = 23
  }
  jvm("desktopJvm") {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }
  }
//  macosArm64()

  // The :klardrop-common module declares pod("Bugsnag") and its synthetic
  // build produces Bugsnag.framework. :common-ui's pod-framework link task
  // inherits the `-framework Bugsnag` linker option from the cinterop klib
  // but not the framework-search-path the cocoapods plugin scoped to
  // :klardrop-common. Forward both Debug and Release search paths for
  // device + simulator so the iOS link step can resolve Bugsnag.
  val bugsnagSyntheticBuild =
    rootProject.file("common/build/cocoapods/synthetic/ios/build")
  val deviceBugsnagPaths = listOf("Debug-iphoneos", "Release-iphoneos")
    .map { File(bugsnagSyntheticBuild, "$it/Bugsnag").absolutePath }
  val simBugsnagPaths = listOf("Debug-iphonesimulator", "Release-iphonesimulator")
    .map { File(bugsnagSyntheticBuild, "$it/Bugsnag").absolutePath }

  cocoapods {
    version = rootProject.version.toString()

    homepage = "https://github.com/carlonzo/klardrop"
    summary = "Shared Module for Klardrop"
    ios.deploymentTarget = "14.1"
    podfile = project.file("../iosApp/Podfile")

    framework {
      baseName = "common_ui"
    }
  }

  // Xcode 16+'s iOS SDK auto-links UIUtilities (a SubFramework) from headers
  // that some pods compile against. The SubFrameworks directory isn't on the
  // default kotlinc-native framework search path, so add it explicitly per
  // target so `-framework UIUtilities` resolves.
  val xcodeDeveloper = providers.exec {
    commandLine("xcode-select", "-p")
  }.standardOutput.asText.get().trim()
  val deviceSdkSubFrameworks =
    "$xcodeDeveloper/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk/System/Library/SubFrameworks"
  val simSdkSubFrameworks =
    "$xcodeDeveloper/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk/System/Library/SubFrameworks"

  iosArm64().binaries.all {
    deviceBugsnagPaths.forEach { linkerOpts("-F", it) }
    linkerOpts("-F", deviceSdkSubFrameworks)
    linkerOpts("-lsqlite3")
  }
  iosSimulatorArm64().binaries.all {
    simBugsnagPaths.forEach { linkerOpts("-F", it) }
    linkerOpts("-F", simSdkSubFrameworks)
    linkerOpts("-lsqlite3")
  }

  sourceSets {

    commonMain {
      dependencies {
        api(compose.runtime)
        api(compose.foundation)
        api(compose.material)
        api(compose.material3)
        api(compose.materialIconsExtended)
        api(compose.components.resources)
        api(compose.ui)

        api(deps.kotlinx.coroutines.core)

        api(deps.filekit.dialogs.compose)
        api(deps.coil3.compose)
        api(deps.coil3.network.ktor)

        implementation(project(":klardrop-common"))
      }
    }

    val desktopJvmMain by getting {
      dependencies {
        implementation(compose.preview)
      }
    }

    val androidMain by getting {
      dependencies {
        implementation(compose.preview)
        implementation(compose.uiTooling)
        implementation(deps.androidx.activity)
        implementation(deps.androidx.activity.compose)
        implementation(deps.androidx.navigation3.runtime)
        implementation(deps.androidx.navigation3.ui)
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

compose.resources {
  publicResClass = false
  packageOfResClass = "com.klardrop.resources"
  generateResClass = auto
}

// Workaround for Compose Multiplatform 1.10 + AGP 9 KMP-DSL: when using the
// new `com.android.kotlin.multiplatform.library` plugin, the AAR build pipeline
// has no merge-assets step, so registering an asset source on the variant is a
// no-op and the composeResources/ tree is silently dropped from the AAR.
// We pin the copy task's output to a known directory and expose it as a
// `composeAndroidAssets` configuration; the consuming Android application
// module wires that directory into its own assets so the resources end up in
// the APK at `assets/composeResources/...` (where AssetManager - and thus
// Compose's resource reader - can find them at runtime).
val androidComposeResourcesDir = layout.buildDirectory.dir("intermediates/compose-resources/androidAssets")
val copyComposeResourcesTaskName = "copyAndroidMainComposeResourcesToAndroidAssets"

afterEvaluate {
  tasks.findByName(copyComposeResourcesTaskName)?.let { task ->
    // The task's class is `internal` in the Compose plugin, so use reflection
    // to grab its outputDirectory DirectoryProperty.
    val getter = task.javaClass.getMethod("getOutputDirectory")
    @Suppress("UNCHECKED_CAST")
    val prop = getter.invoke(task) as org.gradle.api.file.DirectoryProperty
    prop.set(androidComposeResourcesDir)
  }
}

val composeAndroidAssets by configurations.creating {
  isCanBeConsumed = true
  isCanBeResolved = false
}

artifacts {
  add(composeAndroidAssets.name, androidComposeResourcesDir) {
    builtBy(copyComposeResourcesTaskName)
  }
}
