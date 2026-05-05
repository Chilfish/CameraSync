package dev.sebastiano.camerasync.usb

import android.mtp.MtpDevice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.camerasync.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbSyncScreen(viewModel: UsbSyncViewModel, onNavigateBack: () -> Unit) {
    val state by viewModel.state
    val selectionMode by viewModel.selectionMode

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("USB 同步", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.reset()
                            onNavigateBack()
                        }
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back_24dp),
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Status card
            item(key = "status") { StatusCard(state = state) }

            // Camera info (when connected)
            item(key = "camera_info") {
                AnimatedVisibility(
                    visible = state is UsbSyncState.Connected || state is UsbSyncState.Syncing
                ) {
                    val info =
                        when (state) {
                            is UsbSyncState.Connected ->
                                (state as UsbSyncState.Connected).cameraInfo
                            is UsbSyncState.Syncing -> (state as UsbSyncState.Syncing).cameraInfo
                            else -> null
                        }
                    if (info != null) CameraInfoCard(state, info)
                }
            }

            // Progress (when syncing)
            item(key = "progress") {
                AnimatedVisibility(visible = state is UsbSyncState.Syncing) {
                    SyncProgressCard(state as? UsbSyncState.Syncing)
                }
            }

            // Settings (always visible)
            item(key = "settings") {
                SettingsCard(prefs = viewModel.prefs)
            }

            // Selection controls (when connected with photos)
            item(key = "selection") {
                val connected = state as? UsbSyncState.Connected
                if (connected != null && connected.photos.isNotEmpty()) {
                    SelectionControls(
                        photoCount = connected.photos.size,
                        selectedCount = viewModel.selectedCount,
                        onSelectAll = viewModel::selectAll,
                        onDeselectAll = viewModel::deselectAll,
                    )
                }
            }

            // Photo list (when connected with photos)
            val syncingPhotos = (state as? UsbSyncState.Syncing)?.photos
            val connectedPhotos = (state as? UsbSyncState.Connected)?.photos
            val photos = syncingPhotos ?: connectedPhotos
            if (photos != null && photos.isNotEmpty()) {
                itemsIndexed(items = photos, key = { _, p -> p.handle }) { index, photo ->
                    PhotoRow(
                        index = index,
                        photo = photo,
                        isSelected = viewModel.isSelected(photo.handle),
                        onToggle = { viewModel.togglePhotoSelection(photo.handle) },
                        mtpDevice = null, // thumbnail deferred to Step 3
                    )
                }
            }

            // Download progress item (when connected + downloading)
            val connected = state as? UsbSyncState.Connected
            val dlProgress = connected?.downloadProgress
            if (connected != null && connected.isDownloading && dlProgress != null) {
                item(key = "legacy_progress") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("下载中", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { dlProgress.current.toFloat() / dlProgress.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${dlProgress.current} / ${dlProgress.total} — ${dlProgress.currentFileName}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                            if (connected.lastSpeedBytesPerSec > 0) {
                                Text(
                                    "速度: ${formatFileSize(connected.lastSpeedBytesPerSec)}/s",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Action button
            item(key = "action") {
                Spacer(Modifier.height(8.dp))
                SmartActionButton(state = state, viewModel = viewModel)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Status Card ────────────────────────────────────────────────────────────

@Composable
private fun StatusCard(state: UsbSyncState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("连接状态", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            val (label, detail, isRunning) =
                when (state) {
                    is UsbSyncState.Idle -> Triple("等待设备", "通过 C2C 数据线连接 Nikon Z30", false)
                    is UsbSyncState.DeviceDetected ->
                        Triple(
                            "检测到相机",
                            state.deviceName + if (state.hasPermission) "（已有权限）" else "（需授权）",
                            false,
                        )
                    is UsbSyncState.PermissionDenied -> Triple("权限被拒绝", "请在系统设置中授予 USB 权限", false)
                    is UsbSyncState.Connecting -> Triple("正在连接", "打开 MTP 会话…", true)
                    is UsbSyncState.Connected ->
                        Triple(
                            "已连接",
                            "${state.cameraInfo.manufacturer} ${state.cameraInfo.model}" +
                                if (state.photos.isNotEmpty()) " — ${state.photos.size} 张照片"
                                else "",
                            false,
                        )
                    is UsbSyncState.Syncing ->
                        Triple(
                            "正在同步",
                            "${state.downloadProgress.current} / ${state.downloadProgress.total}",
                            true,
                        )
                    is UsbSyncState.Error -> Triple("错误", state.message, false)
                }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRunning) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    val icon =
                        when (state) {
                            is UsbSyncState.Connected ->
                                painterResource(R.drawable.ic_check_circle_24dp)
                            is UsbSyncState.Error,
                            is UsbSyncState.PermissionDenied ->
                                painterResource(R.drawable.ic_error_24dp)
                            is UsbSyncState.DeviceDetected ->
                                painterResource(R.drawable.ic_usb_24dp)
                            else -> painterResource(R.drawable.ic_usb_24dp)
                        }
                    val tint =
                        when (state) {
                            is UsbSyncState.Connected -> MaterialTheme.colorScheme.primary
                            is UsbSyncState.Error,
                            is UsbSyncState.PermissionDenied -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.secondary
                        }
                    Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(label, fontWeight = FontWeight.Medium)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Camera Info Card ────────────────────────────────────────────────────────

@Composable
private fun CameraInfoCard(state: UsbSyncState, info: NikonUsbManager.CameraInfo) {
    val storages = (state as? UsbSyncState.Connected)?.storages ?: emptyList()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("相机信息", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            InfoRow("制造商", info.manufacturer)
            InfoRow("型号", info.model)
            if (info.serialNumber != null) InfoRow("序列号", info.serialNumber)
            if (info.deviceVersion != null) InfoRow("固件版本", info.deviceVersion)

            if (storages.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                for (s in storages) {
                    Text(
                        "存储: ${s.description}  ${formatFileSize(s.freeSpace)} / ${formatFileSize(s.maxCapacity)}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

// ── Sync Progress Card ──────────────────────────────────────────────────────

@Composable
private fun SyncProgressCard(syncing: UsbSyncState.Syncing?) {
    if (syncing == null) return
    val p = syncing.downloadProgress

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("正在同步", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { p.current.toFloat() / p.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${p.current} / ${p.total} — ${p.currentFileName}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            if (syncing.lastSpeedBytesPerSec > 0) {
                Text(
                    "速度: ${formatFileSize(syncing.lastSpeedBytesPerSec)}/s",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Selection Controls ──────────────────────────────────────────────────────

@Composable
private fun SelectionControls(
    photoCount: Int,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("照片 ($photoCount)", fontWeight = FontWeight.Bold)
        if (selectedCount > 0) {
            Row {
                Text("已选 $selectedCount", fontSize = 13.sp, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDeselectAll) { Text("取消") }
            }
        } else {
            TextButton(onClick = onSelectAll) { Text("全选") }
        }
    }
}

// ── Photo Row ───────────────────────────────────────────────────────────────

@Composable
private fun PhotoRow(
    index: Int,
    photo: NikonUsbManager.PhotoInfo,
    isSelected: Boolean,
    onToggle: () -> Unit,
    mtpDevice: MtpDevice?,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surface
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(4.dp))

            // Thumbnail placeholder
            Box(
                modifier =
                    Modifier.size(40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text("JPEG", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = photo.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        photo.formatName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        formatFileSize(photo.size),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Smart Action Button ─────────────────────────────────────────────────────

@Composable
private fun SmartActionButton(state: UsbSyncState, viewModel: UsbSyncViewModel) {
    val isPrimaryDisabled = state is UsbSyncState.Idle || state is UsbSyncState.Connecting

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { viewModel.startSyncFlow() },
            enabled = !isPrimaryDisabled,
            modifier = Modifier.weight(1f),
        ) {
            when (state) {
                is UsbSyncState.Syncing -> {
                    Icon(painterResource(R.drawable.ic_stop_24dp), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                is UsbSyncState.Connected -> {
                    if (state.photos.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_usb_24dp), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                }
                else -> {}
            }
            Text(viewModel.primaryActionLabel())
        }

        if (
            state !is UsbSyncState.Idle &&
                state !is UsbSyncState.Connecting &&
                state !is UsbSyncState.Syncing
        ) {
            OutlinedButton(onClick = { viewModel.reset() }) { Text("重置") }
        }
    }
}

// ── Settings Card ──────────────────────────────────────────────────────────

@Composable
private fun SettingsCard(prefs: UsbSyncPreferences) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("同步设置", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            // Auto-sync toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动同步", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(
                        "插入 USB 后自动开始同步",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = prefs.autoSyncEnabled,
                    onCheckedChange = { prefs.autoSyncEnabled = it },
                )
            }

            Spacer(Modifier.height(12.dp))

            // Format filter
            Text("下载格式", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))

            UsbSyncPreferences.DownloadFormat.entries.forEach { format ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { prefs.downloadFormat = format }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = prefs.downloadFormat == format,
                        onClick = { prefs.downloadFormat = format },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (format) {
                            UsbSyncPreferences.DownloadFormat.ALL -> "全部格式"
                            UsbSyncPreferences.DownloadFormat.JPEG_ONLY -> "仅 JPEG"
                            UsbSyncPreferences.DownloadFormat.RAW_ONLY -> "仅 RAW (NEF)"
                        },
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
