package com.carlom.klardrop.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

// ---------------------------------------------------------------------------
// CompositionLocals
// ---------------------------------------------------------------------------

val LocalKdColors = staticCompositionLocalOf<KdColorScheme> {
    error("No KdColorScheme provided — wrap content with ProvideKdTheme()")
}

val LocalKdTypography = staticCompositionLocalOf<KdTypography> {
    error("No KdTypography provided — wrap content with ProvideKdTheme()")
}

val LocalKdSpacing = staticCompositionLocalOf<KdSpacing> {
    error("No KdSpacing provided — wrap content with ProvideKdTheme()")
}

val LocalKdRadii = staticCompositionLocalOf<KdRadii> {
    error("No KdRadii provided — wrap content with ProvideKdTheme()")
}

val LocalKdMotion = staticCompositionLocalOf<KdMotion> {
    error("No KdMotion provided — wrap content with ProvideKdTheme()")
}

// ---------------------------------------------------------------------------
// KdTheme accessor object
// ---------------------------------------------------------------------------

object KdTheme {
    val colors: KdColorScheme
        @Composable @ReadOnlyComposable
        get() = LocalKdColors.current

    val typography: KdTypography
        @Composable @ReadOnlyComposable
        get() = LocalKdTypography.current

    val spacing: KdSpacing
        @Composable @ReadOnlyComposable
        get() = LocalKdSpacing.current

    val radii: KdRadii
        @Composable @ReadOnlyComposable
        get() = LocalKdRadii.current

    val motion: KdMotion
        @Composable @ReadOnlyComposable
        get() = LocalKdMotion.current
}

// ---------------------------------------------------------------------------
// ProvideKdTheme — wires all locals
// ---------------------------------------------------------------------------

@Composable
fun ProvideKdTheme(
    useDark: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (useDark) KdDarkColors else KdLightColors
    CompositionLocalProvider(
        LocalKdColors provides colors,
        LocalKdTypography provides KdDefaultTypography,
        LocalKdSpacing provides KdDefaultSpacing,
        LocalKdRadii provides KdDefaultRadii,
        LocalKdMotion provides KdDefaultMotion,
        content = content,
    )
}
