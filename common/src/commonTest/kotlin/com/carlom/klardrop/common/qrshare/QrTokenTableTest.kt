package com.carlom.klardrop.common.qrshare

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private class TestClock(
  var current: Instant = Instant.fromEpochMilliseconds(1000L)
) : Clock {
  override fun now(): Instant = current
  fun advance(duration: Duration) {
    current += duration
  }
}

class QrTokenTableTest {

  @Test
  fun testWaitingT1ClaimedByFirstIpAllowsAndSecondIpDenied() = runTest {
    val clock = TestClock()
    val table = QrTokenTable(clock = clock)
    table.setWaiting("T1")

    // Peer 10.0.0.1 claims waiting token T1
    val r1 = table.resolveAccess("/s/T1", "10.0.0.1")
    assertIs<TokenAccessResult.Allowed>(r1)
    assertTrue(r1.isNewClaim)
    assertEquals("T1", r1.token)
    assertNull(r1.fileIndex)

    // Peer 10.0.0.2 on T1 must be denied
    val r2 = table.resolveAccess("/s/T1", "10.0.0.2")
    assertEquals(TokenAccessResult.Denied, r2)
  }

  @Test
  fun testReloadByBoundIpAllowed() = runTest {
    val clock = TestClock()
    val table = QrTokenTable(clock = clock)
    table.setWaiting("T1")

    val r1 = table.resolveAccess("/s/T1", "10.0.0.1")
    assertIs<TokenAccessResult.Allowed>(r1)
    assertTrue(r1.isNewClaim)

    // 10.0.0.1 reload T1 allow
    val rReload = table.resolveAccess("/s/T1", "10.0.0.1")
    assertIs<TokenAccessResult.Allowed>(rReload)
    assertFalse(rReload.isNewClaim)
    assertEquals("T1", rReload.token)
  }

  @Test
  fun testAfterClaimWaitingIsNoLongerT1() = runTest {
    val clock = TestClock()
    val table = QrTokenTable(clock = clock)
    table.setWaiting("T1")
    assertEquals("T1", table.waitingToken)

    table.resolveAccess("/s/T1", "10.0.0.1")

    assertNotEquals("T1", table.waitingToken)
    assertNull(table.waitingToken)
  }

  @Test
  fun testEightClaimedLiveCappedOnNinthClaim() = runTest {
    val clock = TestClock()
    val table = QrTokenTable(clock = clock, maxClaimed = 8)

    for (i in 1..8) {
      table.setWaiting("T$i")
      val res = table.resolveAccess("/s/T$i", "10.0.0.$i")
      assertIs<TokenAccessResult.Allowed>(res)
      assertTrue(res.isNewClaim)
    }
    assertEquals(8, table.getClaimedCount())

    // 9th waiting claim is capped (return 503 signal)
    table.setWaiting("T9")
    val r9 = table.resolveAccess("/s/T9", "10.0.0.9")
    assertEquals(TokenAccessResult.Capped, r9)
    // Capped claim must NOT rotate waiting token
    assertEquals("T9", table.waitingToken)
  }

  @Test
  fun testAfterGraceSlotEvictedNewClaimWorks() = runTest {
    val clock = TestClock()
    val table = QrTokenTable(clock = clock, postArrivalGrace = 2.minutes, maxClaimed = 8)

    for (i in 1..8) {
      table.setWaiting("T$i")
      table.resolveAccess("/s/T$i", "10.0.0.$i")
    }
    assertEquals(8, table.getClaimedCount())

    table.setWaiting("T9")
    assertEquals(TokenAccessResult.Capped, table.resolveAccess("/s/T9", "10.0.0.9"))

    // Advance clock past grace period (2 minutes)
    clock.advance(2.minutes + 1.seconds)

    // After grace, slots are evicted, so new claim works
    val r9AfterGrace = table.resolveAccess("/s/T9", "10.0.0.9")
    assertIs<TokenAccessResult.Allowed>(r9AfterGrace)
    assertTrue(r9AfterGrace.isNewClaim)
    assertEquals("T9", r9AfterGrace.token)
  }

  @Test
  fun testUnknownTokenDenied() = runTest {
    val clock = TestClock()
    val table = QrTokenTable(clock = clock)
    table.setWaiting("T1")

    val res = table.resolveAccess("/s/unknown", "10.0.0.1")
    assertEquals(TokenAccessResult.Denied, res)
  }

  @Test
  fun testFilePathVsLandingPathBothClaimWaiting() = runTest {
    val clock = TestClock()
    val table = QrTokenTable(clock = clock)

    // Landing path claims waiting
    table.setWaiting("T_landing")
    val resLanding = table.resolveAccess("/s/T_landing", "10.0.0.1")
    assertIs<TokenAccessResult.Allowed>(resLanding)
    assertTrue(resLanding.isNewClaim)
    assertEquals("T_landing", resLanding.token)
    assertNull(resLanding.fileIndex)

    // File path claims waiting
    table.setWaiting("T_file")
    val resFile = table.resolveAccess("/s/T_file/file/0", "10.0.0.2")
    assertIs<TokenAccessResult.Allowed>(resFile)
    assertTrue(resFile.isNewClaim)
    assertEquals("T_file", resFile.token)
    assertEquals(0, resFile.fileIndex)
  }

