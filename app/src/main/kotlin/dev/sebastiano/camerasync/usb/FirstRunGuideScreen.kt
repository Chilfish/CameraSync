package dev.sebastiano.camerasync.usb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.camerasync.R
import dev.sebastiano.camerasync.ui.theme.CameraSyncTheme

/**
 * First-run one-screen guide explaining the USB MTP flow: switch the camera to MTP/PTP, grant USB
 * access, then plug in to sync. Shown once on cold start and re-openable from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstRunGuideScreen(onNavigateBack: () -> Unit, onDone: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.guide_topbar_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back_24dp), "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))

            Text("📷", fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.guide_headline),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.guide_subtitle),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GuideStep(
                    step = "①",
                    title = stringResource(R.string.guide_step1_title),
                    description = stringResource(R.string.guide_step1_desc),
                )
                GuideStep(
                    step = "②",
                    title = stringResource(R.string.guide_step2_title),
                    description = stringResource(R.string.guide_step2_desc),
                )
                GuideStep(
                    step = "③",
                    title = stringResource(R.string.guide_step3_title),
                    description = stringResource(R.string.guide_step3_desc),
                )
            }

            Spacer(Modifier.weight(1f))

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                Text(stringResource(R.string.guide_cta), fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun GuideStep(step: String, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(step, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(name = "First Run Guide", showBackground = true)
@Composable
private fun FirstRunGuidePreview() {
    CameraSyncTheme { FirstRunGuideScreen(onNavigateBack = {}, onDone = {}) }
}
