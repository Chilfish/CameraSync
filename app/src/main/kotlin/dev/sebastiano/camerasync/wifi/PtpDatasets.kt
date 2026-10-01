@file:Suppress("MagicNumber")

package dev.sebastiano.camerasync.wifi

/** Parsed PTP `DeviceInfo` dataset (the fields the gallery cares about). */
data class PtpDeviceInfo(
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
)

/** Parsed PTP `StorageInfo` dataset. */
data class PtpStorageInfo(val maxCapacity: Long, val freeSpace: Long, val description: String)

/** Parsed PTP `ObjectInfo` dataset. */
data class PtpObjectInfo(
    val storageId: Int,
    val format: Int,
    val compressedSize: Long,
    val thumbPixWidth: Int,
    val thumbPixHeight: Int,
    val imagePixWidth: Int,
    val imagePixHeight: Int,
    val parentObject: Int,
    val associationType: Int,
    val fileName: String,
    val captureDate: String,
) {
    val isFolder: Boolean
        get() =
            format == PtpIp.FORMAT_ASSOCIATION ||
                associationType == PtpIp.ASSOCIATION_GENERIC_FOLDER
}

/**
 * Parsers for the PTP data-phase datasets (little-endian, ISO 15740).
 *
 * Datasets in the wild are frequently truncated (folders carry no filename, some bodies omit the
 * trailing strings), so the reader returns zeroes past the end instead of throwing.
 */
object PtpDatasets {

    fun parseU32Array(data: ByteArray): List<Int> {
        val reader = PtpReader(data)
        val count = reader.u32()
        val values = mutableListOf<Int>()
        repeat(count.toInt()) { values.add(reader.u32().toInt()) }
        return values
    }

    fun parseDeviceInfo(data: ByteArray): PtpDeviceInfo {
        val reader = PtpReader(data)
        reader.u16() // StandardVersion
        reader.u32() // VendorExtensionID
        reader.u16() // VendorExtensionVersion
        reader.string() // VendorExtensionDesc
        reader.u16() // FunctionalMode
        reader.skipU16Array() // OperationsSupported
        reader.skipU16Array() // EventsSupported
        reader.skipU16Array() // DevicePropertiesSupported
        reader.skipU16Array() // CaptureFormats
        reader.skipU16Array() // ImageFormats
        return PtpDeviceInfo(
            manufacturer = reader.string(),
            model = reader.string(),
            deviceVersion = reader.string(),
            serialNumber = reader.string(),
        )
    }

    fun parseStorageInfo(data: ByteArray): PtpStorageInfo {
        val reader = PtpReader(data)
        reader.u16() // StorageType
        reader.u16() // FilesystemType
        reader.u16() // AccessCapability
        val maxCapacity = reader.u64()
        val freeSpace = reader.u64()
        reader.u32() // FreeSpaceInImages
        return PtpStorageInfo(
            maxCapacity = maxCapacity,
            freeSpace = freeSpace,
            description = reader.string(),
        )
    }

    fun parseObjectInfo(data: ByteArray): PtpObjectInfo {
        val reader = PtpReader(data)
        val storageId = reader.u32().toInt()
        val format = reader.u16()
        reader.u16() // ProtectionStatus
        val compressedSize = reader.u32()
        reader.u16() // ThumbFormat
        reader.u32() // ThumbCompressedSize
        val thumbWidth = reader.u32().toInt()
        val thumbHeight = reader.u32().toInt()
        val imageWidth = reader.u32().toInt()
        val imageHeight = reader.u32().toInt()
        val parent = reader.u32().toInt()
        val associationType = reader.u16()
        reader.u32() // AssociationDesc
        reader.u32() // SequenceNumber
        val fileName = reader.string()
        val captureDate = reader.string()
        return PtpObjectInfo(
            storageId = storageId,
            format = format,
            compressedSize = compressedSize,
            thumbPixWidth = thumbWidth,
            thumbPixHeight = thumbHeight,
            imagePixWidth = imageWidth,
            imagePixHeight = imageHeight,
            parentObject = parent,
            associationType = associationType,
            fileName = fileName,
            captureDate = captureDate,
        )
    }
}

/** Little-endian reader over a PTP dataset; reads past the end return zeroes. */
private class PtpReader(private val data: ByteArray) {
    private var offset = 0

    fun u16(): Int {
        if (offset + 2 > data.size) {
            offset = data.size
            return 0
        }
        val value = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2
        return value
    }

    fun u32(): Long {
        if (offset + 4 > data.size) {
            offset = data.size
            return 0L
        }
        val value =
            (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
        offset += 4
        return value
    }

    fun u64(): Long {
        if (offset + 8 > data.size) {
            offset = data.size
            return 0L
        }
        return u32() or (u32() shl 32)
    }

    /** `String` per PTP: 1-byte char count (including the terminator) followed by UTF-16LE. */
    fun string(): String {
        val length = if (offset + 1 > data.size) 0 else (data[offset++].toInt() and 0xFF)
        if (length == 0) return ""
        val text = StringBuilder()
        var terminated = false
        repeat(length) {
            val value = u16()
            if (value == 0) terminated = true
            if (!terminated) text.append(value.toChar())
        }
        return text.toString()
    }

    /** Skips a count-prefixed `AUINT16` array (UINT32 count then count UINT16 values). */
    fun skipU16Array() {
        val count = u32()
        repeat(count.toInt()) { u16() }
    }
}
