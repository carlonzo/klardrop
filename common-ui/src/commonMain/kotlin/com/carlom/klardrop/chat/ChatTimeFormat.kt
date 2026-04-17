package com.carlom.klardrop.chat

/** "9:41" or "21:07" depending on platform locale. */
expect fun formatChatTime(epochMillis: Long): String

/** "Today", "Yesterday", or e.g. "Apr 17, 2026". */
expect fun formatChatDay(epochMillis: Long): String

/** Stable key representing the local calendar day for the given epoch. */
expect fun chatDayKey(epochMillis: Long): Long
