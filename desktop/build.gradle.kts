import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.compose.compiler)
}

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"


kotlin {
  jvm {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }
  }

  sourceSets {

    val jvmMain by getting {
      dependencies {
        implementation(project(":klardrop-common"))
        implementation(project(":common-ui"))
        implementation(compose.desktop.currentOs)
        implementation(deps.kotlinx.coroutines.core)
        implementation(deps.bugsnag.jvm)
      }
    }
    val jvmTest by getting
    
    all {
      languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
    }

  }
}

compose.desktop {
  application {
    mainClass = "MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "klardrop"
      packageVersion = "1.0.0"
      modules("jdk.unsupported")
      modules("java.sql")

      val icon = file("icon_launcher.png")
      macOS {
        iconFile.set(icon)
        infoPlist {
          extraKeysRawXml = """
            <key>NSBluetoothAlwaysUsageDescription</key>
            <string>Klardrop uses Bluetooth to discover and share with nearby devices when Wi-Fi is unavailable.</string>
          """.trimIndent()
        }
      }
      windows {
        iconFile.set(icon)
      }
      linux {
        iconFile.set(icon)
      }
    }
    buildTypes.release.proguard {
      configurationFiles.from("rules.pro")
    }
  }
}
