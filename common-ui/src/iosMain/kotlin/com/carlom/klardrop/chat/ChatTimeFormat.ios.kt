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
import platform.Foundation.timeIntervalSinceDate

private val timeFormatter = NSDateFormatter().apply {
  timeStyle = NSDateFormatterShortStyle
  dateStyle = NSDateFormatterNoStyle
}

private val dayFormatter = NSDateFormatter().apply {
  dateStyle = NSDateFormatterMediumStyle
  timeStyle = NSDateFormatterNoStyle
}

private val epoch: NSDate = NSDate.dateWithTimeIntervalSince1970(0.0)

private fun nsDate(epochMillis: Long): NSDate =
  NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)

private fun secondsSinceEpoch(date: NSDate): Double =
  date.timeIntervalSinceDate(epoch)

private fun startOfDay(date: NSDate): NSDate {
  val cal = NSCalendar.currentCalendar
  val units = NSCalendarUnitEra or NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay
  val components = cal.components(units, date)
  return cal.dateFromComponents(components) ?: date
}

actual fun formatChatTime(epochMillis: Long): String =
  timeFormatter.stringFromDate(nsDate(epochMillis))

actual fun formatChatDay(epochMillis: Long): String {
  val today = startOfDay(NSDate())
  val day = startOfDay(nsDate(epochMillis))
  val msPerDay = 86_400.0
  val diffDays = ((secondsSinceEpoch(today) - secondsSinceEpoch(day)) / msPerDay).toLong()
  return when (diffDays) {
    0L -> "Today"
    1L -> "Yesterday"
    else -> dayFormatter.stringFromDate(nsDate(epochMillis))
  }
}

actual fun chatDayKey(epochMillis: Long): Long {
  val day = startOfDay(nsDate(epochMillis))
  return (secondsSinceEpoch(day) / 86_400.0).toLong()
}
