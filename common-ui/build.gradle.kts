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
  listOf(
    iosArm64(),
    iosSimulatorArm64()
  )
//    .forEach {
//    it.binaries.framework {
//      baseName = "common_ui"
//      isStatic = true
//      export(project(":klardrop-common"))
//    }
//  }

//  macosArm64()

  cocoapods {
    version = rootProject.version.toString()

    // rest of configuration
    homepage = "https://github.com/carlonzo/klardrop"
    summary = "Shared Module for Klardrop"
    ios.deploymentTarget = "14.1"
    podfile = project.file("../iosApp/Podfile")

    framework {
      baseName = "common_ui"
    }
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

// Workaround for Compose Multiplatform 1.10 + AGP 9 KMP-DSL: the
// CopyResourcesToAndroidAssetsTask doesn't get its outputDirectory auto-wired
// when using the new `kotlin { android { } }` DSL, so the Android APK ships
// without composeResources/. We pin it to a known directory and feed that
// directory into the Android main source set's assets.
val androidComposeResourcesDir = layout.buildDirectory.dir("intermediates/compose-resources/androidAssets")

afterEvaluate {
  tasks.findByName("copyAndroidMainComposeResourcesToAndroidAssets")?.let { task ->
    // The task's class is `internal` in the Compose plugin, so use reflection
    // to grab its outputDirectory DirectoryProperty.
    val getter = task.javaClass.getMethod("getOutputDirectory")
    @Suppress("UNCHECKED_CAST")
    val prop = getter.invoke(task) as org.gradle.api.file.DirectoryProperty
    prop.set(androidComposeResourcesDir)
  }
}

kotlin {
  sourceSets {
    val androidMain by getting {
      resources.srcDir(androidComposeResourcesDir)
    }
  }
}
