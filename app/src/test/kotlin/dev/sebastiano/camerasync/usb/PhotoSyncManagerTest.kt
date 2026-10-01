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

    @Test
    fun `table is capped by evicting the oldest records`() {
        val manager = PhotoSyncManager(InMemorySharedPreferences(), maxEntries = 3)
        (1..5).forEach { h ->
            manager.markAsImported(photo(handle = h, name = "DSC_%04d.JPG".format(h)))
        }
        assertEquals(3, manager.trackedCount)
        assertFalse(manager.isAlreadyImported(photo(handle = 1, name = "DSC_0001.JPG")))
        assertFalse(manager.isAlreadyImported(photo(handle = 2, name = "DSC_0002.JPG")))
        assertTrue(manager.isAlreadyImported(photo(handle = 3, name = "DSC_0003.JPG")))
        assertTrue(manager.isAlreadyImported(photo(handle = 5, name = "DSC_0005.JPG")))
    }

    @Test
    fun `legacy records without a sequence still match on identity`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("s0_h9", "DSC_0009.JPG:5000000").apply()
        val manager = PhotoSyncManager(prefs)
        assertTrue(
            manager.isAlreadyImported(photo(handle = 9, name = "DSC_0009.JPG", size = 5_000_000L))
        )
        assertEquals(1, manager.trackedCount)
    }
}
