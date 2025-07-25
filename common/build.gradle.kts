plugins {
  alias(deps.plugins.android.library)
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.kotlin.serialization)
  alias(deps.plugins.sqldelight)
  kotlin("native.cocoapods")
}

kotlin {
  androidTarget()
  jvm("desktopJvm")
//  macosArm64()
  iosArm64()
  iosSimulatorArm64()

  applyDefaultHierarchyTemplate()

  cocoapods {
    version = rootProject.version.toString()
    ios.deploymentTarget = "14.1"
    pod("Bugsnag", "~> 6.0")
  }

  sourceSets {

    commonMain {
      dependencies {
        implementation(project(":protos")) // wire plugin + applyDefaultHierarchyTemplate does not work
        implementation(deps.kotlinx.coroutines.core)

        implementation(deps.ktor.network)
        implementation(deps.ktor.serialization.protobuf)

        implementation(deps.kotlinx.io.core)
        implementation(deps.kotlinx.io.okio)

        implementation(deps.androidx.datastore.core)
        implementation(deps.androidx.datastore.core.okio)
        implementation(deps.kotlinx.serialization.protobuf)
        implementation(deps.ukey2)
        implementation(deps.filekit.core)
        implementation(deps.sqldelight.runtime)
        implementation(deps.sqldelight.coroutines.extensions)
      }
    }

    commonTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(deps.turbine)
        implementation(deps.kotlinx.coroutines.test)
      }

      kotlin.srcDir("src/integrationCommonTest/kotlin")
    }

    val androidMain by getting {
      dependencies {
        implementation(deps.kotlinx.coroutines.android)
        implementation(deps.simplestorage)
        implementation(deps.bugsnag.kmp)
        implementation(deps.sqldelight.android.driver)
      }
    }

    val androidUnitTest by getting {
      dependencies {
        implementation(deps.sqldelight.sqlite.driver)
      }
    }

    val iosMain by getting {
      dependencies {
        implementation(deps.bugsnag.kmp)
        implementation(deps.sqldelight.native.driver)

      }
    }

    val desktopJvmMain by getting {
      dependencies {
        implementation(deps.jmdns)
        implementation(deps.bugsnag.jvm)
        implementation(deps.sqldelight.sqlite.driver)
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
      compileTaskProvider.configure {
        compilerOptions {
          freeCompilerArgs.add("-Xexpect-actual-classes")
        }
      }
    }
  }
}


android {
  namespace = "com.klardrop.common"

  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

sqldelight {
  databases {
    create("AppDatabase") {
      packageName.set("com.carlom.klardrop.common.database")
    }
  }
}