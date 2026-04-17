package com.carlom.klardrop.chat

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitEra
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

private val timeFormatter = NSDateFormatter().apply {
  timeStyle = NSDateFormatterShortStyle
  dateStyle = NSDateFormatterNoStyle
}

private val dayFormatter = NSDateFormatter().apply {
  dateStyle = NSDateFormatterMediumStyle
  timeStyle = NSDateFormatterNoStyle
}

private fun nsDate(epochMillis: Long): NSDate =
  NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)

private fun startOfDay(date: NSDate): NSDate {
  val cal = NSCalendar.currentCalendar
  val units = NSCalendarUnitEra or NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay
  val components = cal.components(units, date)
  return cal.dateFromComponents(components) ?: date
}

actual fun formatChatTime(epochMillis: Long): String =
  timeFormatter.stringFromDate(nsDate(epochMillis))

actual fun formatChatDay(epochMillis: Long): String {
  val cal = NSCalendar.currentCalendar
  val today = startOfDay(NSDate())
  val day = startOfDay(nsDate(epochMillis))
  val msPerDay = 86_400.0
  val diffDays = ((today.timeIntervalSince1970 - day.timeIntervalSince1970) / msPerDay).toLong()
  return when (diffDays) {
    0L -> "Today"
    1L -> "Yesterday"
    else -> dayFormatter.stringFromDate(nsDate(epochMillis))
  }
}

actual fun chatDayKey(epochMillis: Long): Long {
  val day = startOfDay(nsDate(epochMillis))
  return (day.timeIntervalSince1970 / 86_400.0).toLong()
}
