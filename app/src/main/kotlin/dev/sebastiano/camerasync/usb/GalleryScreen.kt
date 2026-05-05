package dev.sebastiano.camerasync.usb

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.camerasync.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Screen ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(onNavigateBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application
    val vm = remember { GalleryViewModel(app) }

    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            val s = vm.state.value
            GalleryTopBar(s, vm.selectedCount, vm::deselectAll, vm::selectAll)
        },
        bottomBar = {
            val s = vm.state.value
            if (s is GalleryState.Browsing && vm.selectedCount > 0) {
                TransferBottomBar(vm)
            }
        },
    ) { innerPadding ->
        val s = vm.state.value
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when (s) {
                is GalleryState.Disconnected -> DisconnectedContent()
                is GalleryState.RequestingPermission -> RequestingPermissionContent()
                is GalleryState.Connecting -> ConnectingContent()
                is GalleryState.Loading -> LoadingContent(s)
                is GalleryState.Browsing -> BrowsingContent(s, vm)
                is GalleryState.Empty -> EmptyCameraContent()
                is GalleryState.Error -> ErrorContent(s, vm::start)
                is GalleryState.Transferring -> TransferringContent(s)
                is GalleryState.TransferDone -> TransferDoneContent(s, vm::dismissTransferDone)
            }
        }
    }
}

// ── Top Bar ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    state: GalleryState,
    selectedCount: Int,
    onDeselectAll: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        title = {
            val title = when (state) {
                is GalleryState.Browsing -> {
                    val model = state.cameraInfo.model
                    if (selectedCount > 0) "已选 $selectedCount 张" else model
                }
                is GalleryState.Transferring -> "正在传输"
                is GalleryState.TransferDone -> "传输完成"
                else -> "USB 照片同步"
            }
            Text(title, fontWeight = FontWeight.Bold)
        },
        actions = {
            if (state is GalleryState.Browsing) {
                if (selectedCount > 0) {
                    TextButton(onClick = onDeselectAll) { Text("取消") }
                } else {
                    TextButton(onClick = onSelectAll) { Text("全选") }
                }
            }
            if (state is GalleryState.TransferDone) {
                TextButton(onClick = onDeselectAll) { Text("") }
            }
        },
    )
}

// ── Disconnected ───────────────────────────────────────────────────────────

@Composable
private fun DisconnectedContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_usb_24dp), null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("连接相机", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "通过 USB-C 数据线连接 Nikon Z30\n插入后将自动识别",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RequestingPermissionContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("请在系统对话框中授权 USB 访问", fontSize = 15.sp)
        }
    }
}

@Composable
private fun ConnectingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在连接相机…", fontSize = 15.sp)
        }
    }
}

@Composable
private fun LoadingContent(state: GalleryState.Loading) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("正在读取照片列表…", fontSize = 15.sp)
            Text(
                "已发现 ${state.total} 张",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyCameraContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_photo_camera_24dp), null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("相机中无照片", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "拍摄照片后会自动出现在这里",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorContent(state: GalleryState.Error, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_error_24dp), null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(state.message, fontSize = 15.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

// ── Browsing (grid) ────────────────────────────────────────────────────────

@Composable
private fun BrowsingContent(state: GalleryState.Browsing, vm: GalleryViewModel) {
    val groups = state.groups
    val gridState = rememberLazyGridState()

    // Detect scroll-to-bottom for pagination
    LaunchedEffect(gridState) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= gridState.layoutInfo.totalItemsCount - 6
        }.collect { nearEnd ->
            if (nearEnd) vm.loadNextPage()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(groups, key = { _, g -> g.baseName }) { _, group ->
            GridCell(group, vm.isSelected(group.previewHandle), vm::getThumbnail) {
                vm.toggleSelection(group.previewHandle)
                // Also toggle RAW if present
                group.raw?.let { vm.toggleSelection(it.handle) }
            }
        }
    }
}

@Composable
private fun GridCell(
    group: PhotoGroup,
    isSelected: Boolean,
    getThumbnail: suspend (Int) -> ByteArray?,
    onClick: () -> Unit,
) {
    var thumb by remember { androidx.compose.runtime.mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(group.previewHandle) {
        thumb = withContext(Dispatchers.IO) {
            getThumbnail(group.previewHandle)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable { onClick() }
            .then(
                if (isSelected)
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Thumbnail or placeholder
            if (thumb != null) {
                androidx.compose.foundation.Image(
                    bitmap = thumb!!,
                    contentDescription = group.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                    Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            // Format badge
            val badgeText = if (group.raw != null) "RAW" else "JPG"
            val badgeColor = if (group.raw != null) Color(0xFFE65100) else Color(0xFF1565C0)
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp)
                    .background(badgeColor.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(badgeText, fontSize = 9.sp, color = Color.White,
                    fontWeight = FontWeight.Bold)
            }

            // Selection overlay
            if (isSelected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Transfer ───────────────────────────────────────────────────────────────

@Composable
private fun TransferBottomBar(vm: GalleryViewModel) {
    androidx.compose.material3.BottomAppBar(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Button(
            onClick = { vm.startTransfer() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text("传输 ${vm.selectedCount} 张照片")
        }
    }
}

@Composable
private fun TransferringContent(state: GalleryState.Transferring) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            LinearProgressIndicator(
                progress = { state.synced.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("正在传输 ${state.synced}/${state.total}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(state.currentFile, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TransferDoneContent(state: GalleryState.TransferDone, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(R.drawable.ic_check_circle_24dp), null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("传输完成", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "已传输 ${state.synced} 张照片",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDismiss) { Text("返回浏览") }
        }
    }
}
