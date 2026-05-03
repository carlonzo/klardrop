plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.squareup.wire)
}

kotlin {
  jvm()
  iosArm64()
  iosSimulatorArm64()

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


