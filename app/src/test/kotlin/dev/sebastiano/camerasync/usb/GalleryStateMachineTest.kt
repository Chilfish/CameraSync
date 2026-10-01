package dev.sebastiano.camerasync.usb

import dev.sebastiano.camerasync.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State machine tests (P2-2): transitions, filter/sort rules and selection — pure logic extracted
 * in P2-1, no MTP device needed.
 */
class GalleryStateMachineTest {

    private val manager = PhotoSyncManager(InMemorySharedPreferences())

    private fun machine(
        grouping: UsbSyncPreferences.PhotoGrouping = UsbSyncPreferences.PhotoGrouping.BY_FOLDER,
        sorting: UsbSyncPreferences.PhotoSorting = UsbSyncPreferences.PhotoSorting.DATE_DESC,
    ) = GalleryStateMachine(manager, grouping, sorting)

    private fun photo(
        handle: Int,
        name: String = "DSC_%04d.JPG".format(handle),
        dateModified: Long = 1000L * handle,
        size: Long = 1_000L * handle,
        isRaw: Boolean = false,
        storageId: Int = 0,
        parentHandle: Int = 0,
    ) =
        NikonUsbManager.PhotoInfo(
            handle = handle,
            storageId = storageId,
            name = name,
            size = size,
            dateModified = dateModified,
            formatName = if (isRaw) "NEF(RAW)" else "JPEG",
            parentHandle = parentHandle,
        )

    private fun group(
        base: String,
        raw: NikonUsbManager.PhotoInfo? = null,
        jpg: NikonUsbManager.PhotoInfo? = null,
    ) = GalleryEntry.PhotoGroup(baseName = base, raw = raw, jpg = jpg)

    // ── State transitions ────────────────────────────────────────────────────

    @Test
    fun `initial state is Disconnected with NEW filter`() {
        val m = machine()
        assertEquals(GalleryState.Disconnected, m.state.value)
        assertEquals(PhotoFilter.NEW, m.filterMode)
    }

    @Test
    fun `setState drives the full happy-path transition`() {
        val m = machine()
        m.setState(GalleryState.Connecting)
        assertEquals(GalleryState.Connecting, m.state.value)
        m.setState(GalleryState.Browsing(null, emptyList(), emptyList()))
        assertEquals(GalleryState.Browsing(null, emptyList(), emptyList()), m.state.value)
        m.setState(
            GalleryState.Transferring(TransferProgress(1, 1, "DSC_0001.JPG", 100L, 100L, 0L))
        )
        assertTrue(m.state.value is GalleryState.Transferring)
        m.setState(GalleryState.TransferDone(1))
        assertEquals(GalleryState.TransferDone(1), m.state.value)
        m.setState(GalleryState.Error("boom"))
        assertEquals(GalleryState.Error("boom"), m.state.value)
    }

    // ── Filtering ────────────────────────────────────────────────────────────

    @Test
    fun `NEW filter keeps only groups with at least one unimported photo`() {
        val m = machine()
        val imported = photo(1, name = "DSC_0001.JPG")
        manager.markAsImported(imported)
        m.updateCurrentPhotos(
            listOf(
                group("DSC_0001", jpg = imported),
                group("DSC_0002", jpg = photo(2, name = "DSC_0002.JPG")),
            )
        )
        assertEquals(listOf("DSC_0002"), m.getFilteredGroups().map { it.baseName })
        assertEquals(1, m.getNewPhotoCount())
    }

    @Test
    fun `RAW_ONLY and JPEG_ONLY filters keep matching groups`() {
        val m = machine()
        m.updateCurrentPhotos(
            listOf(
                group("RAW1", raw = photo(1, isRaw = true, name = "DSC_0001.NEF")),
                group("JPG1", jpg = photo(2, name = "DSC_0002.JPG")),
                group(
                    "BOTH",
                    raw = photo(3, isRaw = true, name = "DSC_0003.NEF"),
                    jpg = photo(4, name = "DSC_0003.JPG"),
                ),
            )
        )
        m.setFilter(PhotoFilter.RAW_ONLY)
        assertEquals(setOf("RAW1", "BOTH"), m.getFilteredGroups().map { it.baseName }.toSet())
        m.setFilter(PhotoFilter.JPEG_ONLY)
        assertEquals(setOf("JPG1", "BOTH"), m.getFilteredGroups().map { it.baseName }.toSet())
    }

    // ── Sorting ──────────────────────────────────────────────────────────────

