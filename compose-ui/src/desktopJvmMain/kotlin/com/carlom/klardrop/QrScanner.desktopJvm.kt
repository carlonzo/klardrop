package com.carlom.klardrop

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberQrScanner(onResult: (String) -> Unit): (() -> Unit)? = null
