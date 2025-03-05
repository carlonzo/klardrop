import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
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
  alias(deps.plugins.sentry.multiplatform) apply false
}

allprojects {
  repositories {
    google()
    mavenCentral()
    mavenLocal()
  }
}

subprojects {

  val javaVersion = JavaVersion.VERSION_17
  val javaTarget = JvmTarget.fromTarget(javaVersion.toString())

  withAndroidPlugin {
    compileOptions {
      sourceCompatibility = javaVersion
      targetCompatibility = javaVersion
    }

    compileSdk = 35
    defaultConfig {
      minSdk = 23
    }
  }

  tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget = javaTarget
  }

  tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = javaVersion.toString()
    targetCompatibility = javaVersion.toString()
  }

}

fun Project.withAndroidPlugin(configureBlock: CommonExtension<*, *, *, *, *, *>.() -> Unit) {
  pluginManager.withPlugin("com.android.application") {
    configure<ApplicationExtension> { configureBlock() }
  }

  pluginManager.withPlugin("com.android.library") {
    configure<LibraryExtension> { configureBlock() }
  }
}

