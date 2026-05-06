package dev.sebastiano.camerasync.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastiano.camerasync.R
import kotlinx.coroutines.launch

data class OnboardingPage(
    val icon: Int,
    val title: String,
    val description: String,
)

val onboardingPages = listOf(
    OnboardingPage(
        icon = R.drawable.ic_launcher_foreground,
        title = "连接你的尼康相机",
        description = "使用 USB-C 数据线连接相机和手机\n请将相机设置为 MTP/PTP 模式",
    ),
    OnboardingPage(
        icon = R.drawable.ic_launcher_foreground,
        title = "浏览与选择",
        description = "在手机上直接浏览相机中的照片\n支持 RAW+JPEG 分组显示\n长按即可多选",
    ),
    OnboardingPage(
        icon = R.drawable.ic_launcher_foreground,
        title = "一键传输",
        description = "选择照片后轻点传输\n自动保存到手机相册\n已传过的照片不会重复导入",
    ),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        // Skip button
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) {
                Text("跳过", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.weight(0.5f))

        // Pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(2f),
        ) { page ->
            val data = onboardingPages[page]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(
                    painter = painterResource(data.icon),
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    data.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    data.description,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                )
            }
        }

        // Page indicators
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(onboardingPages.size) { index ->
                val isSelected = pagerState.currentPage == index
                val color by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(if (isSelected) 24.dp else 8.dp, 8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }

        Spacer(Modifier.weight(0.5f))

        // Bottom button
        val isLastPage = pagerState.currentPage == onboardingPages.size - 1
        Button(
            onClick = {
                if (isLastPage) onDone()
                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                if (isLastPage) "开始使用" else "下一步",
                fontSize = 16.sp,
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
