package io.github.chilfish.camerasync.usb

import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.sebastiano.camerasync.usb.LocalFolder
import dev.sebastiano.camerasync.usb.LocalPhoto
import dev.sebastiano.camerasync.usb.LocalPhotoGroup
import dev.sebastiano.camerasync.usb.LocalPhotosViewModel
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

@OptIn(ExperimentalCoroutinesApi::class)
class LocalPhotosViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var contentResolver: ContentResolver
    private lateinit var viewModel: LocalPhotosViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        every { app.applicationContext } returns app
        every { app.contentResolver } returns contentResolver

        // Mock Environment.getExternalStoragePublicDirectory
        mockkStatic(Environment::class)
        every {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        } returns java.io.File("/storage/emulated/0/Pictures")

        viewModel = LocalPhotosViewModel(app, testDispatcher)
    }

    @After
    fun tearDown() {
        viewModel.stop()
        unmockkStatic(Environment::class)
    }

    // ── Data Model Tests ─────────────────────────────────────────────────────

    @Test
    fun `LocalPhoto holds file uri name dateModified size and isRaw flag`() {
        val file = java.io.File("/pictures/CameraSync/DSC_0001.JPG")
        val uri = Uri.parse("content://media/external/images/media/1")
        val photo = LocalPhoto(
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
        val jpg = LocalPhoto(jpgFile, Uri.EMPTY, "DSC_0001.JPG", 1000L, 5_000_000L, false)
        val raw = LocalPhoto(rawFile, Uri.EMPTY, "DSC_0001.NEF", 1000L, 25_000_000L, true)
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
        val raw = LocalPhoto(rawFile, Uri.EMPTY, "DSC_0001.NEF", 1000L, 25_000_000L, true)
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
        val imageCursor = createImageCursor()
        every {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any(),
            )
        } answers {
            // Return image cursor on first call (photos), empty on second call (folders)
            if (firstArg<String>() == "Pictures/CameraSync/") imageCursor
            else MatrixCursor(arrayOf(MediaStore.Images.Media.RELATIVE_PATH))
        }

        val filesCursor = createNefCursor()
        every {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                any(),
                any(),
                any(),
                any(),
            )
        } returns filesCursor

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
            createImageCursor(
                path = "Pictures/CameraSync/Nikon Z30/",
                names = listOf("DSC_0001.JPG"),
                ids = listOf(100),
                dates = listOf(2000L),
                sizes = listOf(5_000_000L),
            )
        every {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                any<String>(),
                any<Array<String>>(),
                any(),
            )
        } returns cursor
        every {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                any(),
                any(),
                any(),
                any(),
            )
        } returns null

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

        // Then go back
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

    private fun createImageCursor(
        path: String = "Pictures/CameraSync/",
        names: List<String> = listOf("DSC_0001.JPG", "DSC_0002.JPG"),
        ids: List<Long> = listOf(1, 2),
        dates: List<Long> = listOf(1000L, 2000L),
        sizes: List<Long> = listOf(5_000_000L, 6_000_000L),
    ): Cursor {
        val cursor =
            MatrixCursor(
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.MIME_TYPE,
                )
            )
        for (i in names.indices) {
            cursor.addRow(
                arrayOf<Any>(
                    ids[i],
                    "/storage/emulated/0/$path${names[i]}",
                    names[i],
                    dates[i],
                    sizes[i],
                    "image/jpeg",
                )
            )
        }
        return cursor
    }

    private fun createNefCursor(
        path: String = "Pictures/CameraSync/",
        names: List<String> = listOf("DSC_0001.NEF"),
        ids: List<Long> = listOf(3),
        dates: List<Long> = listOf(1000L),
        sizes: List<Long> = listOf(25_000_000L),
    ): Cursor {
        val cursor =
            MatrixCursor(
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.DATE_MODIFIED,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                )
            )
        for (i in names.indices) {
            cursor.addRow(
                arrayOf<Any>(
                    ids[i],
                    "/storage/emulated/0/$path${names[i]}",
                    names[i],
                    dates[i],
                    sizes[i],
                    "image/x-nikon-nef",
                )
            )
        }
        return cursor
    }
}
