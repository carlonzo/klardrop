plugins {
  alias(deps.plugins.kotlin.android)
  alias(deps.plugins.android.application)
  alias(deps.plugins.jetbrains.compose)
  kotlin("kapt")
}

group "com.carlom.klardrop"
version "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(project(":common"))
  implementation(project(":common-ui"))
  implementation("androidx.activity:activity-compose:1.7.1")
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("androidx.core:core-ktx:1.10.1")
  implementation(deps.dagger)
  kapt(deps.dagger.compiler)
}

android {
  compileSdk = 33
  defaultConfig {
    applicationId = "com.carlom.klardrop.android"
    minSdk = 23
    targetSdk = 33
    versionCode = 1
    versionName = "1.0-SNAPSHOT"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
    }
  }
  android.packagingOptions.resources.pickFirsts.addAll(
    listOf(
      "META-INF/versions/**"
    )
  )
  namespace = "com.carlom.klardrop.android"
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