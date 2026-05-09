package com.carlom.klardrop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// AppTheme — Material3 substrate bridged from KD tokens
//
// The Material ColorScheme / Typography / Shapes are derived from KD values
// so that existing screens that call MaterialTheme.colorScheme.* continue
// to render acceptably during the Wave 2 migration.  Wave 3 will remove this
// bridge once all screens compose from KdTheme directly.
// ---------------------------------------------------------------------------

@Composable
fun AppTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val kd = if (useDarkTheme) KdDarkColors else KdLightColors

    val colorScheme = if (useDarkTheme) {
        darkColorScheme(
            background             = kd.bg0,
            surface                = kd.bg1,
            surfaceContainerHigh   = kd.bg2,
            surfaceContainerHighest= kd.bg3,
            outline                = kd.border,
            outlineVariant         = kd.divider,
            onSurface              = kd.text,
            onSurfaceVariant       = kd.text2,
            primary                = kd.accent,
            onPrimary              = kd.textInv,
            error                  = kd.err,
            onError                = kd.textInv,
            tertiary               = kd.trust,
            onTertiary             = kd.trustFg,
        )
    } else {
        lightColorScheme(
            background             = kd.bg0,
            surface                = kd.bg1,
            surfaceContainerHigh   = kd.bg2,
            surfaceContainerHighest= kd.bg3,
            outline                = kd.border,
            outlineVariant         = kd.divider,
            onSurface              = kd.text,
            onSurfaceVariant       = kd.text2,
            primary                = kd.accent,
            onPrimary              = kd.textInv,
            error                  = kd.err,
            onError                = kd.textInv,
            tertiary               = kd.trust,
            onTertiary             = kd.trustFg,
        )
    }

    // Bridge KD type roles to Material3 type scale
    val typography = Typography(
        displayLarge  = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp),
        titleLarge    = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium   = TextStyle(fontWeight = FontWeight(600), fontSize = 17.sp, lineHeight = 22.sp),
        bodyLarge     = TextStyle(fontWeight = FontWeight(500), fontSize = 15.sp, lineHeight = 21.sp),
        bodyMedium    = TextStyle(fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 17.sp),
        labelSmall    = TextStyle(fontWeight = FontWeight(600), fontSize = 11.sp, lineHeight = 14.sp),
    )

    // Bridge KD radii to Material3 shapes
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.0.dp),
        small      = RoundedCornerShape(10.0.dp),
        medium     = RoundedCornerShape(14.0.dp),
        large      = RoundedCornerShape(18.0.dp),
        extraLarge = RoundedCornerShape(24.0.dp),
    )

    ProvideKdTheme(useDark = useDarkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = typography,
            shapes      = shapes,
            content     = content,
        )
    }
}
