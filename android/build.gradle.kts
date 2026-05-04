plugins {
  alias(deps.plugins.android.application)
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.compose.compiler)
  alias(deps.plugins.ksp)
  id("com.bugsnag.android.gradle") version "8.+"
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
  ksp(deps.dagger.compiler)

  implementation(deps.bugsnag.android)

  debugImplementation(compose.uiTooling)
  implementation(compose.preview)
}

android {
  compileSdk = 37
  defaultConfig {
    applicationId = "com.carlom.klardrop.android"
    minSdk = 24
    targetSdk = 35
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
  packaging {
    resources {
      pickFirsts += "META-INF/versions/**"
    }
  }
  namespace = "com.carlom.klardrop.android"
}

// Pull the compose multiplatform Android assets from :common-ui into this
// app's assets. The KMP Android library plugin's AAR pipeline silently
// drops them, so we wire the producing directory in directly.
val composeAndroidAssetsFromCommonUi by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies {
  composeAndroidAssetsFromCommonUi(
    project(mapOf("path" to ":common-ui", "configuration" to "composeAndroidAssets"))
  )
}

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory(
      composeAndroidAssetsFromCommonUi.singleFile.absolutePath
    )
  }
}

afterEvaluate {
  tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(composeAndroidAssetsFromCommonUi) }
}
