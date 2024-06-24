import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(deps.plugins.kotlin.multiplatform)
//  alias(deps.plugins.jetbrains.compose)
//  alias(deps.plugins.compose.compiler)
}

group = "com.carlom.klardrop"
version = "1.0-SNAPSHOT"


kotlin {
  macosArm64 {
    binaries {
      executable {
      }
    }
  }

  sourceSets {



    val macosArm64Main by getting {
      dependencies {
//        implementation(project(":klardrop-common"))

//        implementation(deps.kotlinx.coroutines.core)
      }
    }


  }
}

//compose.desktop {
//  application {
//    mainClass = "MainKt"
//    nativeDistributions {
//      targetFormats(TargetFormat.Dmg)
//      packageName = "klardrop"
//      packageVersion = "1.0.0"
//    }
//  }
//}
