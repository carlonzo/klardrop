plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.squareup.wire)
}

kotlin {
  jvm()
  iosArm64()
  iosSimulatorArm64()
  macosArm64()
  linuxX64()
  // Declared only when this build can produce linuxArm64 — see `hostCanBuildLinuxArm64`.
  if (rootProject.extra["hostCanBuildLinuxArm64"] as Boolean) linuxArm64()

  targets.all {
    compilations.all {
      compileTaskProvider.configure {
        compilerOptions {
          suppressWarnings.set(true)
        }
      }
    }
  }
}

wire {
  kotlin {
  }
  sourcePath {
    srcDir("src/commonMain/proto")
  }
}


