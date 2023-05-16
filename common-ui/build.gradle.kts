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
        api(compose.material3)
        api(compose.ui)

        implementation(deps.kotlinx.coroutines.core)

        implementation(project(":common"))
      }
    }

    val desktopMain by getting {
      dependencies {
        implementation(compose.preview)
      }
    }

    val androidMain by getting {
      dependencies {
        implementation(compose.preview)
        implementation(compose.uiTooling)
      }
    }
  }
}

android {
  namespace = "com.klardrop.common.ui"
  compileSdk = 33
  defaultConfig {
    minSdk = 23
  }
}
