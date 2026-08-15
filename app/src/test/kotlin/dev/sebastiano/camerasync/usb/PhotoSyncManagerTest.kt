package dev.sebastiano.camerasync.usb

import dev.sebastiano.camerasync.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dedup semantics tests (P2-2): the (storageId, handle) key plus name:size soft identity — a
 * recycled MTP handle pointing at a different photo must be treated as not-yet-imported.
 */
class PhotoSyncManagerTest {

    private fun photo(
        handle: Int = 1,
        storageId: Int = 0,
        name: String = "DSC_0001.JPG",
        size: Long = 5_000_000L,
    ) =
        NikonUsbManager.PhotoInfo(
            handle = handle,
            storageId = storageId,
            name = name,
            size = size,
            dateModified = 1000L,
            formatName = "JPEG",
        )

    @Test
    fun `markAsImported then isAlreadyImported returns true`() {
        val manager = PhotoSyncManager(InMemorySharedPreferences())
        val p = photo()
        assertFalse(manager.isAlreadyImported(p))
        manager.markAsImported(p)
        assertTrue(manager.isAlreadyImported(p))
        assertEquals(1, manager.trackedCount)
    }

    @Test
    fun `same handle on a different storage is not imported`() {
        val manager = PhotoSyncManager(InMemorySharedPreferences())
        manager.markAsImported(photo(handle = 7, storageId = 1))
        assertFalse(manager.isAlreadyImported(photo(handle = 7, storageId = 2)))
    }

    @Test
    fun `recycled handle with different identity is treated as not imported`() {
        val manager = PhotoSyncManager(InMemorySharedPreferences())
        manager.markAsImported(photo(handle = 7, name = "DSC_0001.JPG", size = 5_000_000L))
        // Same handle now points at a different photo (camera reboot / card reformat).
        assertFalse(
            manager.isAlreadyImported(photo(handle = 7, name = "DSC_0002.JPG", size = 6_000_000L))
        )
    }

    @Test
    fun `recycled handle with identical name and size stays imported`() {
        val manager = PhotoSyncManager(InMemorySharedPreferences())
        manager.markAsImported(photo(handle = 7, name = "DSC_0001.JPG", size = 5_000_000L))
        assertTrue(
            manager.isAlreadyImported(photo(handle = 7, name = "DSC_0001.JPG", size = 5_000_000L))
        )
    }

    @Test
    fun `clearAll removes every record`() {
        val manager = PhotoSyncManager(InMemorySharedPreferences())
        manager.markAsImported(photo(handle = 1, storageId = 0))
        manager.markAsImported(photo(handle = 2, storageId = 1))
        manager.clearAll()
        assertEquals(0, manager.trackedCount)
        assertFalse(manager.isAlreadyImported(photo(handle = 1, storageId = 0)))
    }

    @Test
    fun `clearStorage removes only that storage's records`() {
        val manager = PhotoSyncManager(InMemorySharedPreferences())
        manager.markAsImported(photo(handle = 1, storageId = 0))
        manager.markAsImported(photo(handle = 2, storageId = 1))
        manager.clearStorage(0)
        assertFalse(manager.isAlreadyImported(photo(handle = 1, storageId = 0)))
        assertTrue(manager.isAlreadyImported(photo(handle = 2, storageId = 1)))
    }
}
