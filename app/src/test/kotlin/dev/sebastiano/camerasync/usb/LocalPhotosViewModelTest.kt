package dev.sebastiano.camerasync.usb

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plain-JVM tests for [LocalPhotosViewModel]. android.net.Uri / ContentUris / Cursor are stubs in
 * mockable android.jar, so they are mocked statically (Uri/ContentUris) or via mockk Cursor fakes
 * instead of MatrixCursor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalPhotosViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var contentResolver: ContentResolver
    private lateinit var viewModel: LocalPhotosViewModel

    /** Stable mock of MediaStore.Files.getContentUri("external") — the method is stubbed in JVM. */
    private val filesContentUri: Uri = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        every { app.applicationContext } returns app
        every { app.contentResolver } returns contentResolver

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk(relaxed = true) }
        mockkStatic(ContentUris::class)
        every { ContentUris.withAppendedId(any(), any()) } answers { mockk(relaxed = true) }
        mockkStatic(MediaStore.Files::class)
        every { MediaStore.Files.getContentUri(any()) } returns filesContentUri

        viewModel = LocalPhotosViewModel(app, testDispatcher)
    }

    @After
    fun tearDown() {
        viewModel.stop()
        unmockkStatic(Uri::class)
        unmockkStatic(ContentUris::class)
        unmockkStatic(MediaStore.Files::class)
    }

    // ── Data Model Tests ─────────────────────────────────────────────────────

    @Test
    fun `LocalPhoto holds file uri name dateModified size and isRaw flag`() {
        val file = java.io.File("/pictures/CameraSync/DSC_0001.JPG")
        val uri = Uri.parse("content://media/external/images/media/1")
        val photo =
            LocalPhoto(
                file = file,
                uri = uri,
                name = "DSC_0001.JPG",
                dateModified = 1000L,
                size = 5_000_000L,
                isRaw = false,
            )
        assertEquals("DSC_0001.JPG", photo.name)
        assertEquals(5_000_000L, photo.size)
        assertFalse(photo.isRaw)
        assertEquals(uri, photo.uri)
    }

    @Test
    fun `LocalPhotoGroup has jpg raw displayFile and cacheKey`() {
        val jpgFile = java.io.File("/pictures/CameraSync/DSC_0001.JPG")
        val rawFile = java.io.File("/pictures/CameraSync/DSC_0001.NEF")
        val jpg =
            LocalPhoto(jpgFile, Uri.parse("content://1"), "DSC_0001.JPG", 1000L, 5_000_000L, false)
        val raw =
            LocalPhoto(rawFile, Uri.parse("content://2"), "DSC_0001.NEF", 1000L, 25_000_000L, true)
        val group =
            LocalPhotoGroup(
                baseName = "DSC 0001",
                jpg = jpg,
                raw = raw,
                cacheKey = jpgFile.absolutePath.hashCode(),
                displayFile = jpgFile,
            )
        assertEquals("DSC 0001", group.baseName)
        assertNotNull(group.jpg)
        assertNotNull(group.raw)
        assertEquals(jpgFile.absolutePath.hashCode(), group.cacheKey)
        // displayFile should be JPEG when available
        assertEquals(jpgFile, group.displayFile)
    }

    @Test
    fun `LocalFolder holds name path and photo count`() {
        val folder = LocalFolder("Nikon Z30", "Pictures/CameraSync/Nikon Z30/", 42)
        assertEquals("Nikon Z30", folder.name)
        assertEquals("Pictures/CameraSync/Nikon Z30/", folder.relativePath)
        assertEquals(42, folder.photoCount)
    }

    @Test
    fun `LocalPhotoGroup without jpg uses raw as displayFile`() {
        val rawFile = java.io.File("/pictures/CameraSync/DSC_0001.NEF")
        val raw =
            LocalPhoto(rawFile, Uri.parse("content://2"), "DSC_0001.NEF", 1000L, 25_000_000L, true)
        val group =
            LocalPhotoGroup(
                baseName = "DSC 0001",
                jpg = null,
                raw = raw,
                cacheKey = rawFile.absolutePath.hashCode(),
                displayFile = rawFile,
            )
        assertEquals(rawFile, group.displayFile)
    }

    // ── ViewModel State Tests ─────────────────────────────────────────────────

    @Test
    fun `initial state is empty and not loading`() {
        assertTrue(viewModel.groups.isEmpty())
        assertTrue(viewModel.folders.isEmpty())
        assertFalse(viewModel.loading.value)
        assertFalse(viewModel.isRefreshing)
        assertNull(viewModel.currentPath)
        assertFalse(viewModel.isBrowsingFolder)
    }

    @Test
    fun `loadRoot queries MediaStore and populates groups`() = runTest {
        // Arrange: mock ContentResolver to return test data
        val imageCursor =
            mockCursor(
                imageColumns(),
                listOf(
                    arrayOf<Any?>(
                        1L,
                        "/storage/emulated/0/Pictures/CameraSync/DSC_0001.JPG",
                        "DSC_0001.JPG",
                        1000L,
                        5_000_000L,
                        "image/jpeg",
                    ),
                    arrayOf<Any?>(
                        2L,
                        "/storage/emulated/0/Pictures/CameraSync/DSC_0002.JPG",
                        "DSC_0002.JPG",
                        2000L,
                        6_000_000L,
                        "image/jpeg",
                    ),
                ),
            )
        val nefCursor =
            mockCursor(
                fileColumns(),
                listOf(
                    arrayOf<Any?>(
                        3L,
                        "/storage/emulated/0/Pictures/CameraSync/DSC_0001.NEF",
                        "DSC_0001.NEF",
                        1000L,
                        25_000_000L,
                        "image/x-nikon-nef",
                    )
                ),
            )
        every { contentResolver.query(any(), any(), any(), any(), any()) } answers
            {
                val projection = arg<Array<String>>(1)
                val selection = arg<Array<String>>(3)
                when {
                    // Folder queries project only RELATIVE_PATH (1 column).
                    projection.size == 1 -> emptyFolderCursor()
                    // NEF photo query appends the MIME type to the selection args.
                    selection.lastOrNull() == "image/x-nikon-nef" -> nefCursor
                    else -> imageCursor
                }
            }

        // Act
        viewModel.loadRoot()
        advanceUntilIdle()

        // Assert
        assertFalse(viewModel.loading.value)
        assertNull(viewModel.currentPath)
        assertFalse(viewModel.isBrowsingFolder)
        // We should have grouped the two JPEGs + one NEF
        assertEquals(2, viewModel.groups.size)
    }

    @Test
    fun `empty MediaStore returns empty groups`() = runTest {
        // All queries return empty cursors
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null

        viewModel.loadRoot()
        advanceUntilIdle()

        assertTrue(viewModel.groups.isEmpty())
        assertTrue(viewModel.folders.isEmpty())
    }

    @Test
    fun `enterFolder sets currentPath and loads photos`() = runTest {
        val cursor =
            mockCursor(
                imageColumns(),
                listOf(
                    arrayOf<Any?>(
                        100L,
                        "/storage/emulated/0/Pictures/CameraSync/Nikon Z30/DSC_0001.JPG",
                        "DSC_0001.JPG",
                        2000L,
                        5_000_000L,
                        "image/jpeg",
                    )
                ),
            )
        every { contentResolver.query(any(), any(), any(), any(), any()) } answers
            {
                val projection = arg<Array<String>>(1)
                if (projection.size == 1) emptyFolderCursor() else cursor
            }

        viewModel.enterFolder("Pictures/CameraSync/Nikon Z30/")
        advanceUntilIdle()

        assertEquals("Pictures/CameraSync/Nikon Z30/", viewModel.currentPath)
        assertTrue(viewModel.isBrowsingFolder)
        assertEquals(1, viewModel.groups.size)
    }

    @Test
    fun `goBack from folder returns to root and reloads`() = runTest {
        // First enter a folder
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null
        viewModel.enterFolder("Pictures/CameraSync/Nikon Z30/")
        advanceUntilIdle()
        assertTrue(viewModel.isBrowsingFolder)

        // Then go back — leaving a top-level folder must land on root (null)
        viewModel.goBack()
        advanceUntilIdle()

        assertNull(viewModel.currentPath)
        assertFalse(viewModel.isBrowsingFolder)
    }

    @Test
    fun `goBack at root is no-op`() = runTest {
        assertNull(viewModel.currentPath)
        viewModel.goBack()
        assertNull(viewModel.currentPath)
    }

    // ── Helper: create test cursors ──────────────────────────────────────────

    private fun imageColumns() =
        arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
        )

    private fun fileColumns() =
        arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )

    /** Folder-browsing cursor: only RELATIVE_PATH is projected, and no rows. */
    private fun emptyFolderCursor(): Cursor =
        mockCursor(arrayOf(MediaStore.Images.Media.RELATIVE_PATH), emptyList())

    /**
     * Simulates a Cursor over [rows] (each row aligned with [columns]). MatrixCursor is not usable
     * in plain JVM (mockable android.jar), so a mockk fake is used instead.
     */
    private fun mockCursor(columns: Array<String>, rows: List<Array<Any?>>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var rowIndex = -1
        every { cursor.moveToNext() } answers
            {
                rowIndex++
                rowIndex < rows.size
            }
        every { cursor.getColumnIndexOrThrow(any()) } answers
            {
                columns.indexOf(firstArg<String>())
            }
        every { cursor.getString(any()) } answers { rows[rowIndex][firstArg<Int>()] as String? }
        every { cursor.getLong(any()) } answers
            {
                (rows[rowIndex][firstArg<Int>()] as Number).toLong()
            }
        return cursor
    }
}
