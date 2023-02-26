
group "com.carlom.klardrop"
version "1.0-SNAPSHOT"

plugins {
  alias(deps.plugins.kotlin.multiplatform) apply false
  alias(deps.plugins.kotlin.android) apply false
  alias(deps.plugins.android.application) apply false
  alias(deps.plugins.android.library) apply false
  alias(deps.plugins.jetbrains.compose) apply false
  alias(deps.plugins.kotlin.serialization) apply false
}

allprojects {
  repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

subprojects {

  pluginManager.withPlugin("android-library") {
    configure<com.android.build.gradle.BaseExtension> {
      compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }
    }


  }

  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
      kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
      }
  }

  tasks.withType<JavaCompile>().configureEach {
     sourceCompatibility = JavaVersion.VERSION_1_8.toString()
     targetCompatibility = JavaVersion.VERSION_1_8.toString()
  }

}

