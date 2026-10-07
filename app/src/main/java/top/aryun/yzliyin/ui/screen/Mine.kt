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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
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
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SectionHeader

/**
 * 「我的」：三个数据源各一张账号卡片，登录后展示该源的「我喜欢」与歌单/收藏夹
 * （网易云为我的歌单、酷狗为用户歌单、B 站为创建的与订阅的收藏夹）。
 */
@Composable
fun MineScreen(nav: NavController) {
    val store = AppGraph.store
    val scope = rememberCoroutineScope()

    var loggedIn by remember { mutableStateOf(store.isNeteaseLoggedIn) }
    var kugouLoggedIn by remember { mutableStateOf(store.isLoggedIn(Source.KUGOU)) }
    var biliLoggedIn by remember { mutableStateOf(store.isLoggedIn(Source.BILIBILI)) }

    var neteasePlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var kugouLikes by remember { mutableStateOf<Playlist?>(null) }
    var kugouPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var kugouName by remember { mutableStateOf(store.displayName(Source.KUGOU)) }
    var kugouAvatar by remember { mutableStateOf(store.avatar(Source.KUGOU)) }
    var biliLikes by remember { mutableStateOf<Playlist?>(null) }
    var biliCreated by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var biliCollected by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var biliName by remember { mutableStateOf(store.displayName(Source.BILIBILI)) }
    var biliAvatar by remember { mutableStateOf(store.avatar(Source.BILIBILI)) }

    var tip by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    suspend fun reload() {
        loggedIn = store.isNeteaseLoggedIn
        kugouLoggedIn = store.isLoggedIn(Source.KUGOU)
        biliLoggedIn = store.isLoggedIn(Source.BILIBILI)

        if (loggedIn) {
            val mine = runCatching { NeteaseApi.userPlaylists() }.getOrDefault(emptyList())
            if (mine.isNotEmpty()) {
                neteasePlaylists = mine
                tip = null
            } else {
                tip = "暂未取到你的歌单，已展示推荐歌单"
                neteasePlaylists = runCatching { NeteaseApi.recommendedPlaylists(12) }
                    .getOrDefault(emptyList())
            }
        } else {
            tip = "登录网易云账号即可同步你的歌单"
            neteasePlaylists = runCatching { NeteaseApi.recommendedPlaylists(12) }
                .getOrDefault(emptyList())
        }

        if (kugouLoggedIn) {
            // 昵称与头像需要单独拉，扫码响应里没有；拉不到就沿用已存的值
            KugouApi.refreshProfile()
            kugouName = store.displayName(Source.KUGOU)
            kugouAvatar = store.avatar(Source.KUGOU)
            kugouLikes = KugouApi.likesPlaylist()
            kugouPlaylists = KugouApi.userPlaylists().filterNot { KugouApi.isLikesName(it.name) }
        } else {
            kugouLikes = null
            kugouPlaylists = emptyList()
        }

        if (biliLoggedIn) {
            BiliApi.refreshProfile()
            biliName = store.displayName(Source.BILIBILI)
            biliAvatar = store.avatar(Source.BILIBILI)
            biliLikes = BiliApi.likesPlaylist()
            val created = BiliApi.createdFolders()
            biliCreated = created.filterNot { it.id == biliLikes?.id }
            biliCollected = BiliApi.collectedFolders()
        } else {
            biliLikes = null
            biliCreated = emptyList()
            biliCollected = emptyList()
        }
    }

    LaunchedEffect(refreshKey) { reload() }

    // 每次进入/回到前台刷新一次登录态、资料与各源列表
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
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

        // ===== 网易云音乐 =====
        item {
            AccountCard(
                title = "网易云音乐",
                avatar = store.neteaseAvatar.takeIf { loggedIn && it.isNotEmpty() },
                name = store.neteaseName.takeIf { loggedIn && it.isNotEmpty() },
                loggedIn = loggedIn,
                statusText = {
                    if (loggedIn) {
                        buildString {
                            append(store.neteaseName.ifBlank { "已登录" })
                            if (store.neteaseVip) append(" · 黑胶VIP") else append(" · 网易云账号")
                        }
                    } else "未登录（登录后可同步歌单、播放会员歌曲）"
                },
                actions = {
                    if (loggedIn) {
                        OutlinedButton(onClick = {
                            NeteaseApi.logout()
                            loggedIn = false
                            neteasePlaylists = emptyList()
                            refreshKey++
                            nav.navigate(Routes.login(Routes.LOGIN_NETEASE_WEB)) { launchSingleTop = true }
                        }) { Text("切换账号") }
                        TextButton(onClick = {
                            NeteaseApi.logout()
                            loggedIn = false
                            neteasePlaylists = emptyList()
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

        // ===== 网易云歌单（紧跟在网易云账号卡片之后） =====
        item {
            SectionHeader(
                title = if (loggedIn) "我的歌单" else "推荐歌单",
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

        if (neteasePlaylists.isEmpty()) {
            item {
                Text(
                    "加载中…",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(neteasePlaylists.chunked(2), key = { it.first().key }) { pair ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pair.forEach { p ->
                    PlaylistCard(p, Modifier.weight(1f)) { nav.navigate(Routes.playlist(p.source, p.id)) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // ===== 酷狗音乐 =====
        item {
            AccountCard(
                title = "酷狗音乐",
                avatar = kugouAvatar.takeIf { kugouLoggedIn && it.isNotEmpty() },
                name = kugouName.takeIf { kugouLoggedIn && it.isNotEmpty() },
                loggedIn = kugouLoggedIn,
                statusText = {
                    if (kugouLoggedIn) {
                        kugouName.ifBlank { "已登录" } + " · 酷狗账号"
                    } else "未登录（登录后可同步「我喜欢」与歌单）"
                },
                actions = {
                    if (kugouLoggedIn) {
                        TextButton(onClick = {
                            KugouApi.logout()
                            kugouLoggedIn = false
                            kugouName = ""
                            kugouAvatar = ""
                            kugouLikes = null
                            kugouPlaylists = emptyList()
                        }) { Text("退出", color = MaterialTheme.colorScheme.error) }
                    } else {
                        FilledTonalButton(onClick = {
                            nav.navigate(Routes.login(Routes.LOGIN_KUGOU_QR)) { launchSingleTop = true }
                        }) { Text("扫码登录") }
                    }
                },
            )
        }
        if (kugouLoggedIn) {
            likesEntry(kugouLikes, Source.KUGOU, nav)
            playlistGroup("我的歌单", kugouPlaylists, nav)
        }

        // ===== 哔哩哔哩 =====
        item {
            AccountCard(
                title = "哔哩哔哩",
                avatar = biliAvatar.takeIf { biliLoggedIn && it.isNotEmpty() },
                name = biliName.takeIf { biliLoggedIn && it.isNotEmpty() },
                loggedIn = biliLoggedIn,
                statusText = {
                    if (biliLoggedIn) {
                        biliName.ifBlank { "已登录" } + " · 播放视频的音频轨"
                    } else "未登录（登录后可同步收藏夹与「我喜欢」）"
                },
                actions = {
                    if (biliLoggedIn) {
                        TextButton(onClick = {
                            BiliApi.logout()
                            biliLoggedIn = false
                            biliName = ""
                            biliAvatar = ""
                            biliLikes = null
                            biliCreated = emptyList()
                            biliCollected = emptyList()
                        }) { Text("退出", color = MaterialTheme.colorScheme.error) }
                    } else {
                        FilledTonalButton(onClick = {
                            nav.navigate(Routes.login(Routes.LOGIN_BILIBILI_QR)) { launchSingleTop = true }
                        }) { Text("扫码登录") }
                    }
                },
            )
        }
        if (biliLoggedIn) {
            likesEntry(biliLikes, Source.BILIBILI, nav)
            playlistGroup("创建的收藏夹", biliCreated, nav)
            playlistGroup("订阅的收藏夹", biliCollected, nav)
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

/** 「我喜欢」入口；该源没有对应列表时给出说明而不是留空。 */
private fun LazyListScope.likesEntry(
    likes: Playlist?,
    source: Source,
    nav: NavController,
) {
    item {
        SectionHeader(title = "我喜欢")
        ListItem(
            headlineContent = { Text(likes?.name ?: "我喜欢") },
            supportingContent = {
                Text(
                    when {
                        likes == null && source == Source.KUGOU -> "未在酷狗账号里找到「我喜欢」歌单"
                        likes == null -> "未找到可用的收藏夹"
                        likes.songCount > 0 -> "${likes.songCount} 首"
                        else -> source.label
                    },
                )
            },
            leadingContent = {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = likes != null) {
                    likes?.let { nav.navigate(Routes.playlist(it.source, it.id)) }
                },
        )
    }
}

private fun LazyListScope.playlistGroup(
    title: String,
    playlists: List<Playlist>,
    nav: NavController,
) {
    if (playlists.isEmpty()) return
    item { SectionHeader(title = title) }
    items(playlists.chunked(2), key = { it.first().key }) { pair ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pair.forEach { p ->
                PlaylistCard(p, Modifier.weight(1f)) { nav.navigate(Routes.playlist(p.source, p.id)) }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
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
