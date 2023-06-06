import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.jetbrains.compose)
}

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"


kotlin {
  jvm {
    compilations.all {
      kotlinOptions.jvmTarget = "17"
    }
    withJava()
  }

  sourceSets {

    val jvmMain by getting {
      dependencies {
        implementation(project(":common"))
        implementation(project(":common-ui"))
        implementation(compose.desktop.currentOs)
        implementation(deps.kotlinx.coroutines.core)
      }
    }
    val jvmTest by getting

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
    }
    buildTypes.release.proguard {
      configurationFiles.from("rules.pro")
    }
  }
}
