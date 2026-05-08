package dev.sebastiano.camerasync.usb

import android.app.Application
import android.content.ContentUris
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import com.juul.khronicle.Log
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LocalPhotosVM"

data class LocalPhoto(
    val uri: Uri,
    val name: String,
    val dateModified: Long,
    val size: Long,
    val width: Int,
    val height: Int,
)

class LocalPhotosViewModel(private val app: Application) {

    var photos by mutableStateOf<List<LocalPhoto>>(emptyList())
        private set

    private val _loading = mutableStateOf(false)
    val loading: State<Boolean> = _loading

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun load() {
        scope.launch {
            _loading.value = true
            val result = withContext(Dispatchers.IO) { queryMediaStore() }
            photos = result
            _loading.value = false
        }
    }

    fun stop() {
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private fun queryMediaStore(): List<LocalPhoto> {
        val projection =
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
            )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("Pictures/CameraSync/%")
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        val cursor =
            app.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder,
            )
        if (cursor == null) {
            Log.warn(tag = TAG) { "MediaStore query returned null cursor" }
            return emptyList()
        }

        val result = mutableListOf<LocalPhoto>()
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val wCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

            while (it.moveToNext()) {
                result.add(
                    LocalPhoto(
                        uri =
                            ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                it.getLong(idCol),
                            ),
                        name = it.getString(nameCol) ?: "",
                        dateModified = it.getLong(dateCol) * 1000L,
                        size = it.getLong(sizeCol),
                        width = it.getInt(wCol),
                        height = it.getInt(hCol),
                    )
                )
            }
        }
        Log.info(tag = TAG) { "Loaded ${result.size} local photos" }
        return result
    }
}
