package com.carlom.klardrop.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
expect fun rememberDynamicColorScheme(isDark: Boolean): ColorScheme?
