package dev.sebastiano.camerasync.usb

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Owns the gallery's UI-facing state plus the pure selection / filtering / sorting logic.
 *
 * Extracted from [GalleryViewModel] (P2-1) so the state transitions and filter/sort rules are
 * unit-testable without an MTP device. The ViewModel keeps the USB/transfer orchestration and
 * delegates here for anything the screen reads directly.
 *
 * Compose state lives here: any composable reading [state], [selectedCount], [currentPhotos],
 * [filterMode], [groupingMode] or [sortingMode] recomposes when they change.
 */
class GalleryStateMachine(
    private val photoSyncManager: PhotoSyncManager,
    initialGrouping: UsbSyncPreferences.PhotoGrouping = UsbSyncPreferences.PhotoGrouping.BY_FOLDER,
    initialSorting: UsbSyncPreferences.PhotoSorting = UsbSyncPreferences.PhotoSorting.DATE_DESC,
) {

    private val _state = mutableStateOf<GalleryState>(GalleryState.Disconnected)
    val state: State<GalleryState> = _state

    /** SnapshotStateList — any composable reading this list automatically recomposes. */
    val selected = mutableStateListOf<Int>()
    val selectedCount: Int
        get() = selected.size

    /** Photo groups for the current view. Updated via [updateCurrentPhotos]. */
    var currentPhotos by mutableStateOf(emptyList<GalleryEntry.PhotoGroup>())
        private set

    /** Current photo grouping mode. */
    var groupingMode: UsbSyncPreferences.PhotoGrouping by mutableStateOf(initialGrouping)
        private set

    /** Current photo sorting mode. */
    var sortingMode: UsbSyncPreferences.PhotoSorting by mutableStateOf(initialSorting)
        private set

    /**
     * Default view is the new-photos filter: connecting lands on what's ready to transfer (P1-2).
     */
    var filterMode: PhotoFilter by mutableStateOf(PhotoFilter.NEW)
        private set

    private var filterCacheGeneration by mutableStateOf(0)
    private var cachedFilteredGroups: List<GalleryEntry.PhotoGroup> = emptyList()

    fun setState(newState: GalleryState) {
        _state.value = newState
    }

    /** Re-reads grouping/sorting from persisted prefs so settings take effect immediately. */
    fun refreshModes(
        grouping: UsbSyncPreferences.PhotoGrouping,
        sorting: UsbSyncPreferences.PhotoSorting,
    ) {
        groupingMode = grouping
        sortingMode = sorting
    }

    fun setGrouping(mode: UsbSyncPreferences.PhotoGrouping) {
        groupingMode = mode
    }

    fun setSorting(mode: UsbSyncPreferences.PhotoSorting) {
        sortingMode = mode
        invalidateFilterCache()
    }

    fun setFilter(mode: PhotoFilter) {
        filterMode = mode
        invalidateFilterCache()
    }

    /** Updates [currentPhotos] and invalidates the filtered-groups cache. */
    fun updateCurrentPhotos(groups: List<GalleryEntry.PhotoGroup>) {
        currentPhotos = groups
        invalidateFilterCache()
    }

    fun getFilteredGroups(): List<GalleryEntry.PhotoGroup> = cachedFilteredGroups

    fun getNewPhotoCount(): Int =
        currentPhotos.count { group ->
            val photos = listOfNotNull(group.raw, group.jpg)
            photos.any { !photoSyncManager.isAlreadyImported(it) }
        }

    /** Returns true if any photo in the group has already been imported. */
    fun isGroupImported(group: GalleryEntry.PhotoGroup): Boolean =
        listOfNotNull(group.raw, group.jpg).any { photoSyncManager.isAlreadyImported(it) }

    /** Returns the handles to select for a group, respecting [downloadFormat]. */
    fun handlesForFormat(
        group: GalleryEntry.PhotoGroup,
        downloadFormat: UsbSyncPreferences.DownloadFormat,
    ): List<Int> =
        when (downloadFormat) {
            UsbSyncPreferences.DownloadFormat.ALL ->
                listOfNotNull(group.raw?.handle, group.jpg?.handle)
            UsbSyncPreferences.DownloadFormat.RAW_ONLY -> listOfNotNull(group.raw?.handle)
            UsbSyncPreferences.DownloadFormat.JPEG_ONLY -> listOfNotNull(group.jpg?.handle)
        }

    fun toggleSelection(
        group: GalleryEntry.PhotoGroup,
        downloadFormat: UsbSyncPreferences.DownloadFormat,
    ) {
        val handles = handlesForFormat(group, downloadFormat)
        if (handles.isEmpty()) return
        if (handles.all { it in selected }) handles.forEach { selected.remove(it) }
        else handles.forEach { selected.add(it) }
    }

    fun selectAll(downloadFormat: UsbSyncPreferences.DownloadFormat) {
        currentPhotos
            .flatMap { handlesForFormat(it, downloadFormat) }
            .forEach { if (it !in selected) selected.add(it) }
    }

    /**
     * Selects the transferable handles of all not-yet-imported groups, respecting [downloadFormat].
     */
    fun selectAllNew(downloadFormat: UsbSyncPreferences.DownloadFormat) {
        currentPhotos
            .filter { group ->
                listOfNotNull(group.raw, group.jpg).any { !photoSyncManager.isAlreadyImported(it) }
            }
            .flatMap { handlesForFormat(it, downloadFormat) }
            .forEach { if (it !in selected) selected.add(it) }
    }

    fun deselectAll() {
        selected.clear()
    }

    fun isSelected(h: Int) = h in selected

    fun isGroupSelected(group: GalleryEntry.PhotoGroup): Boolean {
        val handles = listOfNotNull(group.raw?.handle, group.jpg?.handle)
        return handles.isNotEmpty() && handles.any { it in selected }
    }

    private fun invalidateFilterCache() {
        filterCacheGeneration++
        cachedFilteredGroups = computeFiltered()
    }

    private fun computeFiltered(): List<GalleryEntry.PhotoGroup> {
        val filtered =
            when (filterMode) {
                PhotoFilter.ALL -> currentPhotos
                PhotoFilter.NEW ->
                    currentPhotos.filter { group ->
                        val photos = listOfNotNull(group.raw, group.jpg)
                        photos.any { !photoSyncManager.isAlreadyImported(it) }
                    }
                PhotoFilter.RAW_ONLY -> currentPhotos.filter { it.hasRaw }
                PhotoFilter.JPEG_ONLY -> currentPhotos.filter { it.jpg != null }
            }
        return applySorting(filtered)
    }

    private fun applySorting(photos: List<GalleryEntry.PhotoGroup>): List<GalleryEntry.PhotoGroup> =
        when (sortingMode) {
            UsbSyncPreferences.PhotoSorting.DATE_DESC ->
                photos.sortedByDescending {
                    maxOf(it.raw?.dateModified ?: 0L, it.jpg?.dateModified ?: 0L)
                }
            UsbSyncPreferences.PhotoSorting.DATE_ASC ->
                photos.sortedBy { maxOf(it.raw?.dateModified ?: 0L, it.jpg?.dateModified ?: 0L) }
            UsbSyncPreferences.PhotoSorting.NAME_ASC -> photos.sortedBy { it.baseName }
            UsbSyncPreferences.PhotoSorting.NAME_DESC -> photos.sortedByDescending { it.baseName }
            UsbSyncPreferences.PhotoSorting.SIZE_DESC ->
                photos.sortedByDescending { (it.raw?.size ?: 0L) + (it.jpg?.size ?: 0L) }
        }
}
