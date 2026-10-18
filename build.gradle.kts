import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"

plugins {
  alias(deps.plugins.kotlin.multiplatform) apply false
  alias(deps.plugins.android.application) apply false
  alias(deps.plugins.android.library) apply false
  alias(deps.plugins.android.kmp.library) apply false
  alias(deps.plugins.jetbrains.compose) apply false
  alias(deps.plugins.compose.compiler) apply false
  alias(deps.plugins.kotlin.serialization) apply false
  alias(deps.plugins.sqldelight) apply false
}

/**
 * `true` when this build should declare the Kotlin/Native linuxArm64 target.
 *
 * Kotlin/Native 2.4 ships no compiler for a linux-aarch64 *host* (supported hosts are
 * linux x64, windows x64, and macOS). On `ubuntu-24.04-arm` the plugin disables
 * linuxArm64, `linkReleaseExecutableLinuxArm64` is SKIPPED, and Gradle still exits 0 —
 * which is how the nightly arm64 tarball lost its engine. The release jobs therefore
 * cross-compile that target from ubuntu-24.04 by passing `-Pklardrop.forceLinuxArm64=true`
 * and installing the aarch64 multiarch headers and libraries. The cinterop `.def` files
 * select those with `compilerOpts.linux_arm64` / `linkerOpts.linux_arm64`, so the cross
 * compile does not index the host's x86_64 headers or link its x86_64 shared objects.
 *
 * An ordinary x64 `./gradlew build` leaves the target off: that build does not install
 * the aarch64 dev packages, and no consumer of it wants the cross compile.
 */
val hostCanBuildLinuxArm64: Boolean =
  findProperty("klardrop.forceLinuxArm64")?.toString() == "true" ||
    !(System.getProperty("os.name") == "Linux" &&
      System.getProperty("os.arch") in setOf("amd64", "x86_64"))

allprojects {
  extra["hostCanBuildLinuxArm64"] = hostCanBuildLinuxArm64
  repositories {
    google()
    mavenCentral()
  }
}

subprojects {

  val javaVersion = JavaVersion.VERSION_21
  val javaTarget = JvmTarget.fromTarget(javaVersion.toString())

  pluginManager.withPlugin("com.android.application") {
    configure<ApplicationExtension> {
      compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
      }
    }
  }

  tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget = javaTarget
  }

  tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = javaVersion.toString()
    targetCompatibility = javaVersion.toString()
  }

  // Workaround for Kotlin 2.4.20 swift-export bug:
  // swift-export emits a fatalError @_spi stub plus the default-body implementation for opt-in
  // (@ExperimentalSerializationApi -> @_spi) interface members inside the same extension,
  // causing "invalid redeclaration" Swift compile errors.
  // Removal condition: can be removed when Kotlin fixes this bug in swift-export;
  // check by deleting this post-processing step and building the app.
  val swiftExportTaskClass = try {
    @Suppress("UNCHECKED_CAST")
    Class.forName("org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftexport.tasks.SwiftExportTask") as Class<Task>
  } catch (_: ClassNotFoundException) {
    null
  }
  if (swiftExportTaskClass != null) {
    tasks.withType(swiftExportTaskClass).configureEach {
      val getParams = javaClass.getMethod("getParameters")
      val params = getParams.invoke(this)
      val getOutputPath = params.javaClass.getMethod("getOutputPath")
      @Suppress("UNCHECKED_CAST")
      val outputDir = getOutputPath.invoke(params) as org.gradle.api.provider.Provider<org.gradle.api.file.Directory>
      doLast {
        val dir = outputDir.orNull?.asFile ?: return@doLast
        if (!dir.exists()) return@doLast
        val duplicateSpiStubRegex = Regex(
          """(?s)(\n[ \t]*@_spi\([^)]*\)\n[ \t]*public func [^{]*\{)\n[ \t]*fatalError\("'[^']*' is an @_spi requirement that must be implemented by Swift conformers"\)\n[ \t]*\}(?=\1)"""
        )
        dir.walkTopDown()
          .filter { it.isFile && it.extension == "swift" }
          .forEach { file ->
            val content = file.readText()
            val patched = duplicateSpiStubRegex.replace(content, "")
            if (patched != content) {
              file.writeText(patched)
            }
          }
      }
    }
  }
}


