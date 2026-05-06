package dev.sebastiano.camerasync

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/** Navigation routes for the application. */
sealed interface NavRoute : Parcelable {

    /** Onboarding flow — shown on first launch. */
    @Parcelize @Serializable data object Onboarding : NavRoute

    /** Permissions need to be requested. */
    @Parcelize @Serializable data object NeedsPermissions : NavRoute

    /** Gallery — primary screen (USB storage / folder list). */
    @Parcelize @Serializable data object Gallery : NavRoute

    /** Gallery folder — photos inside a specific MTP folder. */
    @Parcelize @Serializable data class GalleryFolder(
        val storageId: Int,
        val folderHandle: Int,
        val folderName: String,
    ) : NavRoute

    /** Main screen showing paired BLE devices. */
    @Parcelize @Serializable data object DevicesList : NavRoute

    /** Pairing screen for adding new devices. */
    @Parcelize @Serializable data object Pairing : NavRoute

    /** Log viewer screen. */
    @Parcelize @Serializable data object LogViewer : NavRoute

    /** Settings screen. */
    @Parcelize @Serializable data object Settings : NavRoute

    /** Transfer history screen. */
    @Parcelize @Serializable data object TransferHistory : NavRoute
}
