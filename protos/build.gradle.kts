plugins {
  alias(deps.plugins.kotlin.multiplatform)
  alias(deps.plugins.android.library)
  alias(deps.plugins.squareup.wire)
}

kotlin {
  androidTarget()
  jvm()
  iosArm64()
  iosSimulatorArm64()


  wire {
    kotlin {
    }
  }
}

android {
  namespace = "com.klardrop.common.protos"
}


