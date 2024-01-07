package com.carlom.klardrop.messages

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import com.carlom.klardrop.common.Klardrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MessageScreen(
  klardrop: Klardrop,
  senderDeviceId: String
) {

  val messageScreenPresenter = remember { MessageScreenPresenter(klardrop.commonComponent) }


  Surface {
    MessageScreen(messageScreenPresenter = messageScreenPresenter)
  }

}

@Composable
internal fun MessageScreen(
  messageScreenPresenter: MessageScreenPresenter
) {

  val scope = rememberCoroutineScope()

  val headerUiState by scope.collectMolecule { messageScreenPresenter.header() }

  MessageScreenHeader(headerUiState = headerUiState)
}

@Composable
internal fun MessageScreenHeader(headerUiState: MessageScreenPresenter.HeaderUIState) {

  Row {
    Text(text = headerUiState.deviceName)
  }

}

@Composable
fun <T> CoroutineScope.collectMolecule(body: @Composable () -> T): State<T> {
  return launchMolecule(mode = RecompositionMode.ContextClock, body = body).collectAsState()
}