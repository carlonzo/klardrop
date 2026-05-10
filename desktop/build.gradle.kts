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

      // Per-platform icons. Compose Desktop's bundler requires the native
      // icon format for each target: .icns on macOS, .ico on Windows, .png
      // on Linux. Sourced from the Klardrop brand asset set.
      macOS {
        iconFile.set(file("icons/Klardrop.icns"))
        infoPlist {
          extraKeysRawXml = """
            <key>NSBluetoothAlwaysUsageDescription</key>
            <string>Klardrop uses Bluetooth to discover and share with nearby devices when Wi-Fi is unavailable.</string>
          """.trimIndent()
        }
      }
      windows {
        iconFile.set(file("icons/Klardrop.ico"))
      }
      linux {
        iconFile.set(file("icons/Klardrop.png"))
      }
    }
    buildTypes.release.proguard {
      configurationFiles.from("rules.pro")
    }
  }
}

// Compose Desktop distributions are host-locked (DMG only builds on macOS, MSI on
// Windows, DEB on Linux). On macOS the runtime uses native Bonjour via libdns_sd,
// so jmDNS would be dead weight in the bundle — drop it from the runtime classpath
// when the build host is macOS so it never lands in the .app/.dmg/.pkg.
val isMacHost: Boolean = run {
  val osName = System.getProperty("os.name").orEmpty().lowercase()
  osName.contains("mac") || osName.contains("darwin")
}

if (isMacHost) {
  configurations.matching { it.name == "jvmRuntimeClasspath" }.configureEach {
    exclude(group = "org.jmdns", module = "jmdns")
  }
}
