package com.carlom.klardrop.android

import com.carlom.klardrop.common.App
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop

class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val k = Klardrop(internalPlatformDependency = InternalPlatformDependencies(this))
    k.init()

    val d = k.discovery()
    val discoveryFlow = d.start()

    setContent {

      val received by discoveryFlow.collectAsState("nothing")

      Text(
        text = received
      )

    }
  }
}