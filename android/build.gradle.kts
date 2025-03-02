plugins {
  alias(deps.plugins.kotlin.android)
  alias(deps.plugins.android.application)
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.compose.compiler)
  kotlin("kapt")
//  id("io.sentry.android.gradle") version "4.0.0"
}

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(project(":klardrop-common"))
  implementation(project(":common-ui"))
  implementation(deps.androidx.activity.compose)
  implementation(deps.androidx.appcompat)
  implementation(deps.androidx.core)
  implementation(deps.dagger)
  kapt(deps.dagger.compiler)

  debugImplementation(compose.uiTooling)
  implementation(compose.preview)
}

android {
  compileSdk = 35
  defaultConfig {
    applicationId = "com.carlom.klardrop.android"
    minSdk = 23
    targetSdk = 34
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