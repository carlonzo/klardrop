package com.carlom.klardrop.common

data class ApplicationInfo(
  val isDebug: Boolean = false,

  // useful for testing on desktop to run multiple instance in the same machine
  val disablePersistence: Boolean = false,

  val enableKlardropServer: Boolean = true,

  val enableNearbyServer: Boolean = true,

  val appVersion: String = KlardropVersion.VERSION
)
