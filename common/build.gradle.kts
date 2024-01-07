plugins {
  alias(deps.plugins.android.library)
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.kotlin.serialization)
  alias(deps.plugins.sqldelight)
}

kotlin {
  androidTarget()
  jvm("desktopJvm")
//  macosArm64()
  iosArm64()
  iosSimulatorArm64()

  sourceSets {

    commonMain {
      dependencies {
        implementation(project(":protos")) // wire plugin + applyDefaultHierarchyTemplate does not work
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
        implementation(deps.sqldelight.coroutines.extensions)
        api("io.sentry:sentry-kotlin-multiplatform:0.7.1")
      }
    }

    commonTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(deps.turbine)
        implementation(deps.kotlinx.coroutines.test)
      }
    }

    androidMain.dependencies {
      implementation(deps.kotlinx.coroutines.android)
      implementation(deps.simplestorage)
      implementation(deps.sqldelight.android.driver)
    }

    nativeMain.dependencies {
      implementation(deps.sqldelight.native.driver)
    }

    val desktopJvmMain by getting {
      dependencies {
        implementation(deps.jmdns)
        implementation(deps.sqldelight.jvm.driver)
      }
    }

    all {
      languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
      languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
      languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }
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


android {
  namespace = "com.klardrop.common"
}

sqldelight {
  databases {
    create("KlardropDatabase") {
      packageName = "com.klardrop.common.persistence"
      schemaOutputDirectory.set(file("src/commonMain/sqldelight/com/klardrop/common/persistence/databases"))
      generateAsync.set(true)
      verifyMigrations.set(true)
    }
  }
}
