package com.carlom.klardrop.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.remember
import com.carlom.klardrop.FilePickerFactory
import com.carlom.klardrop.KlardropApp
import com.carlom.klardrop.UiDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme
import javax.inject.Inject

class MainActivity : AppCompatActivity() {


  @Inject
  lateinit var klardrop: Klardrop

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applicationComponent().inject(this)

    setContent {

      val uiDependencies = remember {
        object : UiDependencies {
          override fun filePickerFactory(): FilePickerFactory {
            return FilePickerFactory()
          }

        }
      }

      AppTheme {

        KlardropApp(
          klardrop,
          uiDependencies
        )

      }


    }


  }

}