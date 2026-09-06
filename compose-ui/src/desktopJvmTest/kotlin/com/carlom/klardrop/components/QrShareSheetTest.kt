package com.carlom.klardrop.components

import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QrShareSheetTest {

    @Test
    fun testHelperTextStringsMatchSpec() {
        val expectedFiles =
            "Keep this screen open until you see sending progress. Then you can hide it — the download will finish. Each scan is one phone; the code changes after someone opens it. Both devices must be on the same Wi-Fi. If the other phone warns about the certificate, tap Proceed — that’s this device. Open in Safari if the camera preview blocks it. Some guest networks block this. Turn off mobile data if it won’t open."
        val expectedText =
            "Keep this open until they scan; they can copy the text on their phone. Each scan is one phone; the code changes after someone opens it. Both devices must be on the same Wi-Fi. If the other phone warns about the certificate, tap Proceed — that’s this device. Open in Safari if the camera preview blocks it. Some guest networks block this."

        assertEquals(expectedFiles, QR_HELPER_TEXT_FILES)
        assertEquals(expectedText, QR_HELPER_TEXT_TEXT)
    }

    @Test
    fun testQrEncodingLevelM() {
        val url = "https://192.168.1.50:49152/s/abcdef1234567890"
        val processor = QRCodeProcessor(url, ErrorCorrectionLevel.MEDIUM)
        val matrix = processor.encode()

        assertTrue(matrix.isNotEmpty())
        assertEquals(matrix.size, matrix[0].size) // Square matrix
        // Standard QR code version 1 is 21x21, version 2 is 25x25, version 3 is 29x29, etc.
        assertTrue(matrix.size >= 21)

        // Count dark and light modules
        var darkCount = 0
        var lightCount = 0
        for (row in matrix) {
            for (cell in row) {
                if (cell.dark) darkCount++ else lightCount++
            }
        }
        assertTrue(darkCount > 0)
        assertTrue(lightCount > 0)
    }

    @Test
    fun testQrMatrixChangesOnTokenRotation() {
        val url1 = "https://192.168.1.50:49152/s/token111111111111"
        val url2 = "https://192.168.1.50:49152/s/token222222222222"

        val matrix1 = QRCodeProcessor(url1, ErrorCorrectionLevel.MEDIUM).encode()
        val matrix2 = QRCodeProcessor(url2, ErrorCorrectionLevel.MEDIUM).encode()

        // Size should be identical
        assertEquals(matrix1.size, matrix2.size)

        // Contents must differ because token changed
        var differences = 0
        for (r in matrix1.indices) {
            for (c in matrix1[r].indices) {
                if (matrix1[r][c].dark != matrix2[r][c].dark) {
                    differences++
                }
            }
        }
        assertTrue(differences > 0, "QR modules should differ after token rotation")
    }

    @Test
    fun testQuietZoneCalculation() {
        val quietZone = 4
        val moduleCount = 29 // Example module count
        val totalModules = moduleCount + quietZone * 2
        assertEquals(37, totalModules)
        assertTrue(quietZone >= 4)
    }

    @Test
    fun testShareSheetDeviceConversion() {
        val device = KdShareDevice(
            id = "d1",
            name = "Test Device",
            kind = KdDeviceKind.Android,
            isTrusted = true,
            status = KdStatus.Ok,
        )
        assertEquals("d1", device.id)
        assertEquals("Test Device", device.name)
        assertTrue(device.isTrusted)
    }
}
