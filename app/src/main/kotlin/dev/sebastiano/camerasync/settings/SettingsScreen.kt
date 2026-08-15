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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.camerasync.R
import dev.sebastiano.camerasync.usb.UsbSyncPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialGridColumns: Int,
    initialGrouping: UsbSyncPreferences.PhotoGrouping,
    initialSorting: UsbSyncPreferences.PhotoSorting,
    initialDownloadFormat: UsbSyncPreferences.DownloadFormat,
    initialThemeMode: String,
    onNavigateBack: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToOnboarding: () -> Unit,
    onGroupingChanged: (UsbSyncPreferences.PhotoGrouping) -> Unit = {},
    onSortingChanged: (UsbSyncPreferences.PhotoSorting) -> Unit = {},
    onDownloadFormatChanged: (UsbSyncPreferences.DownloadFormat) -> Unit = {},
    onGridColumnsChanged: (Int) -> Unit = {},
    onThemeModeChanged: (String) -> Unit = {},
) {
    var gridCols by remember { mutableIntStateOf(initialGridColumns) }
    var grouping by remember { mutableStateOf(initialGrouping) }
    var sorting by remember { mutableStateOf(initialSorting) }
    var downloadFormat by remember { mutableStateOf(initialDownloadFormat) }
    var themeMode by remember { mutableStateOf(initialThemeMode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back_24dp),
                            stringResource(R.string.content_desc_back),
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Theme (P4-3, R11): three-way selector wired to prefs + the root theme state.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_theme), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                                "system" to stringResource(R.string.settings_theme_system),
                                "light" to stringResource(R.string.settings_theme_light),
                                "dark" to stringResource(R.string.settings_theme_dark),
                            )
                            .forEach { (mode, label) ->
                                FilterChip(
                                    selected = themeMode == mode,
                                    onClick = {
                                        themeMode = mode
                                        onThemeModeChanged(mode)
                                    },
                                    label = { Text(label, fontSize = 13.sp) },
                                )
                            }
                    }
                }
            }

            // Grid density
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_grid_density),
                        fontWeight = FontWeight.Medium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 3, 4).forEach { cols ->
                            FilterChip(
                                selected = gridCols == cols,
                                onClick = {
                                    gridCols = cols
                                    onGridColumnsChanged(cols)
                                },
                                label = {
                                    Text(stringResource(R.string.settings_grid_columns_n, cols))
                                },
                            )
                        }
                    }
                }
            }

            // Photo grouping
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_grouping), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                                UsbSyncPreferences.PhotoGrouping.BY_FOLDER to
                                    stringResource(R.string.usb_grouping_folder),
                                UsbSyncPreferences.PhotoGrouping.BY_DATE to
                                    stringResource(R.string.usb_grouping_date),
                                UsbSyncPreferences.PhotoGrouping.FLAT to
                                    stringResource(R.string.usb_grouping_flat),
                            )
                            .forEach { (mode, label) ->
                                FilterChip(
                                    selected = grouping == mode,
                                    onClick = {
                                        grouping = mode
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
                    Text(stringResource(R.string.settings_sorting), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                                UsbSyncPreferences.PhotoSorting.DATE_DESC to
                                    stringResource(R.string.usb_sorting_newest),
                                UsbSyncPreferences.PhotoSorting.NAME_ASC to
                                    stringResource(R.string.usb_sorting_name),
                                UsbSyncPreferences.PhotoSorting.SIZE_DESC to
                                    stringResource(R.string.usb_sorting_size),
                            )
                            .forEach { (mode, label) ->
                                FilterChip(
                                    selected = sorting == mode,
                                    onClick = {
                                        sorting = mode
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
                    Text(
                        stringResource(R.string.settings_download_format),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.settings_download_format_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                                UsbSyncPreferences.DownloadFormat.ALL to
                                    stringResource(R.string.usb_filter_all),
                                UsbSyncPreferences.DownloadFormat.JPEG_ONLY to
                                    stringResource(R.string.settings_download_format_jpeg_only),
                                UsbSyncPreferences.DownloadFormat.RAW_ONLY to
                                    stringResource(R.string.settings_download_format_raw_only),
                            )
                            .forEach { (format, label) ->
                                FilterChip(
                                    selected = downloadFormat == format,
                                    onClick = {
                                        downloadFormat = format
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

            // Usage guide (first-run screen, re-openable here)
            Card(modifier = Modifier.fillMaxWidth(), onClick = onNavigateToOnboarding) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            stringResource(R.string.settings_guide),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.settings_guide_desc),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("→", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Transfer history
            Card(modifier = Modifier.fillMaxWidth(), onClick = onNavigateToHistory) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            stringResource(R.string.settings_history),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.settings_history_desc),
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
