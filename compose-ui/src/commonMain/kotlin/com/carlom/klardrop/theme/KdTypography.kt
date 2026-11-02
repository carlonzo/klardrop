// ai-design-check-ignore-file type-scale -- design tokens live here (Apple-aligned 11/13/15/17/20/28); screens use the tokens
// ai-design-check-ignore-file radius-scale -- design tokens live here (6/10/14/18/24/28); screens use the tokens
package com.carlom.klardrop.theme

import androidx.compose.runtime.Composable
import com.klardrop.resources.Res
import com.klardrop.resources.manrope_400
import com.klardrop.resources.manrope_500
import com.klardrop.resources.manrope_600
import com.klardrop.resources.manrope_700
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// KdTypography — type roles matching spec/tokens.css
//
// Manrope → bundled static weights (composeResources/font, OFL), see kdSansFamily()
// JetBrains Mono → FontFamily.Monospace  (system mono — no bundled font)
// ---------------------------------------------------------------------------

data class KdTypography(
    /** 700 · 28/32 · −0.02em — section heroes, onboarding */
    val display: TextStyle,
    /** 700 · 20/26 · −0.01em — screen titles, device names in header */
    val title: TextStyle,
    /** 600 · 17/22 — group headings, dialog titles */
    val headline: TextStyle,
    /** 500 · 15/21 — default; device row name; bubble text */
    val body: TextStyle,
    /** 500 · 13/17 · text/2 — sub-labels, timestamps, banner body */
    val caption: TextStyle,
    /** 600 · 11/14 · 0.10em uc · text/3 — section labels */
    val overline: TextStyle,
    /** Mono 500 · 12/16 — filenames, sizes, IPs, pairing codes */
    val mono: TextStyle,
)

fun kdTypography(sans: FontFamily) = KdTypography(
    display = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.02).em,
    ),
    title = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.01).em,
    ),
    headline = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight(600),
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    body = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight(500),
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    caption = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight(500),
        fontSize = 13.sp,
        lineHeight = 17.sp,
    ),
    overline = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight(600),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.10.em,
    ),
    mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight(500),
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

/** Manrope, the spec's UI face. Static instances: variable fonts need Android API 26+. */
@Composable
fun kdSansFamily(): FontFamily = FontFamily(
    Font(Res.font.manrope_400, FontWeight.Normal),
    Font(Res.font.manrope_500, FontWeight.Medium),
    Font(Res.font.manrope_600, FontWeight.SemiBold),
    Font(Res.font.manrope_700, FontWeight.Bold),
)
