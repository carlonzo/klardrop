package com.carlom.klardrop.chat

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val timeFormatter: DateFormat =
  DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())

private val dateFormatter: DateFormat =
  SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

private fun startOfDayMillis(epochMillis: Long): Long {
  val cal = Calendar.getInstance()
  cal.timeInMillis = epochMillis
  cal.set(Calendar.HOUR_OF_DAY, 0)
  cal.set(Calendar.MINUTE, 0)
  cal.set(Calendar.SECOND, 0)
  cal.set(Calendar.MILLISECOND, 0)
  return cal.timeInMillis
}

actual fun formatChatTime(epochMillis: Long): String =
  timeFormatter.format(Date(epochMillis))

actual fun formatChatDay(epochMillis: Long): String {
  val today = startOfDayMillis(System.currentTimeMillis())
  val day = startOfDayMillis(epochMillis)
  val msPerDay = 86_400_000L
  return when ((today - day) / msPerDay) {
    0L -> "Today"
    1L -> "Yesterday"
    else -> dateFormatter.format(Date(epochMillis))
  }
}

actual fun chatDayKey(epochMillis: Long): Long =
  startOfDayMillis(epochMillis) / 86_400_000L
