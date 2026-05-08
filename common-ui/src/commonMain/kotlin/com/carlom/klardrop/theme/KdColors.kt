package com.carlom.klardrop.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// KdColors — sRGB conversions of every oklch token from spec/tokens.css
// Conversion: oklch → oklab → linear sRGB → sRGB (γ 2.4 transfer function)
// Computed offline; stored as compile-time constants.
// ---------------------------------------------------------------------------

// ---- Dark (default) palette -----------------------------------------------

private val _dark_bg0        = Color(0xFF0A0F13)
private val _dark_bg1        = Color(0xFF151A1F)
private val _dark_bg2        = Color(0xFF20252A)
private val _dark_bg3        = Color(0xFF2B3035)
private val _dark_border     = Color(0xFF30363C)
private val _dark_divider    = Color(0xFF20252A)

private val _dark_text       = Color(0xFFF5F3F0)
private val _dark_text2      = Color(0xFFACB2B9)
private val _dark_text3      = Color(0xFF6F757B)
private val _dark_textInv    = Color(0xFF0E1217)

private val _dark_accent     = Color(0xFFF39762)
private val _dark_accentHi   = Color(0xFFFFAE7F)
private val _dark_accentLo   = Color(0xFFCA723C)

private val _dark_trust      = Color(0xFF7ECBB6)
private val _dark_trustFg    = Color(0xFF0A2A23)

private val _dark_ok         = Color(0xFF77C87A)
private val _dark_warn       = Color(0xFFE9B452)
private val _dark_err        = Color(0xFFEE6A64)

// Sidebar bg — oklch(0.185 0.012 250) — slightly darker than bg0, not in main token set
private val _dark_bgSidebar  = Color(0xFF0F1318)

// ---- Light (paper) palette ------------------------------------------------

private val _light_bg0       = Color(0xFFFCFAF6)
private val _light_bg1       = Color(0xFFF4F1ED)
private val _light_bg2       = Color(0xFFEEEBE5)
private val _light_bg3       = Color(0xFFE4E1DA)
private val _light_border    = Color(0xFFD7D4CD)
private val _light_divider   = Color(0xFFE7E4DF)

private val _light_text      = Color(0xFF13181D)
private val _light_text2     = Color(0xFF484E54)
private val _light_text3     = Color(0xFF757B81)
private val _light_textInv   = Color(0xFFF5F3F0)

// Accent and trust base colours are the same in both modes; only alpha-derived
// fills differ — handled via .copy(alpha=…) at usage sites.
private val _accent          = Color(0xFFF39762)
private val _accentHi        = Color(0xFFFFAE7F)
private val _accentLo        = Color(0xFFCA723C)
private val _trust           = Color(0xFF7ECBB6)
private val _trustFg         = Color(0xFF0A2A23)
private val _ok              = Color(0xFF77C87A)
private val _warn            = Color(0xFFE9B452)
private val _err             = Color(0xFFEE6A64)

// ---------------------------------------------------------------------------
// KdColorScheme — typed token bag exposed via CompositionLocal
// ---------------------------------------------------------------------------

data class KdColorScheme(
    val bg0: Color,
    val bg1: Color,
    val bg2: Color,
    val bg3: Color,
    val border: Color,
    val divider: Color,

    val text: Color,
    val text2: Color,
    val text3: Color,
    val textInv: Color,

    val accent: Color,
    val accentHi: Color,
    val accentLo: Color,
    /** Outbound bubble fill / highlight tint — pre-alpha, use .copy(alpha=…) variant below */
    val accentBg: Color,

    val trust: Color,
    val trustBg: Color,
    val trustFg: Color,

    val ok: Color,
    val warn: Color,
    val err: Color,

    /** Sidebar background — not in the main --kd-* token set; unique to C16 */
    val bgSidebar: Color,
)

val KdDarkColors = KdColorScheme(
    bg0        = _dark_bg0,
    bg1        = _dark_bg1,
    bg2        = _dark_bg2,
    bg3        = _dark_bg3,
    border     = _dark_border,
    divider    = _dark_divider,

    text       = _dark_text,
    text2      = _dark_text2,
    text3      = _dark_text3,
    textInv    = _dark_textInv,

    accent     = _accent,
    accentHi   = _accentHi,
    accentLo   = _accentLo,
    accentBg   = _accent.copy(alpha = 0.16f),

    trust      = _trust,
    trustBg    = _trust.copy(alpha = 0.18f),
    trustFg    = _dark_trustFg,

    ok         = _ok,
    warn       = _warn,
    err        = _err,

    bgSidebar  = _dark_bgSidebar,
)

val KdLightColors = KdColorScheme(
    bg0        = _light_bg0,
    bg1        = _light_bg1,
    bg2        = _light_bg2,
    bg3        = _light_bg3,
    border     = _light_border,
    divider    = _light_divider,

    text       = _light_text,
    text2      = _light_text2,
    text3      = _light_text3,
    textInv    = _light_textInv,

    accent     = _accent,
    accentHi   = _accentHi,
    accentLo   = _accentLo,
    accentBg   = _accent.copy(alpha = 0.14f),   // .kd-light uses 0.14 per spec

    trust      = _trust,
    trustBg    = _trust.copy(alpha = 0.18f),    // .kd-light keeps 0.18
    trustFg    = _trustFg,

    ok         = _ok,
    warn       = _warn,
    err        = _err,

    bgSidebar  = _light_bg1,                    // light mode: sidebar = bg1
)
