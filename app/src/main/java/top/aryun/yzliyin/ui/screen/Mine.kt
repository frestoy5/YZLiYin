package top.aryun.yzliyin.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SectionHeader

/**
 * 「我的」：网易云账号卡片 + **我的歌单**（登录后同步用户歌单；
 * 未登录时展示推荐歌单并提示登录）。
 */
@Composable
fun MineScreen(nav: NavController) {
    val store = AppGraph.store
    val scope = rememberCoroutineScope()

    var neteaseLoggedIn by remember { mutableStateOf(store.isNeteaseLoggedIn) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var tip by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    suspend fun reload() {
        neteaseLoggedIn = store.isNeteaseLoggedIn
        val logged = store.isNeteaseLoggedIn
        if (logged) {
            val mine = runCatching { NeteaseApi.userPlaylists() }.getOrDefault(emptyList())
            if (mine.isNotEmpty()) {
                playlists = mine
                tip = null
                return
            }
            tip = "暂未取到你的歌单，已展示推荐歌单"
        } else {
            tip = "登录网易云账号即可同步你的歌单"
        }
        playlists = runCatching { NeteaseApi.recommendedPlaylists(12) }.getOrDefault(emptyList())
    }

    LaunchedEffect(refreshKey) { reload() }

    // 每次进入/回到前台刷新一次登录态、资料与歌单
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                neteaseLoggedIn = store.isNeteaseLoggedIn
                if (store.isNeteaseLoggedIn) {
                    scope.launch { runCatching { NeteaseApi.refreshAccount() } }
                }
                scope.launch { reload() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 220.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            }
        }

        // ===== 网易云音乐账号 =====
        item {
            AccountCard(
                title = "网易云音乐",
                avatar = store.neteaseAvatar.takeIf { neteaseLoggedIn && it.isNotEmpty() },
                name = store.neteaseName.takeIf { neteaseLoggedIn && it.isNotEmpty() },
                loggedIn = neteaseLoggedIn,
                statusText = {
                    if (neteaseLoggedIn) {
                        buildString {
                            append(store.neteaseName.ifBlank { "已登录" })
                            if (store.neteaseVip) append(" · 黑胶VIP") else append(" · 网易云账号")
                        }
                    } else "未登录（登录后可同步歌单、播放会员歌曲）"
                },
                actions = {
                    if (neteaseLoggedIn) {
                        OutlinedButton(onClick = {
                            NeteaseApi.logout()
                            neteaseLoggedIn = false
                            playlists = emptyList()
                            refreshKey++
                            nav.navigate(Routes.login(Routes.LOGIN_NETEASE_WEB)) { launchSingleTop = true }
                        }) { Text("切换账号") }
                        TextButton(onClick = {
                            NeteaseApi.logout()
                            neteaseLoggedIn = false
                            playlists = emptyList()
                            refreshKey++
                        }) { Text("退出", color = MaterialTheme.colorScheme.error) }
                    } else {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = {
                                    nav.navigate(Routes.login(Routes.LOGIN_NETEASE_WEB)) { launchSingleTop = true }
                                }) { Text("网页登录") }
                                OutlinedButton(onClick = {
                                    nav.navigate(Routes.login(Routes.LOGIN_NETEASE_CAPTCHA)) { launchSingleTop = true }
                                }) { Text("验证码") }
                            }
                            TextButton(onClick = {
                                nav.navigate(Routes.login(Routes.LOGIN_NETEASE_QR)) { launchSingleTop = true }
                            }) { Text("扫码登录") }
                        }
                    }
                },
            )
        }

        // ===== 我的歌单 =====
        item {
            SectionHeader(
                title = if (neteaseLoggedIn) "我的歌单" else "推荐歌单",
                action = "刷新",
                onAction = { refreshKey++ },
            )
            tip?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (playlists.isEmpty()) {
            item {
                Text(
                    "加载中…",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(playlists.chunked(2), key = { it.first().id }) { pair ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pair.forEach { p ->
                    PlaylistCard(p, Modifier.weight(1f)) { nav.navigate(Routes.playlist(p.id)) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        item {
            ListItem(
                headlineContent = { Text("设置") },
                leadingContent = { Icon(Icons.Filled.Settings, contentDescription = null) },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { nav.navigate(Routes.SETTINGS) },
            )
        }
    }
}

@Composable
private fun PlaylistCard(p: Playlist, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = p.cover,
                contentDescription = p.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            p.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (p.songCount > 0) "${p.songCount} 首" else p.author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AccountCard(
    title: String,
    avatar: String?,
    name: String?,
    loggedIn: Boolean,
    statusText: () -> String,
    actions: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avatar != null) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title.take(1),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (loggedIn && name != null) name else title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        statusText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}
