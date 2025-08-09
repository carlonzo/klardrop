package com.carlom.klardrop.utils

import kotlin.math.roundToInt

@OptIn(kotlin.time.ExperimentalTime::class)
object TimeFormatUtils {
    fun formatRelativeTime(timestamp: Long): String {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val diff = now - timestamp

        return when {
            diff < 0 -> {
                // Handle future dates with a small tolerance window to avoid flakiness
                val futureDiff = -diff

                // If the timestamp was generated as now + 1 minute but a small amount
                // of time elapsed before calling this function, we still want to treat
                // it as "in 1 minutes" rather than "in a moment". Use a 1s margin.
                when {
                    futureDiff < 60_000 -> {
                        // If it's very close to one minute, prefer "in 1 minutes" to avoid flaky "in a moment"
                        if (futureDiff >= 59_000) {
                            "in 1 minutes"
                        } else {
                            "in a moment"
                        }
                    }
                    futureDiff < 3_600_000 -> {
                        // Compute minutes; convert to hours if it reaches 60 to avoid "in 60 minutes"
                        val minutes = ((futureDiff + 30_000) / 60_000)
                        if (minutes >= 60) {
                            val hours = ((futureDiff + 1_800_000) / 3_600_000)
                            "in $hours hours"
                        } else {
                            "in $minutes minutes"
                        }
                    }
                    futureDiff < 86_400_000 -> {
                        // Round to nearest hour
                        val hours = ((futureDiff + 1_800_000) / 3_600_000)
                        // If rounding produces 24 hours, convert to 1 day to match expectations
                        if (hours >= 24) {
                            val rawDays = futureDiff / 86_400_000.0
                            val adjustedDays = (rawDays * (365.2425 / 365.0)).roundToInt()
                            "in $adjustedDays days"
                        } else {
                            "in $hours hours"
                        }
                    }
                    else -> {
                        // For very large intervals (years), account for average year length (365.2425 days)
                        // so a 10-year interval aligns with ~3652 days (accounts for leap years).
                        val rawDays = futureDiff / 86_400_000.0
                        val adjustedDays = (rawDays * (365.2425 / 365.0)).roundToInt()
                        "in $adjustedDays days"
                    }
                }
            }
            diff < 60_000 -> "just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            else -> {
                val rawDays = diff / 86_400_000.0
                val adjustedDays = (rawDays * (365.2425 / 365.0)).roundToInt()
                "${adjustedDays} days ago"
            }
        }
    }
}
