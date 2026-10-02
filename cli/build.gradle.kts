import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.kotlin.serialization)
}

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"

kotlin {
  jvm {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }

    // Configure main class for execution
    mainRun {
      mainClass.set("com.carlom.klardrop.cli.MainKt")
    }
  }

  // Two binaries, two names, deliberately:
  //   bin/klardrop        -> the Rust client (cli-rust/), the user-facing CLI
  //   bin/klardrop-engine -> this Kotlin/Native engine (daemon, listen, ...)
  // The engine is a separate binary so no client can accidentally start a second
  // engine: a stray `klardrop` invocation is either the Rust client (which talks to
  // the running engine over the control file) or an explicit `klardrop-engine daemon`.
  // NEVER rename this baseName back to "klardrop" — the names collide in the tarball.
  linuxX64 {
    binaries {
      executable {
        baseName = "klardrop-engine"
        entryPoint = "com.carlom.klardrop.cli.main"
        binaryOption("pagedAllocator", "false")
      }
    }
    binaries.all {
      linkerOpts(
        "-Wl,--as-needed",
        // Kotlin/Native links against its bundled older glibc sysroot; host libs reference
        // newer glibc symbol versions that resolve correctly at runtime.
        "--allow-shlib-undefined",
        "-lsqlite3",
      )
    }
  }

  linuxArm64 {
    binaries {
      executable {
        // Same name split as linuxX64 above; see the comment there.
        baseName = "klardrop-engine"
        entryPoint = "com.carlom.klardrop.cli.main"
        binaryOption("pagedAllocator", "false")
      }
    }
    binaries.all {
      linkerOpts(
        "-Wl,--as-needed",
        // Same bundled-sysroot arrangement as linuxX64 above; arm64 runners provide
        // the aarch64 system libs at these paths natively (no cross-linking).
        "--allow-shlib-undefined",
        "-lsqlite3",
      )
    }
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":klardrop-common"))
        implementation(project(":presentation"))
        implementation(project(":control-plane"))
        implementation(deps.kotlinx.coroutines.core)
        implementation(deps.clikt)
        implementation(deps.filekit.core)
        implementation(deps.kotlinx.serialization.json)
        implementation(deps.ktor.network)
      }
    }

    val jvmMain by getting {
      dependencies {
        implementation(deps.kotlinx.coroutines.core)
      }
    }

    val linuxMain by getting {
      dependencies {
        implementation(deps.kotlinx.coroutines.core)
      }
    }

    // Same as :klardrop-common: every linux source set (intermediate + each arch) must
    // carry the cinterop opt-in, or adding a second linux target fails configuration
    // with "Inconsistent settings ... the dependent source set must use all opt-in
    // annotations that its dependency uses".
    matching { it.name.startsWith("linux") }.configureEach {
      languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }

    all {
      languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
    }
  }
}

// macOS: hide Dock icon and name the process before AWT initializes (see CliPlatformRuntime.jvm.kt).
tasks.withType<JavaExec>().configureEach {
  if (project.path == ":cli") {
    val isMac = org.gradle.internal.os.OperatingSystem.current().isMacOsX
    if (isMac) {
      jvmArgs(
        "-Dapple.awt.UIElement=true",
        "-Dapple.awt.application.name=klardrop",
        "-Dcom.apple.mrj.application.apple.menu.about.name=klardrop",
        "-Xdock:name=klardrop",
      )
    }
  }
}