  @Test
  fun testInvalidPathsDoNotClaimWaiting() = runTest {
    val clock = TestClock()
    val table = QrTokenTable(clock = clock)
    table.setWaiting("T1")

    assertEquals(TokenAccessResult.Denied, table.resolveAccess("/favicon.ico", "10.0.0.1"))
    assertEquals(TokenAccessResult.Denied, table.resolveAccess("/s", "10.0.0.1"))
    assertEquals(TokenAccessResult.Denied, table.resolveAccess("/s/", "10.0.0.1"))
    assertEquals(TokenAccessResult.Denied, table.resolveAccess("/s/T1/other", "10.0.0.1"))
    assertEquals(TokenAccessResult.Denied, table.resolveAccess("/s/T1/file/notanumber", "10.0.0.1"))
    assertEquals(TokenAccessResult.Denied, table.resolveAccess("/s/T1/file/-1", "10.0.0.1"))

    // Waiting token must NOT be claimed
    assertEquals("T1", table.waitingToken)
  }

  @Test
  fun testDownloadsInFlightPreventsEvictionDuringGrace() = runTest {
    val clock = TestClock()
    val table = QrTokenTable(clock = clock, postArrivalGrace = 2.minutes)
    table.setWaiting("T1")
    table.resolveAccess("/s/T1", "10.0.0.1")

    // Start a file download
    assertTrue(table.startDownload("T1"))

    // Advance clock past grace period
    clock.advance(5.minutes)
    table.evictExpired()

    // Must NOT be evicted while download is in flight
    assertEquals(1, table.getClaimedCount())

    // End download
    table.endDownload("T1")

    // Right after download ends, grace clock restarts from end time
    clock.advance(1.minutes)
    table.evictExpired()
    assertEquals(1, table.getClaimedCount()) // still within grace

    // After another 1 minute + 1 second, grace expires
    clock.advance(1.minutes + 1.seconds)
    table.evictExpired()
    assertEquals(0, table.getClaimedCount()) // evicted now
  }

  @Test
  fun testMaxConcurrentDownloadsCap() = runTest {
    val clock = TestClock()
    val table = QrTokenTable(clock = clock, maxConcurrentDownloads = 8)
    table.setWaiting("T1")
    table.resolveAccess("/s/T1", "10.0.0.1")

    for (i in 1..8) {
      assertTrue(table.startDownload("T1"), "Download $i should succeed")
    }

    // 9th download must be capped
    assertFalse(table.startDownload("T1"), "9th download must fail")

    // After one ends, another can start
    table.endDownload("T1")
    assertTrue(table.startDownload("T1"), "Should succeed after freeing a slot")
  }

  @Test
  fun testContentDispositionAndFilenameSanitization() {
    val header1 = contentDispositionHeader("test \"file\"\\name\r\n.txt")
    assertTrue(header1.startsWith("attachment; filename=\"test filename.txt\"; filename*=UTF-8''"))
    assertFalse(header1.contains("\"file\""))
    assertFalse(header1.contains("\\"))
    assertFalse(header1.contains("\r"))
    assertFalse(header1.contains("\n"))

    val header2 = contentDispositionHeader("café.pdf")
    assertTrue(header2.contains("filename*=UTF-8''caf%C3%A9.pdf"))

    val headerEmpty = contentDispositionHeader("")
    assertTrue(headerEmpty.contains("filename=\"file\""))
  }

  @Test
  fun testHtmlEscape() {
    val escaped = htmlEscape("<script>alert('xss')</script> & \"quotes\"")
    assertEquals("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt; &amp; &quot;quotes&quot;", escaped)
  }

  @Test
  fun testExtractPath() {
    assertEquals("/s/abc", extractPath("/s/abc"))
    assertEquals("/s/abc", extractPath("/s/abc?foo=bar"))
    assertEquals("/s/abc", extractPath("https://192.168.1.5:8080/s/abc"))
    assertEquals("/s/abc", extractPath("http://192.168.1.5:8080/s/abc?bar=baz"))
    assertEquals("/", extractPath("https://192.168.1.5:8080"))
  }

  @Test
  fun testRedactTokenAndPath() {
    assertEquals("***", redactToken("123"))
    assertEquals("abc...xyz", redactToken("abcdefxyz"))
    assertEquals("/s/abc...xyz", redactPath("/s/abcdefxyz"))
    assertEquals("/s/abc...xyz/file/0", redactPath("/s/abcdefxyz/file/0"))
  }
}
