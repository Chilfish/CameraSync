@file:Suppress("MagicNumber")

package dev.sebastiano.camerasync.wifi

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests for the WiFi transport POC against an in-process mock camera — no hardware, no
 * manifest permission. Proves the `CameraSource` seam accepts a second transport (ADR-011).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiCameraSourceTest {

    private fun connected(camera: MockPtpIpCamera): Pair<PtpIpClient, WifiCameraSource> {
        val client = PtpIpClient("127.0.0.1", camera.port)
        val source = WifiCameraSource(client)
        assertTrue("connect failed", source.open())
        return client to source
    }

    @Test
    fun `opens a session and reads camera info and storages`() {
        MockPtpIpCamera().use { camera ->
            val (_, source) = connected(camera)
            try {
                assertEquals("NIKON", source.cameraInfo?.manufacturer)
                assertEquals("Z30", source.cameraInfo?.model)
                assertEquals("6001234", source.cameraInfo?.serialNumber)
                assertEquals(1, source.storages.size)
                val storage = source.storages.first()
                assertEquals("SD Card", storage.description)
                assertEquals(128_000_000_000L, storage.maxCapacity)
                assertEquals(64_000_000_000L, storage.freeSpace)
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun `walks folders and lists every photo`() {
        MockPtpIpCamera().use { camera ->
            val (_, source) = connected(camera)
            try {
                val photos = source.listPhotos(MOCK_STORAGE_ID, null, null) {}
                assertEquals(setOf("DSC_0001.JPG", "DSC_0002.NEF"), photos.map { it.name }.toSet())
                val jpg = photos.first { it.name == "DSC_0001.JPG" }
                assertEquals(4L * 1024 * 1024, jpg.size)
                assertEquals(MOCK_ROOT_FOLDER, jpg.parentHandle)
                assertEquals("JPEG", jpg.formatName)
                assertEquals(5568, jpg.imagePixWidth)
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun `lists folders and folder contents`() {
        MockPtpIpCamera().use { camera ->
            val (_, source) = connected(camera)
            try {
                assertEquals(
                    listOf("100NIKON"),
                    source.listFolders(MOCK_STORAGE_ID, PtpIp.ROOT_PARENT).map { it.name },
                )
                assertEquals(2, source.listPhotosInFolder(MOCK_STORAGE_ID, MOCK_ROOT_FOLDER).size)
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun `downloads the thumbnail and the full object`() = runTest {
        MockPtpIpCamera().use { camera ->
            val (_, source) = connected(camera)
            try {
                val thumbnail = source.getThumbnail(1)
                assertTrue(thumbnail != null && thumbnail.isNotEmpty())

                val photo =
                    source.listPhotosInFolder(MOCK_STORAGE_ID, MOCK_ROOT_FOLDER).first {
                        it.handle == 1
                    }
                val out = ByteArrayOutputStream()
                assertEquals(16L, source.download(photo, out))
                assertEquals(16, out.toByteArray().size)
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun `deletes an object and surfaces ObjectAdded events`() {
        MockPtpIpCamera().use { camera ->
            val (client, source) = connected(camera)
            try {
                assertTrue(source.delete(1))
                assertEquals(listOf(1), camera.deletedHandles.toList())

                camera.pushEvent(PtpIp.EVENT_OBJECT_ADDED)
                assertEquals(PtpIp.EVENT_OBJECT_ADDED, client.pollEvent(2000))
            } finally {
                source.close()
            }
        }
    }
}
