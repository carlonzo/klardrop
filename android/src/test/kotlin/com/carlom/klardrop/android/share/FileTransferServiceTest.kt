package com.carlom.klardrop.android.share

import android.content.Context
import android.content.Intent
import com.carlom.klardrop.common.communication.TransferAnchor.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class FileTransferServiceTest {

  @Before
  fun setUp() {
    FileTransferService.clearQrSessionHeld()
  }

  @Test
  fun `isIdle includes not qrSessionHeld`() {
    // When nothing is active and hold is not held, isIdle is true
    assertTrue(
      FileTransferService.isIdle(
        activeTransfersEmpty = true,
        activeBatches = 0,
        qrSessionHeld = false,
      )
    )

    // When qrSessionHeld is true, isIdle must be false regardless of active transfers/batches
    assertFalse(
      FileTransferService.isIdle(
        activeTransfersEmpty = true,
        activeBatches = 0,
        qrSessionHeld = true,
      )
    )

    // Active transfers block idle
    assertFalse(
      FileTransferService.isIdle(
        activeTransfersEmpty = false,
        activeBatches = 0,
        qrSessionHeld = false,
      )
    )

    // Active batches block idle
    assertFalse(
      FileTransferService.isIdle(
        activeTransfersEmpty = true,
        activeBatches = 1,
        qrSessionHeld = false,
      )
    )

    // All active blocks idle
    assertFalse(
      FileTransferService.isIdle(
        activeTransfersEmpty = false,
        activeBatches = 1,
        qrSessionHeld = true,
      )
    )
  }

  @Test
  fun `startQrSession with empty URI list still sets qrSessionHeld`() {
    assertFalse(FileTransferService.qrSessionHeld)
    assertNull(FileTransferService.pendingGeneration)

    val context = createFakeContext()
    var launchedIntent: Intent? = null

    FileTransferService.startQrSession(context, emptyList()) { _, intent ->
      launchedIntent = intent
    }

    assertTrue(FileTransferService.qrSessionHeld)
    assertNotNull(FileTransferService.pendingGeneration)
    assertEquals(FileTransferService.qrHoldGeneration, FileTransferService.pendingGeneration)
    assertNotNull(launchedIntent)

    // Second call preserves 0-or-1 hold
    val firstGen = FileTransferService.qrHoldGeneration
    FileTransferService.startQrSession(context, emptyList()) { _, intent ->
      launchedIntent = intent
    }
    assertTrue(FileTransferService.qrSessionHeld)
    assertEquals(firstGen + 1, FileTransferService.qrHoldGeneration)
    assertEquals(FileTransferService.qrHoldGeneration, FileTransferService.pendingGeneration)

    // Clearing hold releases hold and pendingGeneration
    FileTransferService.clearQrSessionHeld()
    assertFalse(FileTransferService.qrSessionHeld)
    assertNull(FileTransferService.pendingGeneration)
  }

  @Test
  fun `deriveNotificationTitle for QR wait only`() {
    val entries = mapOf(
      "qr:test-session:wait" to ActiveTransfers.Entry("Waiting for someone to scan", null, Direction.OUTGOING),
    )
    val title = FileTransferService.deriveNotificationTitle(entries, qrHeld = true)
    assertEquals("Waiting for someone to scan", title)
  }

  @Test
  fun `deriveNotificationTitle for QR grace only`() {
    val entries = mapOf(
      "qr:test-session:grace" to ActiveTransfers.Entry("Waiting for download", null, Direction.OUTGOING),
    )
    val title = FileTransferService.deriveNotificationTitle(entries, qrHeld = true)
    assertEquals("Waiting for download", title)
  }

  @Test
  fun `deriveNotificationTitle for QR download single file`() {
    val entries = mapOf(
      "qr:test-session:file:0:conn:abc" to ActiveTransfers.Entry("my_photo.jpg", 50, Direction.OUTGOING),
    )
    val title = FileTransferService.deriveNotificationTitle(entries, qrHeld = true)
    assertEquals("Sending my_photo.jpg", title)
  }

  @Test
  fun `deriveNotificationTitle for QR download multiple files`() {
    val entries = mapOf(
      "qr:test-session:file:0:conn:abc" to ActiveTransfers.Entry("file1.txt", 10, Direction.OUTGOING),
      "qr:test-session:file:1:conn:def" to ActiveTransfers.Entry("file2.txt", 90, Direction.OUTGOING),
    )
    val title = FileTransferService.deriveNotificationTitle(entries, qrHeld = true)
    assertEquals("Sending 2 files", title)
  }

  @Test
  fun `deriveNotificationTitle for QR download with wait still active`() {
    val entries = mapOf(
      "qr:test-session:wait" to ActiveTransfers.Entry("Waiting for someone to scan", null, Direction.OUTGOING),
      "qr:test-session:file:0:conn:abc" to ActiveTransfers.Entry("clip.mp4", 33, Direction.OUTGOING),
    )
    val title = FileTransferService.deriveNotificationTitle(entries, qrHeld = true)
    assertEquals("Sending clip.mp4", title)
  }

  @Test
  fun `deriveNotificationTitle never says Preparing transfer for QR`() {
    // Empty entries with qrHeld true must display "Waiting for someone to scan"
    val title = FileTransferService.deriveNotificationTitle(emptyMap(), qrHeld = true)
    assertEquals("Waiting for someone to scan", title)
  }

  @Test
  fun `deriveNotificationTitle for Klardrop only`() {
    val outgoing1 = mapOf(
      "kd-transfer-1" to ActiveTransfers.Entry("notes.txt", 10, Direction.OUTGOING),
    )
    assertEquals("Sending notes.txt", FileTransferService.deriveNotificationTitle(outgoing1, qrHeld = false))

    val incoming1 = mapOf(
      "kd-transfer-2" to ActiveTransfers.Entry("picture.png", 20, Direction.INCOMING),
    )
    assertEquals("Receiving picture.png", FileTransferService.deriveNotificationTitle(incoming1, qrHeld = false))

    val multipleOutgoing = mapOf(
      "kd-1" to ActiveTransfers.Entry("a.txt", 10, Direction.OUTGOING),
      "kd-2" to ActiveTransfers.Entry("b.txt", 20, Direction.OUTGOING),
    )
    assertEquals("Sending 2 files", FileTransferService.deriveNotificationTitle(multipleOutgoing, qrHeld = false))

    val multipleIncoming = mapOf(
      "kd-1" to ActiveTransfers.Entry("a.txt", 10, Direction.INCOMING),
      "kd-2" to ActiveTransfers.Entry("b.txt", 20, Direction.INCOMING),
    )
    assertEquals("Receiving 2 files", FileTransferService.deriveNotificationTitle(multipleIncoming, qrHeld = false))

    val mixedDirections = mapOf(
      "kd-1" to ActiveTransfers.Entry("a.txt", 10, Direction.OUTGOING),
      "kd-2" to ActiveTransfers.Entry("b.txt", 20, Direction.INCOMING),
    )
    assertEquals("Transferring 2 files", FileTransferService.deriveNotificationTitle(mixedDirections, qrHeld = false))

    // Klardrop empty entries shows Preparing transfer…
    assertEquals("Preparing transfer…", FileTransferService.deriveNotificationTitle(emptyMap(), qrHeld = false))
  }

  @Test
  fun `deriveNotificationTitle for mixed QR and Klardrop`() {
    val entries = mapOf(
      "qr:session-1:file:0:conn:1" to ActiveTransfers.Entry("qr_file.zip", 40, Direction.OUTGOING),
      "kd-transfer-1" to ActiveTransfers.Entry("klardrop_file.pdf", 80, Direction.OUTGOING),
    )
    val title = FileTransferService.deriveNotificationTitle(entries, qrHeld = true)
    assertEquals("Transferring 2 files", title)

    val entriesWaitAndKd = mapOf(
      "qr:session-1:wait" to ActiveTransfers.Entry("Waiting for someone to scan", null, Direction.OUTGOING),
      "kd-transfer-1" to ActiveTransfers.Entry("klardrop_file.pdf", 80, Direction.OUTGOING),
    )
    val titleWaitAndKd = FileTransferService.deriveNotificationTitle(entriesWaitAndKd, qrHeld = true)
    assertEquals("Transferring 2 files", titleWaitAndKd)
  }

  private fun createFakeContext(): Context {
    return object : android.content.ContextWrapper(null) {
      override fun getPackageName(): String = "com.carlom.klardrop"
    }
  }
}
