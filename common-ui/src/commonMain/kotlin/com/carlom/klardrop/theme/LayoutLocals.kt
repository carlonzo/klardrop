package com.carlom.klardrop.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Set by the platform entry point. Controls a few small layout choices that
 * differ between the JVM desktop window and a tablet (iPad / Android tablet):
 *
 *  · the window-shell tone behind the floating sidebar (deep slate on
 *    desktop, regular bg0 on tablet so the iPad status-bar blends with
 *    the rest of the app)
 *  · whether the sidebar sheet wraps the macOS traffic-light buttons
 */
val LocalIsDesktop = staticCompositionLocalOf { false }

/**
 * Window-chrome insets the platform reserves at the top of the content area.
 * On macOS this is ~28 dp for the traffic-light strip; on iOS/Android the
 * platform handles the status bar via `WindowInsets.statusBars` instead.
 */
val LocalContentInsets = compositionLocalOf { PaddingValues(0.dp) }
