package com.carlom.klardrop.utils

@OptIn(kotlin.time.ExperimentalTime::class)
object TimeFormatUtils {
    fun formatRelativeTime(timestamp: Long): String {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val diff = now - timestamp
        
        return when {
            diff < 0 -> {
                // Handle future dates
                val futureDiff = -diff
                when {
                    futureDiff < 60_000 -> "in a moment"
                    futureDiff < 3600_000 -> "in ${futureDiff / 60_000} minutes"
                    futureDiff < 86400_000 -> "in ${futureDiff / 3600_000} hours"
                    else -> "in ${futureDiff / 86400_000} days"
                }
            }
            diff < 60_000 -> "just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            else -> "${diff / 86400_000} days ago"
        }
    }
}