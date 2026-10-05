package top.aryun.yzliyin.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes

private data class Quality(val id: String, val label: String)

private val qualities = listOf(
    Quality("standard", "标准"),
    Quality("higher", "较高"),
    Quality("exhigh", "极高"),
    Quality("lossless", "无损"),
    Quality("hires", "Hi·Res"),
)

/**
 * 设置页（网易云风格）：账号 + 音质 + 播放行为 + 缓存 + 关于。
 * 已移除 API 地址、音源切换与「自动转B站」开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val store = AppGraph.store
    val scope = rememberCoroutineScope()

    var tip by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableStateOf(store.playQuality) }
    var autoPlay by remember { mutableStateOf(store.autoPlayNext) }
    var lyricScroll by remember { mutableStateOf(store.lyricAutoScroll) }
    var effect by remember { mutableStateOf(store.audioEffect) }
    var cacheSize by remember { mutableStateOf("") }

    fun refreshCache() {
        cacheSize = formatSize(context.cacheDir?.let { dir ->
            runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        } ?: 0L)
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refreshCache() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text("设置", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        // ===== 账号 =====
        SectionTitle("账号")
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("网易云音乐", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (store.isNeteaseLoggedIn) {
                                buildString {
                                    append("已登录")
                                    if (store.neteaseName.isNotEmpty()) append(" · ${store.neteaseName}")
                                    if (store.neteaseVip) append(" · 黑胶VIP")
                                }
                            } else "未登录（登录后可同步歌单、播放会员歌曲）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        nav.navigate(Routes.login(Routes.LOGIN_NETEASE_WEB)) { launchSingleTop = true }
                    }) {
                        Text(if (store.isNeteaseLoggedIn) "重新登录" else "去登录")
                    }
                }
                if (store.isNeteaseLoggedIn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            NeteaseApi.logout()
                            tip = "已退出网易云账号"
                        }) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = {
                            nav.navigate(Routes.login(Routes.LOGIN_NETEASE_QR)) { launchSingleTop = true }
                        }) { Text("扫码登录") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            nav.navigate(Routes.login(Routes.LOGIN_NETEASE_CAPTCHA)) { launchSingleTop = true }
                        }) { Text("验证码登录") }
                        TextButton(onClick = {
                            nav.navigate(Routes.login(Routes.LOGIN_NETEASE_QR)) { launchSingleTop = true }
                        }) { Text("扫码登录") }
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))

        // ===== 播放音质 =====
        SectionTitle("播放音质")
        Text(
            "会员歌曲按账号权益下发，非会员自动降到可用音质",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            qualities.forEach { q ->
                FilterChip(
                    selected = quality == q.id,
                    onClick = {
                        if (quality == q.id) return@FilterChip
                        quality = q.id
                        store.playQuality = q.id
                        tip = "音质已切换为${q.label}，正在重新取链"
                        scope.launch {
                            runCatching { PlayerController.reapplyQuality() }
                            tip = "音质已切换为${q.label}"
                        }
                    },
                    label = { Text(q.label) },
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))

        // ===== 播放行为 =====
        SectionTitle("播放行为")
        SwitchRow(
            title = "自动连播",
            desc = "关闭后，当前列表播完将暂停在最后一首",
            checked = autoPlay,
            onCheckedChange = {
                autoPlay = it
                store.autoPlayNext = it
            },
        )
        SwitchRow(
            title = "歌词自动滚动",
            desc = "播放时歌词跟随进度自动滚动",
            checked = lyricScroll,
            onCheckedChange = {
                lyricScroll = it
                store.lyricAutoScroll = it
            },
        )
        SwitchRow(
            title = "低频增强音效",
            desc = "开启后对低频做增益（部分机型可能无效）",
            checked = effect,
            onCheckedChange = {
                effect = it
                store.audioEffect = it
                tip = if (it) "音效已开启，播放中生效" else "音效已关闭"
            },
        )

        HorizontalDivider(Modifier.padding(vertical = 14.dp))

        // ===== 缓存 =====
        SectionTitle("缓存")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("图片与数据缓存", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "当前占用 $cacheSize",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                runCatching {
                    context.cacheDir?.deleteRecursively()
                    refreshCache()
                }
                tip = "缓存已清除"
            }) { Text("清除缓存") }
        }

        tip?.let {
            Text(
                it,
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp))

        // ===== 关于 =====
        SectionTitle("关于")
        InfoRow("应用", "音之理音 1.0.0")
        InfoRow("包名", "top.aryun.yzliyin")
        InfoRow("数据源", "网易云音乐官方接口")
        InfoRow("技术栈", "Jetpack Compose · Material 3 · Media3")
        InfoRow("开源许可", "参考项目 NeriPlayer（GPLv3），保留原版权声明")

        Spacer(Modifier.height(180.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            k,
            Modifier.width(76.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(v, style = MaterialTheme.typography.bodySmall)
    }
}
