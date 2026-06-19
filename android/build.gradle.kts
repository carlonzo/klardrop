plugins {
  alias(deps.plugins.android.application)
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.compose.compiler)
  alias(deps.plugins.ksp)
  id("com.bugsnag.android.gradle") version "8.+"
}

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"

// Version single source of truth: driven by CI. versionName comes straight from
// `klardrop.version` (the `vX.Y.Z` tag for stable, a date for nightly); versionCode
// is a monotonic integer = (commit count)*2, +1 for nightly/tester builds, computed
// in CI and passed as `klardrop.versionCode`. Local builds fall back to dev values.
val klardropVersionName: String = providers.gradleProperty("klardrop.version").getOrElse("1.0-SNAPSHOT")
val klardropVersionCode: Int = providers.gradleProperty("klardrop.versionCode").map { it.toInt() }.getOrElse(1)

// Release signing: keystore + credentials come from env vars (CI) or gradle
// properties (local, e.g. ~/.gradle/gradle.properties or -P). When absent we fall
// back to debug signing so plain dev builds and secret-less CI still work. The
// keystore itself is never committed.
fun signingValue(env: String, prop: String): String? =
  providers.environmentVariable(env).orNull ?: providers.gradleProperty(prop).orNull

val releaseStoreFilePath = signingValue("KLARDROP_KEYSTORE_FILE", "klardrop.keystoreFile")
val releaseStorePassword = signingValue("KLARDROP_KEYSTORE_PASSWORD", "klardrop.keystorePassword")
val releaseKeyAlias = signingValue("KLARDROP_KEY_ALIAS", "klardrop.keyAlias")
val releaseKeyPassword = signingValue("KLARDROP_KEY_PASSWORD", "klardrop.keyPassword") ?: releaseStorePassword
val hasReleaseSigning =
  releaseStoreFilePath != null && releaseStorePassword != null && releaseKeyAlias != null

repositories {
  mavenCentral()
}

dependencies {
  implementation(project(":klardrop-common"))
  implementation(project(":presentation"))
  implementation(project(":compose-ui"))
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
    // Play Store package name — must match the registered listing exactly.
    // (namespace below stays com.carlom.klardrop.android: it's the source/R/BuildConfig
    // package, independent of the install id. FileProvider authority is derived from
    // applicationId at runtime, so it follows this automatically.)
    applicationId = "com.carlom.klardrop"
    minSdk = 24
    targetSdk = 35
    versionCode = klardropVersionCode
    versionName = klardropVersionName
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
  }

  signingConfigs {
    // Shared debug key, committed at android/debug.keystore so every machine + CI
    // signs debug builds identically (well-known creds — no secrecy intended).
    getByName("debug") {
      storeFile = file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(releaseStoreFilePath!!)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {

    getByName("release") {
      isMinifyEnabled = true
      // Real signing key when configured (CI / local with creds); otherwise debug
      // so the release variant still builds and installs for development.
      signingConfig = if (hasReleaseSigning) {
        signingConfigs.getByName("release")
      } else {
        signingConfigs.getByName("debug")
      }
    }

    getByName("debug") {
      applicationIdSuffix = ".debug"
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

// Pull the compose multiplatform Android assets from :compose-ui into this
// app's assets. The KMP Android library plugin's AAR pipeline silently
// drops them, so we wire the producing directory in directly.
val composeAndroidAssetsFromCommonUi by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies {
  composeAndroidAssetsFromCommonUi(
    project(mapOf("path" to ":compose-ui", "configuration" to "composeAndroidAssets"))
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
