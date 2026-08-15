package dev.sebastiano.camerasync.usb

/** One entry in the transfer history (persisted as a delimited string, see UsbSyncPreferences). */
data class TransferRecord(
    val date: String, // "yyyy-MM-dd HH:mm"
    val photoCount: Int,
    val cameraModel: String,
)
