package com.carlom.klardrop.android

import com.carlom.klardrop.common.App
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val k = Klardrop(internalPlatformDependency = InternalPlatformDependencies(this))
    k.init()

    val d = k.discovery()
    d.start()

    val knownDevices = k.knownDevices()

    setContent {

      val received by knownDevices.collectAsState(emptyMap())

      Column {
        received.map {
          Text(
            text = it.value.toString()
          )
        }
      }


    }
  }
}