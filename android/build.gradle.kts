plugins {
  alias(deps.plugins.android.application)
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.compose.compiler)
  alias(deps.plugins.ksp)
  id("com.bugsnag.android.gradle") version "8.+"
}

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"

// Version single source of truth: driven by the `vX.Y.Z` git tag via CI.
// versionName comes straight from `klardrop.version`; versionCode is computed
// in CI as major*10000 + minor*100 + patch and passed as `klardrop.versionCode`.
// Local builds fall back to dev values.
val klardropVersionName: String = providers.gradleProperty("klardrop.version").getOrElse("1.0-SNAPSHOT")
val klardropVersionCode: Int = providers.gradleProperty("klardrop.versionCode").map { it.toInt() }.getOrElse(1)

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
    versionCode = klardropVersionCode
    versionName = klardropVersionName
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

abstract class SyncComposeAndroidAssets : org.gradle.api.DefaultTask() {
  @get:org.gradle.api.tasks.InputFiles
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
  abstract val source: org.gradle.api.file.ConfigurableFileCollection

  @get:org.gradle.api.tasks.OutputDirectory
  abstract val destination: org.gradle.api.file.DirectoryProperty

  @get:javax.inject.Inject
  abstract val fs: org.gradle.api.file.FileSystemOperations

  @org.gradle.api.tasks.TaskAction
  fun run() {
    fs.sync {
      from(source)
      into(destination)
    }
  }
}

val syncComposeAndroidAssets =
  tasks.register("syncComposeAndroidAssets", SyncComposeAndroidAssets::class.java) {
    source.from(composeAndroidAssetsFromCommonUi)
    destination.set(layout.buildDirectory.dir("intermediates/composeAndroidAssets"))
  }

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
      syncComposeAndroidAssets,
      SyncComposeAndroidAssets::destination
    )
  }
}
