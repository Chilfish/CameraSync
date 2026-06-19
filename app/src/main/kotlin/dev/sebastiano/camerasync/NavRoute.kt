package dev.sebastiano.camerasync

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/** Navigation routes for the application. */
sealed interface NavRoute : Parcelable {

    /** Gallery — primary screen (USB storage / folder list). */
    @Parcelize @Serializable data object Gallery : NavRoute

    /** Gallery folder — photos inside a specific MTP folder. */
    @Parcelize
    @Serializable
    data class GalleryFolder(val storageId: Int, val folderHandle: Int, val folderName: String) :
        NavRoute

    /** Log viewer screen. */
    @Parcelize @Serializable data object LogViewer : NavRoute

    /** Settings screen. */
    @Parcelize @Serializable data object Settings : NavRoute

    /** Transfer history screen. */
    @Parcelize @Serializable data object TransferHistory : NavRoute
}
