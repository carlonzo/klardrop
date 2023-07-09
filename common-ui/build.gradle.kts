plugins {
  alias(deps.plugins.kotlin.multiplatform)
  kotlin("native.cocoapods")
  alias(deps.plugins.jetbrains.compose)
  alias(deps.plugins.android.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
  targetHierarchy.default()

  androidTarget()
  jvm("desktopJvm") {
    compilations.all {
      kotlinOptions.jvmTarget = "17"
    }
  }
  iosArm64()
  iosSimulatorArm64()
//  macosArm64()

  cocoapods {
    version = "1.0.0"
    summary = "Some description for the Shared Module"
    homepage = "Link to the Shared Module homepage"
    ios.deploymentTarget = "14.1"
    podfile = project.file("../iosApp/Podfile")
    name = "common_ui"
    framework {
      baseName = "common_ui"
      isStatic = true
      export(project(":common"))
    }
//    extraSpecAttributes["resources"] = "['src/commonMain/resources/**', 'src/iosMain/resources/**']"
  }





  sourceSets {

    val commonMain by getting {
      dependencies {
        api(compose.runtime)
        api(compose.foundation)
        api(compose.material3)
        api(compose.ui)

        api(deps.kotlinx.coroutines.core)

        api(project(":common"))
      }
    }

    val desktopJvmMain by getting {
      dependencies {
        implementation(compose.preview)
      }
    }

    val androidMain by getting {
      dependencies {
        implementation(compose.preview)
        implementation(compose.uiTooling)
      }
    }
  }
}

android {
  namespace = "com.klardrop.common.ui"
  compileSdk = 33
  defaultConfig {
    minSdk = 23
  }
}


//// See https://youtrack.jetbrains.com/issue/KT-55751
//val myAttribute = Attribute.of("myOwnAttribute", String::class.java)
//
//// replace releaseFrameworkIosFat by the name of the first configuration that conflicts
//configurations.named("podReleaseFrameworkIosFat").configure {
//  attributes {
//    // put a unique attribute
//    attribute(myAttribute, "pod-release-fat")
//  }
//}
//
//configurations.named("podDebugFrameworkIosFat").configure {
//  attributes {
//    // put a unique attribute
//    attribute(myAttribute, "pod-debug-fat")
//  }
//}
//
//// replace debugFrameworkIosFat by the name of the second configuration that conflicts
//configurations.named("podDebugFrameworkIosArm64").configure {
//  attributes {
//    attribute(myAttribute, "pod-debug-ios-arm64")
//  }
//}
//
//configurations.named("podReleaseFrameworkIosArm64").configure {
//  attributes {
//    attribute(myAttribute, "pod-release-ios-arm64")
//  }
//}