    @Test
    fun `sorting by name and by date desc`() {
        val m = machine(sorting = UsbSyncPreferences.PhotoSorting.NAME_ASC)
        m.updateCurrentPhotos(
            listOf(
                group("B", jpg = photo(1, name = "B.JPG")),
                group("A", jpg = photo(2, name = "A.JPG")),
            )
        )
        m.setSorting(UsbSyncPreferences.PhotoSorting.NAME_ASC)
        assertEquals(listOf("A", "B"), m.getFilteredGroups().map { it.baseName })

        m.setSorting(UsbSyncPreferences.PhotoSorting.DATE_DESC)
        // photo(2) has date 2000, photo(1) has date 1000 → newest first
        assertEquals(listOf("A", "B"), m.getFilteredGroups().map { it.baseName })
    }

    @Test
    fun `grouping and sorting refresh from prefs`() {
        val m = machine()
        m.refreshModes(
            UsbSyncPreferences.PhotoGrouping.BY_DATE,
            UsbSyncPreferences.PhotoSorting.SIZE_DESC,
        )
        assertEquals(UsbSyncPreferences.PhotoGrouping.BY_DATE, m.groupingMode)
        assertEquals(UsbSyncPreferences.PhotoSorting.SIZE_DESC, m.sortingMode)
    }

    // ── Selection ────────────────────────────────────────────────────────────

    @Test
    fun `handlesForFormat respects download format`() {
        val m = machine()
        val g = group("BOTH", raw = photo(1, isRaw = true), jpg = photo(2))
        assertEquals(listOf(1, 2), m.handlesForFormat(g, UsbSyncPreferences.DownloadFormat.ALL))
        assertEquals(listOf(1), m.handlesForFormat(g, UsbSyncPreferences.DownloadFormat.RAW_ONLY))
        assertEquals(listOf(2), m.handlesForFormat(g, UsbSyncPreferences.DownloadFormat.JPEG_ONLY))
    }

    @Test
    fun `toggleSelection selects and deselects the group's handles`() {
        val m = machine()
        val g = group("BOTH", raw = photo(1, isRaw = true), jpg = photo(2))
        m.toggleSelection(g, UsbSyncPreferences.DownloadFormat.ALL)
        assertTrue(m.isGroupSelected(g))
        assertEquals(2, m.selectedCount)
        m.toggleSelection(g, UsbSyncPreferences.DownloadFormat.ALL)
        assertFalse(m.isGroupSelected(g))
        assertEquals(0, m.selectedCount)
    }

    @Test
    fun `selectAllNew selects only not-yet-imported groups`() {
        val m = machine()
        val imported = photo(1, name = "DSC_0001.JPG")
        manager.markAsImported(imported)
        m.updateCurrentPhotos(
            listOf(
                group("OLD", jpg = imported),
                group("NEW", jpg = photo(2, name = "DSC_0002.JPG")),
            )
        )
        m.selectAllNew(UsbSyncPreferences.DownloadFormat.ALL)
        assertFalse(m.isSelected(imported.handle))
        assertTrue(m.isSelected(2))
        m.deselectAll()
        assertEquals(0, m.selectedCount)
    }

    @Test
    fun `selectAll respects download format`() {
        val m = machine()
        m.updateCurrentPhotos(listOf(group("BOTH", raw = photo(1, isRaw = true), jpg = photo(2))))
        m.selectAll(UsbSyncPreferences.DownloadFormat.JPEG_ONLY)
        assertEquals(setOf(2), m.selected)
    }

    // ── Grouping (R23) ───────────────────────────────────────────────────────

    @Test
    fun `same base name on different storages stays in separate groups`() {
        val groups =
            GalleryViewModel.groupByBaseFilename(
                listOf(
                    photo(1, name = "DSC_0001.JPG", storageId = 1),
                    photo(2, name = "DSC_0001.JPG", storageId = 2),
                )
            )
        assertEquals(2, groups.size)
        assertEquals(setOf(1, 2), groups.map { it.jpg?.storageId }.toSet())
        assertEquals(2, groups.map { it.key }.toSet().size)
    }

    @Test
    fun `same base name in different folders stays in separate groups`() {
        val groups =
            GalleryViewModel.groupByBaseFilename(
                listOf(
                    photo(1, name = "DSC_0001.JPG", storageId = 1, parentHandle = 10),
                    photo(2, name = "DSC_0001.JPG", storageId = 1, parentHandle = 20),
                )
            )
        assertEquals(2, groups.size)
        assertEquals(setOf(10, 20), groups.map { it.jpg?.parentHandle }.toSet())
    }

    @Test
    fun `RAW and JPEG of the same base name coalesce into one group`() {
        val groups =
            GalleryViewModel.groupByBaseFilename(
                listOf(
                    photo(1, name = "DSC_0001.NEF", isRaw = true),
                    photo(2, name = "DSC_0001.JPG"),
                )
            )
        assertEquals(1, groups.size)
        assertEquals("DSC_0001", groups.single().baseName)
        assertEquals(1, groups.single().raw?.handle)
        assertEquals(2, groups.single().jpg?.handle)
    }
}
