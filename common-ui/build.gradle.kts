plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.android.library)
}

kotlin {
  android()
  jvm("desktop") {
    compilations.all {
      kotlinOptions.jvmTarget = "11"
    }
  }
  iosArm64()

  sourceSets {

    val commonMain by getting {
      dependencies {
        api(compose.runtime)
        api(compose.foundation)
        api(compose.material)

        implementation(deps.kotlinx.coroutines.core)

        implementation(project(":common"))
      }
    }

    val desktopMain by getting {
      dependencies {
        implementation(compose.preview)
      }
    }
  }
}

android {
  namespace = "com.klardrop.common.ui"
  compileSdkVersion(33)
  defaultConfig {
    minSdkVersion(23)
    targetSdkVersion(33)
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }


}
