package dev.sebastiano.camerasync.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.camerasync.R
import dev.sebastiano.camerasync.usb.UsbSyncPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: UsbSyncPreferences,
    onNavigateBack: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToOnboarding: () -> Unit,
    onGroupingChanged: (UsbSyncPreferences.PhotoGrouping) -> Unit = {},
    onSortingChanged: (UsbSyncPreferences.PhotoSorting) -> Unit = {},
    onDownloadFormatChanged: (UsbSyncPreferences.DownloadFormat) -> Unit = {},
    onGridColumnsChanged: (Int) -> Unit = {},
) {
    var autoSync by remember { mutableStateOf(prefs.autoSyncEnabled) }
    var gridCols by remember { mutableIntStateOf(prefs.getGridColumns()) }
    var grouping by remember { mutableStateOf(prefs.photoGrouping) }
    var sorting by remember { mutableStateOf(prefs.photoSorting) }
    var downloadFormat by remember { mutableStateOf(prefs.downloadFormat) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back_24dp), "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Auto-sync toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动同步", fontWeight = FontWeight.Medium)
                        Text(
                            "插线时自动导入新照片",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoSync,
                        onCheckedChange = {
                            autoSync = it
                            prefs.autoSyncEnabled = it
                        },
                    )
                }
            }

            // Grid density
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("照片网格", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 3, 4).forEach { cols ->
                            FilterChip(
                                selected = gridCols == cols,
                                onClick = {
                                    gridCols = cols
                                    prefs.setGridColumns(cols)
                                    onGridColumnsChanged(cols)
                                },
                                label = { Text("${cols}列") },
                            )
                        }
                    }
                }
            }

            // Photo grouping
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("照片分组", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                                UsbSyncPreferences.PhotoGrouping.BY_FOLDER to "按文件夹",
                                UsbSyncPreferences.PhotoGrouping.BY_DATE to "按日期",
                                UsbSyncPreferences.PhotoGrouping.FLAT to "不分组",
                            )
                            .forEach { (mode, label) ->
                                FilterChip(
                                    selected = grouping == mode,
                                    onClick = {
                                        grouping = mode
                                        prefs.photoGrouping = mode
                                        onGroupingChanged(mode)
                                    },
                                    label = { Text(label, fontSize = 13.sp) },
                                )
                            }
                    }
                }
            }

            // Photo sorting
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("照片排序", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                                UsbSyncPreferences.PhotoSorting.DATE_DESC to "最新优先",
                                UsbSyncPreferences.PhotoSorting.NAME_ASC to "按名称",
                                UsbSyncPreferences.PhotoSorting.SIZE_DESC to "按大小",
                            )
                            .forEach { (mode, label) ->
                                FilterChip(
                                    selected = sorting == mode,
                                    onClick = {
                                        sorting = mode
                                        prefs.photoSorting = mode
                                        onSortingChanged(mode)
                                    },
                                    label = { Text(label, fontSize = 13.sp) },
                                )
                            }
                    }
                }
            }

            // Download format
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("默认下载格式", fontWeight = FontWeight.Medium)
                    Text(
                        "连接相机后默认显示的照片类型",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                                UsbSyncPreferences.DownloadFormat.ALL to "全部",
                                UsbSyncPreferences.DownloadFormat.JPEG_ONLY to "仅 JPEG",
                                UsbSyncPreferences.DownloadFormat.RAW_ONLY to "仅 RAW",
                            )
                            .forEach { (format, label) ->
                                FilterChip(
                                    selected = downloadFormat == format,
                                    onClick = {
                                        downloadFormat = format
                                        prefs.downloadFormat = format
                                        onDownloadFormatChanged(format)
                                    },
                                    label = { Text(label, fontSize = 13.sp) },
                                )
                            }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Transfer history
            Card(modifier = Modifier.fillMaxWidth(), onClick = onNavigateToHistory) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("传输历史", fontWeight = FontWeight.Medium)
                        Text(
                            "查看以往的同步记录",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("→", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Re-show onboarding
            Card(modifier = Modifier.fillMaxWidth(), onClick = onNavigateToOnboarding) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("查看引导", fontWeight = FontWeight.Medium)
                        Text(
                            "重新查看使用指南",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("→", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
