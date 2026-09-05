import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.kmp.library)
  alias(deps.plugins.kotlin.serialization)
}

kotlin {
  android {
    namespace = "com.carlom.klardrop.debug"
    compileSdk = 37
    minSdk = 24
  }
  jvm("desktopJvm") {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }
  }
  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain {
      dependencies {
        implementation(project(":klardrop-common"))
        implementation(project(":presentation"))
        implementation(deps.kotlinx.coroutines.core)
        implementation(deps.kotlinx.serialization.json)
        implementation(deps.ktor.network)
      }
    }
    commonTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(deps.kotlinx.coroutines.test)
        implementation(deps.ktor.network)
      }
    }
  }
}
