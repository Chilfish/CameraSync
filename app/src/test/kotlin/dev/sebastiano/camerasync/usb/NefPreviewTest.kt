package dev.sebastiano.camerasync.usb

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEF preview extraction: locates the largest embedded JPEG stream so Nikon RAW files render
 * instead of showing the grey placeholder. Fixtures are synthetic TIFF/JPEG byte layouts (no real
 * NEF).
 */
class NefPreviewTest {

    private fun jpeg(payload: Int, marker: Byte): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(payload) { marker } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test
    fun isRawFile_matchesNikonExtensions_caseInsensitive() {
        assertTrue(NefPreview.isRawFile(File("DSC_0001.NEF")))
        assertTrue(NefPreview.isRawFile(File("DSC_0001.nef")))
        assertTrue(NefPreview.isRawFile(File("DSC_0001.NRW")))
        assertFalse(NefPreview.isRawFile(File("DSC_0001.JPG")))
        assertFalse(NefPreview.isRawFile(File("DSC_0001")))
    }

    @Test
    fun extractLargestJpeg_picksTheBiggerPreview() {
        val small = jpeg(16, 0x11)
        val big = jpeg(64, 0x22)
        val data =
            "TIFF-HEADER".toByteArray() + small + "xx".toByteArray() + big + "TAIL".toByteArray()

        assertArrayEquals(big, NefPreview.extractLargestJpeg(data))
    }

    @Test
    fun extractLargestJpeg_returnsExactSegment() {
        val only = jpeg(8, 0x33)
        val data = byteArrayOf(1, 2, 3) + only + byteArrayOf(4, 5)

        assertArrayEquals(only, NefPreview.extractLargestJpeg(data))
    }

    @Test
    fun extractLargestJpeg_returnsNullWhenAbsent() {
        assertNull(NefPreview.extractLargestJpeg("no jpeg here".toByteArray()))
        assertNull(NefPreview.extractLargestJpeg(ByteArray(0)))
    }

    @Test
    fun extractLargestJpeg_ignoresUnterminatedJpeg() {
        val truncated = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)
        assertNull(NefPreview.extractLargestJpeg(truncated))
    }

    @Test
    fun extractFromFile_readsTheEmbeddedPreview() {
        val preview = jpeg(32, 0x44)
        val file = File.createTempFile("nef-preview", ".NEF")
        try {
            file.writeBytes("header".toByteArray() + preview)
            assertArrayEquals(preview, NefPreview.extractFromFile(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun extractFromFile_missingFile_returnsNull() {
        assertNull(NefPreview.extractFromFile(File("missing-${System.nanoTime()}.NEF")))
    }
}
