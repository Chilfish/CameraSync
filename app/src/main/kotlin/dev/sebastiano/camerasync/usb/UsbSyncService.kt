package dev.sebastiano.camerasync.usb

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.juul.khronicle.Log
import dev.sebastiano.camerasync.R
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "UsbSyncService"

/**
 * Foreground service that handles USB MTP photo sync from Nikon cameras.
 *
 * Auto-starts on [ACTION_SYNC] intent (typically triggered by USB_DEVICE_ATTACHED). Performs a full
 * sync — connects to the camera, enumerates photos, downloads new ones — then updates the
 * notification with results and stops itself.
 *
 * The service runs briefly (duration of the sync) and is not persistent like
 * [MultiDeviceSyncService].
 */
class UsbSyncService : Service(), CoroutineScope {

    override val coroutineContext =
        Dispatchers.IO + CoroutineName("UsbSyncService") + SupervisorJob()

    private val binder by lazy { UsbSyncServiceBinder() }

    private val _serviceState = MutableStateFlow<UsbSyncServiceState>(UsbSyncServiceState.Stopped)

    /** Current state of the USB sync service. */
    val serviceState: StateFlow<UsbSyncServiceState> = _serviceState.asStateFlow()

    private var syncJob: Job? = null

    private val usbManager: UsbManager by lazy {
        applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    }
    private val nikonUsbManager by lazy { NikonUsbManager(usbManager) }
    private val photoSyncManager by lazy { PhotoSyncManager(applicationContext) }
    private val coordinator by lazy {
        UsbSyncCoordinator(applicationContext, usbManager, nikonUsbManager, photoSyncManager, this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SYNC -> {
                Log.info(tag = TAG) { "Received sync intent" }
                startSync()
            }
            ACTION_STOP -> {
                Log.info(tag = TAG) { "Received stop intent" }
                stopSync()
            }
            else -> {
                Log.info(tag = TAG) { "Service started (no action)" }
                startSync()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSync() {
        if (syncJob?.isActive == true) {
            Log.info(tag = TAG) { "Sync already in progress, ignoring" }
            return
        }

        try {
            startForegroundNotification()
            _serviceState.value = UsbSyncServiceState.Idle
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.error(tag = TAG, throwable = e) { "Cannot start foreground service" }
            return
        }

        syncJob = launch {
            try {
                val result = coordinator.syncOnce()

                if (result.isSuccess) {
                    if (result.synced > 0) {
                        Log.info(tag = TAG) { "Sync complete: ${result.synced} photos imported" }
                        showCompletionNotification(result)
                        // Keep the service alive briefly so the user sees the completion
                        delay(3000)
                    } else {
                        Log.info(tag = TAG) { "No new photos to sync" }
                    }
                } else {
                    Log.error(tag = TAG) { "Sync failed: ${result.error}" }
                    updateErrorNotification(result.error ?: "Unknown error")
                    delay(5000)
                }

                _serviceState.value = UsbSyncServiceState.Stopped
                ServiceCompat.stopForeground(
                    this@UsbSyncService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                NotificationManagerCompat.from(this@UsbSyncService).cancel(NOTIFICATION_ID)
                stopSelf()
            } catch (e: Exception) {
                Log.error(tag = TAG, throwable = e) { "Sync crashed: ${e.message}" }
                _serviceState.value = UsbSyncServiceState.Stopped
                serviceStop()
            }
        }
    }

    private fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        coordinator.reset()
        serviceStop()
    }

    private fun serviceStop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notification =
            buildNotification(title = "Nikon USB 同步", content = "准备同步…", ongoing = true)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateNotification(synced: Int, total: Int, complete: Boolean = false) {
        val title = if (complete) "同步完成" else "正在同步"
        val content = if (complete) "已同步 $synced 张照片" else "$synced / $total 张照片"

        val notification = buildNotification(title = title, content = content, ongoing = !complete)

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun updateErrorNotification(message: String) {
        val notification = buildNotification(title = "USB 同步失败", content = message, ongoing = false)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(result: SyncResult) {
        val thumbnail: Bitmap? =
            result.savedUris.firstOrNull()?.let { uri -> decodeThumbnail(this, uri) }

        val title = getString(R.string.usb_notif_sync_complete_title)
        val body = getString(R.string.usb_notif_sync_complete_body, result.synced)

        val builder =
            NotificationCompat.Builder(this, USB_SYNC_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (thumbnail != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(thumbnail)
                    .bigLargeIcon(null as Bitmap?)
            )
        }

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
    }

    private fun decodeThumbnail(context: Context, uri: Uri, maxSize: Int = 512): Bitmap? {
        return try {
            var scale = 1
            context.contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                scale =
                    maxOf(
                        (options.outWidth / maxSize).coerceAtLeast(1),
                        (options.outHeight / maxSize).coerceAtLeast(1),
                    )
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = scale }
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildNotification(title: String, content: String, ongoing: Boolean): Notification {
        return NotificationCompat.Builder(this, USB_SYNC_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        syncJob?.cancel()
        super.onDestroy()
    }

    inner class UsbSyncServiceBinder : Binder() {
        fun getService(): UsbSyncService = this@UsbSyncService
    }

    companion object {
        const val NOTIFICATION_ID = 120
        const val USB_SYNC_CHANNEL_ID = "USB_SYNC_CHANNEL"
        const val ACTION_SYNC = "dev.sebastiano.camerasync.USB_SYNC_START"
        const val ACTION_STOP = "dev.sebastiano.camerasync.USB_SYNC_STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun createStartIntent(context: Context): Intent =
            Intent(context, UsbSyncService::class.java).apply { action = ACTION_SYNC }

        fun createStopIntent(context: Context): Intent =
            Intent(context, UsbSyncService::class.java).apply { action = ACTION_STOP }
    }
}
