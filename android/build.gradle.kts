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
  implementation(deps.androidx.activity.compose)
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation(deps.dagger)
  kapt(deps.dagger.compiler)

  debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
  implementation("androidx.compose.ui:ui-tooling-preview:1.6.7")
}

android {
  compileSdk = 34
  defaultConfig {
    applicationId = "com.carlom.klardrop.android"
    minSdk = 23
    targetSdk = 33
    versionCode = 1
    versionName = "1.0-SNAPSHOT"
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
  }

  buildTypes {

    getByName("release") {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
    }

    getByName("debug") {
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  android.packagingOptions.resources.pickFirsts.addAll(
    listOf(
      "META-INF/versions/**"
    )
  )
  namespace = "com.carlom.klardrop.android"
}