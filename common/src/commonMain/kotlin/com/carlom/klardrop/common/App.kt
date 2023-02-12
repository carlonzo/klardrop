package com.carlom.klardrop.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn


@Composable
fun App(flow: Flow<String>) {

  val socketState by flow.collectAsState("Nothing yet.")

  Column(Modifier.padding(16.dp)) {

    Text(text = "Received: $socketState")

  }

}

fun log(message: String) {
  println("[Klardrop] $message")
}