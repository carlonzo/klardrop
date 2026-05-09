package com.carlom.klardrop.theme

import androidx.compose.animation.core.CubicBezierEasing

// ---------------------------------------------------------------------------
// KdMotion — animation timing from spec/tokens.css
// ---------------------------------------------------------------------------

data class KdMotion(
    /** 140 ms — tap feedback · status-dot pulse · button hover */
    val dFast: Int = 140,
    /** 220 ms — sheet open · row insert/remove · transfer progress tick */
    val dBase: Int = 220,
    /** 360 ms — screen change · pairing accept · device promotion */
    val dSlow: Int = 360,
)

val KdDefaultMotion = KdMotion()

/** cubic-bezier(0.2, 0.7, 0.2, 1) — motion in/out */
val KdEase = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

/** cubic-bezier(0.16, 1, 0.3, 1) — entrances (sheets, banners) */
val KdEaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
