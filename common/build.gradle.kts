plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.library)
  alias(deps.plugins.kotlin.serialization)
}

kotlin {
  android()
  jvm("desktopJvm")
  macosArm64("desktopMacos")
  iosArm64()

  sourceSets {

    val commonMain by getting {
      dependencies {

        implementation(deps.kotlinx.coroutines.core)

        implementation(deps.ktor.network)
        implementation(deps.ktor.server.websockets)
        implementation(deps.ktor.server.core)
        implementation(deps.ktor.server.cio)
        implementation(deps.ktor.client.websockets)
        implementation(deps.ktor.client.cio)
        implementation(deps.ktor.serialization.protobuf)

        implementation(deps.androidx.datastore.core)
        implementation(deps.androidx.datastore.core.okio)
        implementation(deps.kotlinx.serialization.protobuf)
      }
    }

    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
      }
    }

    val androidMain by getting {
      dependencies {
        implementation(deps.kotlinx.coroutines.android)
        implementation(deps.simplestorage)
      }
    }


    val desktopJvmMain by getting
    val desktopJvmTest by getting

    val iosMain by creating {
      dependsOn(commonMain)
    }
    val iosTest by creating {
      dependsOn(commonTest)
    }

    val iosArm64Main by getting {
      dependsOn(iosMain)
    }

    val desktopMacosMain by getting {
      dependsOn(iosMain)
    }

    all {
      languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
      languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
    }
  }
}

android {
  namespace = "com.klardrop.common"
  compileSdk = 33
  defaultConfig {
    minSdk = 23
    setTargetSdkVersion("33")
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }


}

