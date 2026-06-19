package dev.sebastiano.camerasync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import com.juul.khronicle.ConsoleLogger
import com.juul.khronicle.Log
import com.juul.khronicle.Logger
import dev.sebastiano.camerasync.di.AppGraph
import dev.sebastiano.camerasync.usb.UsbSyncService
import dev.zacsweers.metro.createGraphFactory
import kotlin.getValue

class CameraSyncApp : Application(), SingletonImageLoader.Factory {

    val appGraph by lazy { createGraphFactory<AppGraph.Factory>().create(application = this) }

    @Suppress("TooGenericExceptionCaught")
    override fun onCreate() {
        super.onCreate()
        initializeLogging()

        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.error("Crash", throwable = throwable) { "FATAL: ${throwable.message}" }
            originalHandler?.uncaughtException(thread, throwable)
        }

        Log.info(javaClass.simpleName) {
            "-------------------- CAMERASYNC STARTED --------------------"
        }

        registerUsbSyncChannel()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun registerUsbSyncChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel =
                NotificationChannel(
                    UsbSyncService.USB_SYNC_CHANNEL_ID,
                    "USB 同步",
                    NotificationManager.IMPORTANCE_LOW,
                )
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        fun initializeLogging(logger: Logger = ConsoleLogger) {
            Log.dispatcher.install(logger)
        }
    }

    // ── Coil ImageLoader configuration ──────────────────────────────────────────

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil_disk").toOkioPath())
                    .maxSizeBytes(50 * 1024 * 1024)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.10)
                    .build()
            }
            .build()
}
