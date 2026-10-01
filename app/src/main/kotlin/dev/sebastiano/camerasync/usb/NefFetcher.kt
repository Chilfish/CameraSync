package dev.sebastiano.camerasync.usb

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import java.io.File
import java.io.IOException
import okio.Buffer
import okio.FileSystem

/**
 * Coil [Fetcher] that renders Nikon NEF files from their embedded JPEG preview, instead of failing
 * to decode the RAW container (which previously left a grey placeholder).
 *
 * Only NEF/NRW files are handled; everything else falls through to Coil's default file fetcher. If
 * no preview can be found the fetch throws, so Coil shows its error placeholder exactly as before.
 */
internal class NefFetcher(private val file: File) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val jpeg =
            NefPreview.extractFromFile(file)
                ?: throw IOException("No embedded JPEG preview in ${file.name}")
        return SourceFetchResult(
            source =
                ImageSource(
                    source = Buffer().apply { write(jpeg) },
                    fileSystem = FileSystem.SYSTEM,
                ),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (NefPreview.isRawFile(data) && data.isFile) NefFetcher(data) else null
    }
}
