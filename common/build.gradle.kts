import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.library)
  alias(deps.plugins.kotlin.serialization)
  alias(deps.plugins.squareup.wire)
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
  targetHierarchy.default()

  android()
  jvm("desktopJvm")
//  macosArm64()
  iosArm64()
  iosSimulatorArm64()

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
        implementation(deps.ukey2)
      }
    }

    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
        implementation(deps.turbine)
        implementation(deps.kotlinx.coroutines.test)
      }
    }

    val androidMain by getting {
      dependencies {
        implementation(deps.kotlinx.coroutines.android)
        implementation(deps.simplestorage)
      }
    }

    val desktopJvmMain by getting{
      dependencies {
        implementation(deps.jmdns)
      }
    }
    val desktopJvmTest by getting

    val appleMain by getting
    val appleTest by getting


    all {
      languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
      languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
      languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }
  }
}

android {
  namespace = "com.klardrop.common"
  compileSdk = 33
  defaultConfig {
    minSdk = 23
  }
}

wire {
  kotlin {
  }
}
