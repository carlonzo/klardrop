plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.squareup.wire)
}

kotlin {
  jvm()
  iosArm64()
  iosSimulatorArm64()
}

wire {
  kotlin {
  }
  sourcePath {
    srcDir("src/commonMain/proto")
  }
}


