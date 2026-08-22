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
        implementation(project(":presentation"))
        implementation(project(":compose-ui"))
        implementation(compose.desktop.currentOs)
        implementation(deps.kotlinx.coroutines.core)
        implementation(deps.bugsnag.jvm)
        // Linux StatusNotifierItem tray so Omarchy (and other SNI hosts) can
        // show the live device list from this process. macOS/Windows keep the
        // Compose Desktop AWT tray.
        implementation(deps.nucleus.composenativetray)
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
      // jpackage requires a strictly numeric x.y.z. Stable passes the tag directly via
      // klardrop.version. Nightly's version is a pre-release semver (1.0.1-nightly.N) that
      // jpackage rejects, so nightly passes the numeric base separately as
      // klardrop.packageVersion; klardrop.version still carries the semver for the app's
      // displayed/updater version (KlardropVersion.VERSION). Local builds fall back to 1.0.0.
      packageVersion = providers.gradleProperty("klardrop.packageVersion")
        .orElse(providers.gradleProperty("klardrop.version"))
        .getOrElse("1.0.0")
      modules("jdk.unsupported")
      modules("java.sql")
      // The in-app update checker uses java.net.http.HttpClient; jlink omits this
      // module from the bundled runtime unless we ask for it (NoClassDefFoundError otherwise).
      modules("java.net.http")

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
        // Register an application-menu launcher in the generated .deb.
        shortcut = true
        menuGroup = "Network"
        appCategory = "Network"
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
