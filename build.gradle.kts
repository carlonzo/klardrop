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
 * `true` when this host can actually build the Kotlin/Native Linux arm64 target.
 *
 * The Linux native build links against the *host's* avahi/sqlite/openssl/systemd — the
 * cinterop .def files point at `/usr/include` and `-L/usr/lib64`, and the release jobs run
 * the arm64 leg on a native `ubuntu-24.04-arm`. Kotlin/Native can cross-compile linuxArm64
 * from an x64 Linux host, so nothing host-locks it out of the build there: the cinterop
 * would index x86_64 headers for an arm64 target and the link would pick up x86_64 shared
 * objects. Modules declare `linuxArm64()` only where it can really be built, so
 * `./gradlew build` on an x64 Linux host tests what it can instead of failing on a
 * cross-compile no consumer of that build wants.
 */
val hostCanBuildLinuxArm64: Boolean =
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
}


