package com.carlom.klardrop.android

import DiscoveryDashboard
import DiscoveryUIController
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop

class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val k = Klardrop(internalPlatformDependency = InternalPlatformDependencies(this))
    k.init()

    val discoveryController = DiscoveryUIController(k.commonComponent)


    setContent {

      DiscoveryDashboard(discoveryController)

    }
  }
}