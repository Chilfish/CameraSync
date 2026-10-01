package dev.sebastiano.camerasync.camera

/**
 * Transport-agnostic camera models. These used to be nested in the USB manager; they are the
 * vocabulary the rest of the app speaks, so adding a new transport (e.g. WiFi/PTP-IP) does not
 * require touching the UI or the state machine.
 */

/** Camera identity, populated once a transport connects. */
data class CameraInfo(
    val manufacturer: String,
    val model: String,
    val serialNumber: String?,
    val deviceVersion: String?,
    val supportedOps: List<String>,
    val supportedEvents: List<String>,
    val vendorExtension: String?,
)

/** A storage card exposed by the camera. */
data class StorageInfo(
    val id: Int,
    val description: String,
    val maxCapacity: Long,
    val freeSpace: Long,
)

/** A photo object on the camera. */
data class PhotoInfo(
    val handle: Int,
    /** Storage this photo lives on — part of the dedup key. */
    val storageId: Int,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val formatName: String,
    /** Thumbnail pixel dimensions — may be 0 if unavailable. */
    val thumbPixWidth: Int = 0,
    val thumbPixHeight: Int = 0,
    /** Full image pixel dimensions — may be 0 if unavailable. */
    val imagePixWidth: Int = 0,
    val imagePixHeight: Int = 0,
    /** Handle of the folder directly containing this photo — part of the group key (R23). */
    val parentHandle: Int = 0,
)

/** A folder on the camera. */
data class FolderInfo(val handle: Int, val name: String, val dateCreated: Long)
