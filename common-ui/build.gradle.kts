import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.compose.compiler)
  alias(deps.plugins.android.library)
  alias(deps.plugins.sentry.multiplatform)
  kotlin("native.cocoapods")
}

kotlin {

  androidTarget()
  jvm("desktopJvm") {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_17
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

    pod("Sentry") {
      // Check the version compatibility table for the correct version
      version = "8.44.0"
      linkOnly = true
      extraOpts += listOf("-compiler-option", "-fmodules")
    }

    framework {
      baseName = "common_ui"
      export(project(":klardrop-common"))
    }
  }

  sourceSets {

    commonMain {
      dependencies {
        api(compose.runtime)
        api(compose.foundation)
        api(compose.material)
        api(compose.material3)
        api(compose.ui)

        api(deps.kotlinx.coroutines.core)

        api(deps.filekit.dialogs.compose)

        api(project(":klardrop-common"))
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

android {
  namespace = "com.klardrop.common.ui"
  compileSdk = 35
  defaultConfig {
    minSdk = 23
  }
}
