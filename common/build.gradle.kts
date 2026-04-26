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
        implementation(deps.ktor.client.core)
        implementation(deps.ktor.client.content.negotiation)
        implementation(deps.ktor.serialization.kotlinx.json)
        implementation(deps.kotlinx.serialization.json)

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
        implementation(deps.ktor.client.mock)
      }

      kotlin.srcDir("src/integrationCommonTest/kotlin")
    }

    // Manual intermediate source set shared between Android + JVM. Used for
    // code that depends on `java.security` etc. but should not leak to iOS.
    val jvmAndAndroidMain by creating {
      dependsOn(commonMain.get())
      dependencies {
        implementation(deps.ktor.client.okhttp)
      }
    }

    val androidMain by getting {
      dependsOn(jvmAndAndroidMain)
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
        implementation(deps.ktor.client.darwin)
      }
    }

    val desktopJvmMain by getting {
      dependsOn(jvmAndAndroidMain)
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