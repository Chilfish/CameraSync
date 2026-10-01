package dev.sebastiano.camerasync.usb

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import dev.sebastiano.camerasync.R
import dev.sebastiano.camerasync.camera.CameraInfo
import dev.sebastiano.camerasync.camera.PhotoInfo
import dev.sebastiano.camerasync.camera.StorageInfo
import dev.sebastiano.camerasync.ui.theme.CameraSyncTheme

internal fun previewCameraInfo() =
    CameraInfo("NIKON", "Z30", "S1234", "1.01", emptyList(), emptyList(), null)

internal fun previewStorage() =
    StorageInfo(
        id = 1,
        description = "SD CARD",
        maxCapacity = 100_000_000_000L,
        freeSpace = 40_000_000_000L,
    )

internal fun previewPhoto(handle: Int, name: String) =
    PhotoInfo(
        handle = handle,
        storageId = 1,
        name = name,
        size = 5_000_000L,
        dateModified = 1_750_000_000_000L,
        formatName = if (name.endsWith(".NEF", true)) "NEF(RAW)" else "JPEG",
        thumbPixWidth = 160,
        thumbPixHeight = 120,
        imagePixWidth = 5568,
        imagePixHeight = 3712,
    )

internal fun previewGroups() =
    listOf(
        GalleryEntry.PhotoGroup(
            "DSC_0001",
            previewPhoto(1, "DSC_0001.NEF"),
            previewPhoto(2, "DSC_0001.JPG"),
        ),
        GalleryEntry.PhotoGroup("DSC_0002", null, previewPhoto(3, "DSC_0002.JPG")),
        GalleryEntry.PhotoGroup("DSC_0003", previewPhoto(4, "DSC_0003.NEF"), null),
    )

@Preview(name = "Gallery — Disconnected", showBackground = true)
@Composable
internal fun GalleryDisconnectedPreview() {
    CameraSyncTheme { DisconnectedContent() }
}

@Preview(name = "Gallery — Connecting", showBackground = true)
@Composable
internal fun GalleryConnectingPreview() {
    CameraSyncTheme { ConnectingContent() }
}

@Preview(name = "Gallery — Loading", showBackground = true)
@Composable
internal fun GalleryLoadingPreview() {
    CameraSyncTheme {
        LoadingContent(
            GalleryState.Loading(stringResource(R.string.usb_status_loading_photos), 12, 100)
        )
    }
}

@Preview(name = "Gallery — Browsing", showBackground = true)
@Composable
internal fun GalleryBrowsingPreview() {
    val groups = previewGroups()
    CameraSyncTheme {
        BrowsingContent(
            state = GalleryState.Browsing(previewCameraInfo(), listOf(previewStorage()), groups),
            host = PreviewGalleryHost(groups),
            isRoot = true,
        )
    }
}

@Preview(name = "Gallery — Empty", showBackground = true)
@Composable
internal fun GalleryEmptyPreview() {
    CameraSyncTheme { EmptyCameraContent() }
}

@Preview(name = "Gallery — Error", showBackground = true)
@Composable
internal fun GalleryErrorPreview() {
    CameraSyncTheme {
        ErrorContent(message = stringResource(R.string.usb_error_connect), onRetry = {})
    }
}

@Preview(name = "Gallery — Transferring", showBackground = true)
@Composable
internal fun GalleryTransferringPreview() {
    CameraSyncTheme {
        TransferringContent(
            GalleryState.Transferring(
                TransferProgress(
                    synced = 2,
                    total = 5,
                    currentFile = "DSC_0003.NEF",
                    bytesTransferred = 10_000_000L,
                    totalBytes = 25_000_000L,
                    startTimeMillis = System.currentTimeMillis() - 5_000L,
                )
            )
        )
    }
}

@Preview(name = "Gallery — Transfer Done", showBackground = true)
@Composable
internal fun GalleryTransferDonePreview() {
    CameraSyncTheme {
        TransferDonePanel(
            s =
                GalleryState.TransferDone(
                    synced = 3,
                    savedUris =
                        listOf(
                            "content://media/external/images/media/1".toUri(),
                            "content://media/external/images/media/2".toUri(),
                        ),
                ),
            failedHandles = listOf(4),
            onContinue = {},
            onRetryFailed = {},
            onViewSummary = {},
            onRequestDelete = {},
        )
    }
}
