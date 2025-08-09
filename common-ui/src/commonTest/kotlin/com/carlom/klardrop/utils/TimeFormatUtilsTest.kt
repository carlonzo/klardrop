package com.carlom.klardrop.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlin.time.ExperimentalTime::class)
class TimeFormatUtilsTest {

    @Test
    fun testFormatRelativeTime_JustNow() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        
        // Test timestamps within the last minute
        val recentTimes = listOf(
            now,
            now - 30.seconds.inWholeMilliseconds,
            now - 59.seconds.inWholeMilliseconds
        )
        
        for (timestamp in recentTimes) {
            val result = TimeFormatUtils.formatRelativeTime(timestamp)
            assertEquals("just now", result)
        }
    }

    @Test
    fun testFormatRelativeTime_Minutes() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        
        // Test various minute intervals
        val testCases = mapOf(
            now - 1.minutes.inWholeMilliseconds to "1 minutes ago",
            now - 2.minutes.inWholeMilliseconds to "2 minutes ago",
            now - 15.minutes.inWholeMilliseconds to "15 minutes ago",
            now - 30.minutes.inWholeMilliseconds to "30 minutes ago",
            now - 59.minutes.inWholeMilliseconds to "59 minutes ago"
        )
        
        for ((timestamp, expected) in testCases) {
            val result = TimeFormatUtils.formatRelativeTime(timestamp)
            assertEquals(expected, result)
        }
    }

    @Test
    fun testFormatRelativeTime_Hours() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        
        // Test various hour intervals
        val testCases = mapOf(
            now - 1.hours.inWholeMilliseconds to "1 hours ago",
            now - 2.hours.inWholeMilliseconds to "2 hours ago",
            now - 12.hours.inWholeMilliseconds to "12 hours ago",
            now - 23.hours.inWholeMilliseconds to "23 hours ago"
        )
        
        for ((timestamp, expected) in testCases) {
            val result = TimeFormatUtils.formatRelativeTime(timestamp)
            assertEquals(expected, result)
        }
    }

    @Test
    fun testFormatRelativeTime_Days() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        
        // Test various day intervals
        val testCases = mapOf(
            now - 1.days.inWholeMilliseconds to "1 days ago",
            now - 2.days.inWholeMilliseconds to "2 days ago",
            now - 7.days.inWholeMilliseconds to "7 days ago",
            now - 30.days.inWholeMilliseconds to "30 days ago",
            now - 365.days.inWholeMilliseconds to "365 days ago"
        )
        
        for ((timestamp, expected) in testCases) {
            val result = TimeFormatUtils.formatRelativeTime(timestamp)
            assertEquals(expected, result)
        }
    }

    @Test
    fun testFormatRelativeTime_FutureDates() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        
        // Test future timestamps
        val testCases = mapOf(
            now + 30.seconds.inWholeMilliseconds to "in a moment",
            now + 1.minutes.inWholeMilliseconds to "in 1 minutes",
            now + 30.minutes.inWholeMilliseconds to "in 30 minutes",
            now + 1.hours.inWholeMilliseconds to "in 1 hours",
            now + 12.hours.inWholeMilliseconds to "in 12 hours",
            now + 1.days.inWholeMilliseconds to "in 1 days",
            now + 7.days.inWholeMilliseconds to "in 7 days"
        )
        
        for ((timestamp, expected) in testCases) {
            val result = TimeFormatUtils.formatRelativeTime(timestamp)
            assertEquals(expected, result)
        }
    }

    @Test
    fun testFormatRelativeTime_EdgeCases() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        
        // Test exactly 1 minute boundary
        val oneMinuteAgo = now - 60.seconds.inWholeMilliseconds
        assertEquals("1 minutes ago", TimeFormatUtils.formatRelativeTime(oneMinuteAgo))
        
        // Test exactly 1 hour boundary  
        val oneHourAgo = now - 1.hours.inWholeMilliseconds
        assertEquals("1 hours ago", TimeFormatUtils.formatRelativeTime(oneHourAgo))
        
        // Test exactly 1 day boundary
        val oneDayAgo = now - 1.days.inWholeMilliseconds
        assertEquals("1 days ago", TimeFormatUtils.formatRelativeTime(oneDayAgo))
    }

    @Test
    fun testFormatRelativeTime_ZeroDifference() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val result = TimeFormatUtils.formatRelativeTime(now)
        assertEquals("just now", result)
    }

    @Test
    fun testFormatRelativeTime_VeryLargeNegativeDifference() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val veryOldTimestamp = now - (10 * 365 * 24 * 60 * 60 * 1000L) // 10 years ago
        
        val result = TimeFormatUtils.formatRelativeTime(veryOldTimestamp)
        assertTrue(result.contains("days ago"))
        assertTrue(result.startsWith("3652")) // Approximately 3652 days in 10 years
    }

    @Test
    fun testFormatRelativeTime_VeryLargeFutureDifference() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val futureTimestamp = now + (10 * 365 * 24 * 60 * 60 * 1000L) // 10 years in future
        
        val result = TimeFormatUtils.formatRelativeTime(futureTimestamp)
        assertTrue(result.contains("in"))
        assertTrue(result.contains("days"))
        assertTrue(result.startsWith("in 3652")) // Approximately 3652 days in 10 years
    }

    @Test
    fun testFormatRelativeTime_Consistency() {
        val baseTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
        
        // Test that the same timestamp always produces the same result
        val timestamp = baseTime - 5.minutes.inWholeMilliseconds
        val result1 = TimeFormatUtils.formatRelativeTime(timestamp)
        val result2 = TimeFormatUtils.formatRelativeTime(timestamp)
        
        assertEquals(result1, result2)
    }

    @Test
    fun testFormatRelativeTime_BoundaryTransitions() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        
        // Test the transition points between different time units
        
        // Just before 1 minute
        val almostOneMinute = now - (59.seconds.inWholeMilliseconds + 999)
        assertEquals("just now", TimeFormatUtils.formatRelativeTime(almostOneMinute))
        
        // Just at 1 minute
        val exactlyOneMinute = now - 60.seconds.inWholeMilliseconds
        assertEquals("1 minutes ago", TimeFormatUtils.formatRelativeTime(exactlyOneMinute))
        
        // Just before 1 hour
        val almostOneHour = now - (59.minutes.inWholeMilliseconds + 59.seconds.inWholeMilliseconds + 999)
        assertTrue(TimeFormatUtils.formatRelativeTime(almostOneHour).contains("minutes ago"))
        
        // Just at 1 hour
        val exactlyOneHour = now - 1.hours.inWholeMilliseconds
        assertEquals("1 hours ago", TimeFormatUtils.formatRelativeTime(exactlyOneHour))
        
        // Just before 1 day
        val almostOneDay = now - (23.hours.inWholeMilliseconds + 59.minutes.inWholeMilliseconds + 59.seconds.inWholeMilliseconds + 999)
        assertTrue(TimeFormatUtils.formatRelativeTime(almostOneDay).contains("hours ago"))
        
        // Just at 1 day
        val exactlyOneDay = now - 1.days.inWholeMilliseconds
        assertEquals("1 days ago", TimeFormatUtils.formatRelativeTime(exactlyOneDay))
    }

    @Test
    fun testFormatRelativeTime_LongTimestamp() {
        // Test with very large timestamp values
        val largeTimestamp = Long.MAX_VALUE / 2
        val result = TimeFormatUtils.formatRelativeTime(largeTimestamp)
        
        // Should not throw exception and should return a valid string
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun testFormatRelativeTime_SmallTimestamp() {
        // Test with very small timestamp values
        val smallTimestamp = 1000L // 1 second after epoch
        val result = TimeFormatUtils.formatRelativeTime(smallTimestamp)
        
        // Should not throw exception and should return a valid string
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
        assertTrue(result.contains("days ago")) // Should be many days ago from now
    }

    @Test
    fun testFormatRelativeTime_NegativeTimestamp() {
        // Test with negative timestamp (before Unix epoch)
        val negativeTimestamp = -1000L
        val result = TimeFormatUtils.formatRelativeTime(negativeTimestamp)
        
        // Should not throw exception and should return a valid string
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }


}