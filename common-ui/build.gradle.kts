plugins {
  alias(deps.plugins.kotlin.multiplatform)
  kotlin("native.cocoapods")
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.compose.compiler)
  alias(deps.plugins.android.library)
}

kotlin {

  androidTarget()
  jvm("desktopJvm") {

    compilations.all {
      kotlinOptions.jvmTarget = "17"
    }
  }
  iosArm64()
  iosSimulatorArm64()
//  macosArm64()

  cocoapods {
    version = "1.0.0"
    summary = "Some description for the Shared Module"
    homepage = "Link to the Shared Module homepage"
    ios.deploymentTarget = "14.1"
    podfile = project.file("../iosApp/Podfile")
    name = "common_ui"

    pod("Sentry") {
      version = "8.38.0"
      linkOnly = true
      extraOpts += listOf("-compiler-option", "-fmodules")
    }

    framework {
      baseName = "common_ui"
      isStatic = true
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
        compileTaskProvider.configure{
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
