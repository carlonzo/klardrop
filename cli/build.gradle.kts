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

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":klardrop-common"))
        implementation(deps.kotlinx.coroutines.core)
        implementation(deps.clikt)
        implementation(deps.filekit.core)
        implementation(deps.kotlinx.serialization.json)
      }
    }

    val jvmMain by getting {
      dependencies {
      }
    }

    all {
      languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
    }
  }
}

// macOS: hide Dock icon and name the process before AWT initializes (see CliPlatformRuntime.jvm.kt).
tasks.withType<JavaExec>().configureEach {
  if (project.path == ":cli") {
    jvmArgs(
      "-Dapple.awt.UIElement=true",
      "-Dapple.awt.application.name=klardrop",
      "-Dcom.apple.mrj.application.apple.menu.about.name=klardrop",
      "-Xdock:name=klardrop",
    )
  }
}