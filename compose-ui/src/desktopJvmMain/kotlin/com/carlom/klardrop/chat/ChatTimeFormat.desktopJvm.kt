package com.carlom.klardrop.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val timeFormatter: DateTimeFormatter =
  DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

private val dateFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

private fun localDate(epochMillis: Long): LocalDate =
  Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

actual fun formatChatTime(epochMillis: Long): String =
  Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormatter)

actual fun formatChatDay(epochMillis: Long): String {
  val today = LocalDate.now(ZoneId.systemDefault())
  val day = localDate(epochMillis)
  return when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(dateFormatter)
  }
}

actual fun chatDayKey(epochMillis: Long): Long = localDate(epochMillis).toEpochDay()
