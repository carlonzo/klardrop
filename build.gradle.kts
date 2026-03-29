import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"

plugins {
  alias(deps.plugins.kotlin.multiplatform) apply false
  alias(deps.plugins.kotlin.android) apply false
  alias(deps.plugins.android.application) apply false
  alias(deps.plugins.android.library) apply false
  alias(deps.plugins.jetbrains.compose) apply false
  alias(deps.plugins.compose.compiler) apply false
  alias(deps.plugins.kotlin.serialization) apply false
  alias(deps.plugins.sqldelight) apply false
}

allprojects {
  repositories {
    google()
    mavenCentral()
    mavenLocal()
  }
}

subprojects {

  val androidJavaVersion = JavaVersion.VERSION_17
  val jvmJavaVersion = JavaVersion.VERSION_21
  val jvmTarget = JvmTarget.fromTarget(jvmJavaVersion.toString())

  pluginManager.withPlugin("com.android.application") {
    configure<BaseExtension> {
      compileOptions {
        sourceCompatibility = androidJavaVersion
        targetCompatibility = androidJavaVersion
      }
      compileSdkVersion(36)
      defaultConfig {
        minSdk = 23
      }
    }
  }

  pluginManager.withPlugin("com.android.library") {
    configure<BaseExtension> {
      compileOptions {
        sourceCompatibility = androidJavaVersion
        targetCompatibility = androidJavaVersion
      }
      compileSdkVersion(36)
      defaultConfig {
        minSdk = 23
      }
    }
  }

  tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget = jvmTarget
  }

  tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = jvmJavaVersion.toString()
    targetCompatibility = jvmJavaVersion.toString()
  }

}

