@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
  alias(deps.plugins.android.application)
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.compose.compiler)
  alias(deps.plugins.sentry.android)
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
  implementation(deps.androidx.core)

  debugImplementation(compose.uiTooling)
  debugImplementation(project(":debug-control"))
  implementation(compose.preview)

  testImplementation(deps.junit4)

  androidTestImplementation(deps.androidx.test.core)
  androidTestImplementation(deps.androidx.test.ext.junit)
  androidTestImplementation(deps.androidx.test.runner)
  androidTestImplementation(deps.compose.ui.test.junit4)
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
    targetSdk = 36
    versionCode = klardropVersionCode
    versionName = klardropVersionName
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

// R8 renames every class and method in the release APK, so a production crash arrives
// at Sentry as `a.b.c(SourceFile:1)` unless Sentry holds the matching mapping. The
// plugin stamps a UUID into the APK's assets and uploads mapping.txt under that same
// UUID, so the two halves have to be produced together — hence in the build, not as a
// separate CI step. This replaces what com.bugsnag.android.gradle used to do.
sentry {
  // MANDATORY. The Sentry SDK already reaches this APK transitively: sentry-kotlin-
  // multiplatform 0.27.0 (via :klardrop-common) pulls io.sentry:sentry-android 8.41.0,
  // the version it was built against. Auto-installation would add a second, direct
  // io.sentry:sentry-android at the version *this plugin* ships (8.53.0 for 6.19.0),
  // and Gradle's conflict resolution would quietly hand the KMP SDK the newer one.
  // We want the mapping upload out of this plugin and nothing else — and that means
  // this flag has to be re-checked on every plugin bump, not set and forgotten.
  autoInstallation.enabled.set(false)

  // This PR is a like-for-like crash-reporter swap, so anything that changes what the
  // app *does* at runtime stays off. Tracing instrumentation rewrites bytecode to wrap
  // SQLite, file I/O, OkHttp and Compose in performance spans; runtimeOptimizations
  // rewrites the SDK's own class-availability checks. Neither was asked for, and both
  // would land in a release APK nobody profiled.
  tracingInstrumentation.enabled.set(false)
  runtimeOptimizations.enabled.set(false)

  // The dependency report would add a sentry-external-modules.txt asset listing the whole
  // dependency graph to every event. The mapping alone deobfuscates stack traces.
  // (includeSourceContext is not set: it already defaults to false.)
  includeDependenciesReport.set(false)

  // On by default, this reports build metrics to Sentry's own project on every
  // invocation, local dev builds included. Not something a crash-reporter swap adds.
  telemetry.set(false)

  // Debug builds are not minified and never leave a developer's machine, so there is
  // no mapping worth uploading and no reason to run sentry-cli on every debug build.
  ignoredBuildTypes.set(setOf("debug"))

  // org / projectName / authToken are deliberately left unset. sentry-cli reads
  // SENTRY_ORG, SENTRY_PROJECT and SENTRY_AUTH_TOKEN from the environment it inherits,
  // which is how the release workflow already feeds the dSYM upload. Hardcoding the
  // slugs would publish them in a public repo and stop a fork from pointing the build
  // at its own org.

  // With no token there is nothing to upload to, and neither a fork PR build nor a
  // local `assembleRelease` may fail over that. Unset, the mapping task still runs but
  // as a sentry-cli dry run (`--no-upload`), so UUID stamping stays exercised and the
  // APK is identical. Read through `providers` rather than System.getenv(): the
  // configuration cache is on (gradle.properties) and only the provider is tracked as
  // a build input.
  autoUploadProguardMapping.set(
    providers.environmentVariable("SENTRY_AUTH_TOKEN").map { it.isNotBlank() }.orElse(false)
  )
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
