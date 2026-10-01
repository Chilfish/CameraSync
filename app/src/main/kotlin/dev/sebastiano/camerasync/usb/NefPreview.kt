package dev.sebastiano.camerasync.usb

import java.io.File

/**
 * Extracts the embedded JPEG preview from a Nikon NEF (TIFF-based RAW) file.
 *
 * NEF files embed one or more JPEG previews — the largest is usually the full-size preview. We
 * locate the largest JPEG stream by its SOI (`FF D8 FF`) / EOI (`FF D9`) markers instead of walking
 * the TIFF IFD tree: it is simpler and robust to where Nikon stores the preview (IFD0, SubIFD or
 * MakerNotes).
 */
internal object NefPreview {

    /** Nikon RAW extensions we attempt to extract a preview from. */
    private val RAW_EXTENSIONS = setOf("nef", "nrw")

    /** Previews live near the start; refuse absurdly large files rather than scanning forever. */
    private const val MAX_SCAN_BYTES = 96L * 1024 * 1024

    /** Decoded previews are reused across Coil's placeholder (60px) and grid (360px) requests. */
    private const val CACHE_MAX = 8

    private val cache = LinkedHashMap<String, ByteArray>(CACHE_MAX, 0.75f, true)

    /** True when [file] has a Nikon RAW extension. */
    fun isRawFile(file: File): Boolean = file.extension.lowercase() in RAW_EXTENSIONS

    /** Returns the largest embedded JPEG preview of [file], or null if the file has none. */
    fun extractFromFile(file: File): ByteArray? {
        if (!file.isFile || file.length() > MAX_SCAN_BYTES) return null
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        synchronized(cache) { cache[key] }
            ?.let {
                return it
            }
        val jpeg = extractLargestJpeg(file.readBytes()) ?: return null
        synchronized(cache) {
            cache[key] = jpeg
            while (cache.size > CACHE_MAX) cache.remove(cache.keys.first())
        }
        return jpeg
    }

    /** Returns the largest JPEG stream (SOI..EOI) contained in [data], or null if there is none. */
    fun extractLargestJpeg(data: ByteArray): ByteArray? {
        var bestStart = -1
        var bestEnd = -1
        var i = 0
        val n = data.size
        while (i + 2 < n) {
            if (
                data[i] == 0xFF.toByte() &&
                    data[i + 1] == 0xD8.toByte() &&
                    data[i + 2] == 0xFF.toByte()
            ) {
                val end = findEoi(data, i + 2)
                if (end > 0 && (bestStart < 0 || end - i > bestEnd - bestStart)) {
                    bestStart = i
                    bestEnd = end
                }
                i = if (end > 0) end + 1 else i + 3
            } else {
                i++
            }
        }
        return if (bestStart < 0) null else data.copyOfRange(bestStart, bestEnd + 1)
    }

    /** Index of the `FF D9` EOI marker at or after [from], or -1 when absent. */
    private fun findEoi(data: ByteArray, from: Int): Int {
        var j = from
        while (j + 1 < data.size) {
            if (data[j] == 0xFF.toByte() && data[j + 1] == 0xD9.toByte()) return j + 1
            j++
        }
        return -1
    }
}
