package dev.sebastiano.camerasync.usb

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import dev.sebastiano.camerasync.InMemorySharedPreferences
import dev.sebastiano.camerasync.camera.FakeCameraSource
import dev.sebastiano.camerasync.camera.PhotoInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Transfer engine tests (P2-2): happy path, MediaStore save failures, retry of failed handles and
 * camera deletion — orchestration extracted in P2-1, MediaStore mocked and the camera backed by
 * [FakeCameraSource].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferEngineTest {

    private val manager = PhotoSyncManager(InMemorySharedPreferences())
    private val machine = GalleryStateMachine(manager)

    private lateinit var app: Application
    private lateinit var contentResolver: ContentResolver
    private lateinit var cameraSource: FakeCameraSource

    @Before
    fun setUp() {
        app = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        every { app.contentResolver } returns contentResolver
        cameraSource = FakeCameraSource()
    }

    /** Engine whose coroutines run on the test scheduler (driven by advanceUntilIdle). */
    private fun createEngine(scope: () -> CoroutineScope) =
        TransferEngine(
            scope = scope,
            app = app,
            camera = { cameraSource },
            photoSyncManager = manager,
            stateMachine = machine,
            prefs = UsbSyncPreferences(app),
            cameraInfo = { null },
            cancelPendingWork = {},
        )

    private fun photo(
        handle: Int,
        name: String = "DSC_%04d.JPG".format(handle),
        size: Long = 5_000_000L,
    ) =
        PhotoInfo(
            handle = handle,
            storageId = 0,
            name = name,
            size = size,
            dateModified = 1000L,
            formatName = "JPEG",
        )

    private fun group(p: PhotoInfo) = GalleryEntry.PhotoGroup(p.name, null, p)

    private fun group(raw: PhotoInfo, jpg: PhotoInfo) =
        GalleryEntry.PhotoGroup(jpg.name.substringBeforeLast("."), raw, jpg)

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    fun `startTransfer saves photos and marks them imported`() = runTest {
        val engine = createEngine { this }
        val p = photo(1)
        machine.updateCurrentPhotos(listOf(group(p)))
        machine.select(1)
        val uri = mockk<Uri>(relaxed = true)
        every { contentResolver.insert(any(), any()) } returns uri
        every { contentResolver.openOutputStream(uri) } returns mockk<OutputStream>(relaxed = true)

        engine.startTransfer()
        advanceUntilIdle()

        val done = machine.state.value as GalleryState.TransferDone
        assertEquals(1, done.synced)
        assertEquals(listOf(uri), done.savedUris)
        assertTrue(manager.isAlreadyImported(p))
        assertEquals(listOf(1), engine.lastTransferredHandles)
        assertTrue(engine.failedHandles.isEmpty())
        assertEquals(0, machine.selectedCount)
        verify { contentResolver.update(uri, any(), any(), any()) }
    }

    @Test
    fun `startTransfer downloads both files of a RAW+JPEG pair when ALL is selected`() = runTest {
        val engine = createEngine { this }
        val raw = photo(1, name = "DSC_0001.NEF")
        val jpg = photo(2, name = "DSC_0001.JPG")
        machine.updateCurrentPhotos(listOf(group(raw, jpg)))
        machine.selectAll(UsbSyncPreferences.DownloadFormat.ALL)

        val firstUri = mockk<Uri>(relaxed = true)
        val secondUri = mockk<Uri>(relaxed = true)
        every { contentResolver.insert(any(), any()) } returnsMany listOf(firstUri, secondUri)
        every { contentResolver.openOutputStream(firstUri) } returns
            mockk<OutputStream>(relaxed = true)
        every { contentResolver.openOutputStream(secondUri) } returns
            mockk<OutputStream>(relaxed = true)

        engine.startTransfer()
        advanceUntilIdle()

        assertEquals(listOf(1, 2), cameraSource.downloadedHandles)
        assertEquals(listOf(1, 2), engine.lastTransferredHandles)
        val done = machine.state.value as GalleryState.TransferDone
        assertEquals(2, done.synced)
        assertTrue(manager.isAlreadyImported(raw))
        assertTrue(manager.isAlreadyImported(jpg))
    }

    @Test
    fun `startTransfer with empty selection is a no-op TransferDone`() = runTest {
        val engine = createEngine { this }
        engine.startTransfer()
        assertEquals(GalleryState.TransferDone(0), machine.state.value)
    }

    @Test
    fun `already imported photos are skipped without downloading`() = runTest {
        val engine = createEngine { this }
        val p = photo(1)
        manager.markAsImported(p)
        machine.updateCurrentPhotos(listOf(group(p)))
        machine.select(1)

        engine.startTransfer()
        advanceUntilIdle()

        assertEquals(GalleryState.TransferDone(0), machine.state.value)
        assertTrue(cameraSource.downloadedHandles.isEmpty())
    }

    @Test
    fun `transfer launches on the scope provided at call time, not at construction`() = runTest {
        val deadScope = CoroutineScope(SupervisorJob())
        deadScope.cancel()
        var scopeProvider: () -> CoroutineScope = { deadScope }
        val engine = createEngine { scopeProvider() }

        val p = photo(1)
        machine.updateCurrentPhotos(listOf(group(p)))
        machine.select(1)
        val uri = mockk<Uri>(relaxed = true)
        every { contentResolver.insert(any(), any()) } returns uri
        every { contentResolver.openOutputStream(uri) } returns mockk<OutputStream>(relaxed = true)

        // Simulate stop()+start(): the scope provider now yields a fresh, active scope.
        scopeProvider = { this }

        engine.startTransfer()
        advanceUntilIdle()

        val done = machine.state.value as GalleryState.TransferDone
        assertEquals(1, done.synced)
    }

    // ── Failure paths ────────────────────────────────────────────────────────

    @Test
    fun `insert returning null records the handle as failed`() = runTest {
        val engine = createEngine { this }
        val p = photo(1)
        machine.updateCurrentPhotos(listOf(group(p)))
        machine.select(1)
        every { contentResolver.insert(any(), any()) } returns null

        engine.startTransfer()
        advanceUntilIdle()

        val done = machine.state.value as GalleryState.TransferDone
        assertEquals(0, done.synced)
        assertEquals(listOf(1), engine.failedHandles)
        assertFalse(manager.isAlreadyImported(p))
    }

    @Test
    fun `null output stream deletes the pending uri and fails the photo`() = runTest {
        val engine = createEngine { this }
        val p = photo(1)
        machine.updateCurrentPhotos(listOf(group(p)))
        machine.select(1)
        val uri = mockk<Uri>(relaxed = true)
        every { contentResolver.insert(any(), any()) } returns uri
        every { contentResolver.openOutputStream(uri) } returns null

        engine.startTransfer()
        advanceUntilIdle()

        val done = machine.state.value as GalleryState.TransferDone
        assertEquals(0, done.synced)
        assertEquals(listOf(1), engine.failedHandles)
        verify { contentResolver.delete(uri, null, null) }
    }

    @Test
    fun `download throwing deletes the pending uri and fails the photo`() = runTest {
        val engine = createEngine { this }
        val p = photo(1)
        machine.updateCurrentPhotos(listOf(group(p)))
        machine.select(1)
        val uri = mockk<Uri>(relaxed = true)
        every { contentResolver.insert(any(), any()) } returns uri
        every { contentResolver.openOutputStream(uri) } returns mockk<OutputStream>(relaxed = true)
        cameraSource.downloadError = RuntimeException("MTP error")

        engine.startTransfer()
        advanceUntilIdle()

        assertEquals(listOf(1), engine.failedHandles)
        verify { contentResolver.delete(uri, null, null) }
        assertFalse(manager.isAlreadyImported(p))
    }

    @Test
    fun `cancellation during download propagates and is not recorded as a failure`() = runTest {
        // Own scope so the CancellationException doesn't tear down the test coroutine.
        val transferScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val engine = createEngine { transferScope }
        val p = photo(1)
        machine.updateCurrentPhotos(listOf(group(p)))
        machine.select(1)
        val uri = mockk<Uri>(relaxed = true)
        every { contentResolver.insert(any(), any()) } returns uri
        every { contentResolver.openOutputStream(uri) } returns mockk<OutputStream>(relaxed = true)
        cameraSource.downloadError = CancellationException("cancelled")

        engine.startTransfer()
        advanceUntilIdle()

        assertTrue(engine.failedHandles.isEmpty())
        assertFalse(manager.isAlreadyImported(p))
        verify { contentResolver.delete(uri, null, null) }
    }

    // ── Retry & delete ───────────────────────────────────────────────────────

    @Test
    fun `retryFailedTransfers retries only the failed handles`() = runTest {
        val engine = createEngine { this }
        val p1 = photo(1)
        val p2 = photo(2)
        machine.updateCurrentPhotos(listOf(group(p1), group(p2)))
        listOf(1, 2).forEach { machine.select(it) }
        // First pass: both inserts fail.
        every { contentResolver.insert(any(), any()) } returns null
        engine.startTransfer()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), engine.failedHandles)

        // Second pass: inserts succeed.
        val uri = mockk<Uri>(relaxed = true)
        every { contentResolver.insert(any(), any()) } returns uri
        every { contentResolver.openOutputStream(uri) } returns mockk<OutputStream>(relaxed = true)

        engine.retryFailedTransfers()
        advanceUntilIdle()

        val done = machine.state.value as GalleryState.TransferDone
        assertEquals(2, done.synced)
        assertTrue(engine.failedHandles.isEmpty())
        assertTrue(manager.isAlreadyImported(p1))
        assertTrue(manager.isAlreadyImported(p2))
    }

    @Test
    fun `deletePhotos returns count of successfully deleted handles`() = runTest {
        val engine = createEngine { this }
        cameraSource.deleteResult = { it == 1 }
        assertEquals(1, engine.deletePhotos(listOf(1, 2)))
        assertEquals(listOf(1, 2), cameraSource.deletedHandles)
    }
}
