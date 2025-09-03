import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
}

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"

kotlin {
  jvm {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_17
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
      }
    }

    val jvmMain by getting {
      dependencies {
        implementation(deps.bugsnag.jvm)
      }
    }

    all {
      languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
    }
  }
}