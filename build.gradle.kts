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
  val androidJvmTarget = JvmTarget.fromTarget(androidJavaVersion.toString())
  val jvmTarget = JvmTarget.fromTarget(jvmJavaVersion.toString())

  fun configureAndroid(extension: BaseExtension) {
    extension.apply {
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

  pluginManager.withPlugin("com.android.application") {
    configure<BaseExtension> { configureAndroid(this) }
  }

  pluginManager.withPlugin("com.android.library") {
    configure<BaseExtension> { configureAndroid(this) }
  }

  // Set JVM target per task: Android-related tasks get 17, others get 21.
  // In KMP modules, Android tasks contain "Android" in their name.
  // In pure Android modules (kotlin-android plugin), tasks are "compileDebugKotlin" etc.
  afterEvaluate {
    val isAndroidOnlyProject = pluginManager.hasPlugin("org.jetbrains.kotlin.android")
    tasks.withType<KotlinJvmCompile>().configureEach {
      val isAndroidTask = isAndroidOnlyProject || name.contains("Android", ignoreCase = true)
      compilerOptions.jvmTarget = if (isAndroidTask) androidJvmTarget else jvmTarget
    }
    tasks.withType<JavaCompile>().configureEach {
      val isAndroidTask = isAndroidOnlyProject || name.contains("Android", ignoreCase = true)
      val version = if (isAndroidTask) androidJavaVersion else jvmJavaVersion
      sourceCompatibility = version.toString()
      targetCompatibility = version.toString()
    }
  }

}

