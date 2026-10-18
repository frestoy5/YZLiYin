package top.aryun.yzliyin.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.AudioCache
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SectionHeader
import top.aryun.yzliyin.ui.components.SongRow

/**
 * 曲库：把各数据源的歌单/收藏夹、最近播放与本地音频聚到一页，
 * 顶部按标签切换（仿 NeriPlayer 的 Library）。
 */
@Composable
fun LibraryScreen(nav: NavController) {
    val store = AppGraph.store
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val playState by PlayerController.state.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(LibraryTab.RECENT) }
    var refreshKey by remember { mutableStateOf(0) }

    // 最近播放 / 本地音频：只读本地存储，进页即取
    var recent by remember { mutableStateOf(store.readRecentSongs()) }
    var local by remember { mutableStateOf<List<Song>>(emptyList()) }
    var localBytes by remember { mutableStateOf(0L) }
    var localDropped by remember { mutableStateOf(0) }

    // 各源歌单/收藏：只在切到对应标签时才拉，互不清空
    var neteasePlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var neteaseTip by remember { mutableStateOf<String?>(null) }
    var kugouLikes by remember { mutableStateOf<Playlist?>(null) }
    var kugouPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var biliLikes by remember { mutableStateOf<Playlist?>(null) }
    var biliCreated by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var biliCollected by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    /** 重新核对本地音频索引：文件已被淘汰的条目从索引里剔掉。 */
    fun refreshLocal() {
        val indexed = store.readDownloadedSongs()
        val stale = indexed.filterNot { AudioCache.cached(it.key) != null }
        stale.forEach { store.removeDownloadedSong(it.key) }
        local = indexed - stale.toSet()
        localBytes = AudioCache.totalBytes()
        localDropped = stale.size
    }

    suspend fun loadSource() {
        when (tab) {
            LibraryTab.NETEASE -> {
                loading = true
                if (store.isNeteaseLoggedIn) {
                    val mine = runCatching { NeteaseApi.userPlaylists() }.getOrDefault(emptyList())
                    if (mine.isNotEmpty()) {
                        neteasePlaylists = mine
                        neteaseTip = null
                    } else {
                        neteasePlaylists =
                            runCatching { NeteaseApi.recommendedPlaylists(12) }.getOrDefault(emptyList())
                        neteaseTip = if (neteasePlaylists.isEmpty()) {
                            "网络不可达，暂无本地歌单缓存"
                        } else {
                            "暂未取到你的歌单，已展示推荐歌单"
                        }
                    }
                } else {
                    neteaseTip = "登录网易云账号即可同步你的歌单"
                    neteasePlaylists =
                        runCatching { NeteaseApi.recommendedPlaylists(12) }.getOrDefault(emptyList())
                }
                loading = false
            }

            LibraryTab.KUGOU -> {
                loading = true
                if (store.isLoggedIn(Source.KUGOU)) {
                    KugouApi.refreshProfile()
                    kugouLikes = KugouApi.likesPlaylist()
                    kugouPlaylists =
                        KugouApi.userPlaylists().filterNot { KugouApi.isLikesName(it.name) }
                } else {
                    kugouLikes = null
                    kugouPlaylists = emptyList()
                }
                loading = false
            }

            LibraryTab.BILIBILI -> {
                loading = true
                if (store.isLoggedIn(Source.BILIBILI)) {
                    BiliApi.refreshProfile()
                    biliLikes = BiliApi.likesPlaylist()
                    biliCreated = BiliApi.createdFolders().filterNot { it.id == biliLikes?.id }
                    biliCollected = BiliApi.collectedFolders()
                } else {
                    biliLikes = null
                    biliCreated = emptyList()
                    biliCollected = emptyList()
                }
                loading = false
            }

            else -> Unit
        }
    }

    LaunchedEffect(tab, refreshKey) {
        when (tab) {
            LibraryTab.RECENT -> recent = store.readRecentSongs()
            LibraryTab.LOCAL -> refreshLocal()
            else -> loadSource()
        }
    }

    fun play(list: List<Song>, index: Int) {
        if (list.isEmpty()) return
        scope.launch { PlayerController.playQueue(context, list, index) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("曲库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        LibraryTabBar(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        when (tab) {
            LibraryTab.RECENT -> SongPane(
                songs = recent,
                emptyText = "还没有播放记录，去首页或搜索听几首吧",
                playState = playState,
                onPlayAll = { play(recent, 0) },
                onPlayAt = { play(recent, it) },
            )

            LibraryTab.LOCAL -> LocalPane(
                songs = local,
                bytes = localBytes,
                dropped = localDropped,
                emptyText = "本地还没有音频，播放过的曲目会自动缓存，也可以在歌单里手动下载",
                playState = playState,
                onPlayAll = { play(local, 0) },
                onPlayAt = { play(local, it) },
                onClear = {
                    local.forEach { AudioCache.remove(it.key) }
                    store.readDownloadedSongs().forEach { store.removeDownloadedSong(it.key) }
                    refreshLocal()
                },
                onRemove = { song ->
                    AudioCache.remove(song.key)
                    store.removeDownloadedSong(song.key)
                    refreshLocal()
                },
            )

            LibraryTab.NETEASE -> SourcePane(
                loading = loading,
                tip = neteaseTip,
                loggedIn = store.isNeteaseLoggedIn,
                loginRoute = Routes.login(Routes.LOGIN_NETEASE_WEB),
                nav = nav,
                empty = neteasePlaylists.isEmpty(),
                emptyText = if (store.isNeteaseLoggedIn) "还没有歌单" else "登录后同步你的歌单",
                headerTitle = if (store.isNeteaseLoggedIn) "我的歌单" else "推荐歌单",
                content = {
                    items(neteasePlaylists.chunked(2), key = { it.first().key }) { pair ->
                        PlaylistGrid(pair, nav)
                    }
                },
            )

            LibraryTab.KUGOU -> SourcePane(
                loading = loading,
                tip = null,
                loggedIn = store.isLoggedIn(Source.KUGOU),
                loginRoute = Routes.login(Routes.LOGIN_KUGOU_QR),
                nav = nav,
                empty = kugouLikes == null && kugouPlaylists.isEmpty(),
                emptyText = "登录酷狗账号后同步「我喜欢」与歌单",
                headerTitle = "我的歌单",
                content = {
                    if (kugouLikes != null) likesEntry(kugouLikes, Source.KUGOU, nav)
                    if (kugouPlaylists.isNotEmpty()) {
                        item { SectionHeader(title = "我的歌单") }
                        items(kugouPlaylists.chunked(2), key = { it.first().key }) { pair ->
                            PlaylistGrid(pair, nav)
                        }
                    }
                },
            )

            LibraryTab.BILIBILI -> SourcePane(
                loading = loading,
                tip = null,
                loggedIn = store.isLoggedIn(Source.BILIBILI),
                loginRoute = Routes.login(Routes.LOGIN_BILIBILI_QR),
                nav = nav,
                empty = biliLikes == null && biliCreated.isEmpty() && biliCollected.isEmpty(),
                emptyText = "登录 B 站账号后同步收藏夹",
                headerTitle = "收藏夹",
                content = {
                    if (biliLikes != null) likesEntry(biliLikes, Source.BILIBILI, nav)
                    if (biliCreated.isNotEmpty()) {
                        item { SectionHeader(title = "创建的收藏夹") }
                        items(biliCreated.chunked(2), key = { it.first().key }) { pair ->
                            PlaylistGrid(pair, nav)
                        }
                    }
                    if (biliCollected.isNotEmpty()) {
                        item { SectionHeader(title = "订阅的收藏夹") }
                        items(biliCollected.chunked(2), key = { it.first().key }) { pair ->
                            PlaylistGrid(pair, nav)
                        }
                    }
                },
            )
        }
    }
}

/** 曲库子标签。 */
private enum class LibraryTab(val label: String) {
    RECENT("最近"),
    LOCAL("本地"),
    NETEASE("网易云"),
    KUGOU("酷狗"),
    BILIBILI("B站"),
}

/** 顶部一排胶囊标签（NeriPlayer 的 Library 用的也是这种 20dp 圆角分段控件）。 */
@Composable
private fun LibraryTabBar(
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryTab.entries.forEach { t ->
            val isOn = t == selected
            Text(
                t.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isOn) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isOn) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .clickable { onSelect(t) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** 歌单两列网格（与「我的」页同一套卡片）。 */
@Composable
private fun PlaylistGrid(pair: List<Playlist>, nav: NavController) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        pair.forEach { p ->
            PlaylistCard(p, Modifier.weight(1f)) {
                nav.navigate(Routes.playlist(p.source, p.id))
            }
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}

/** 通用歌曲列表页：「播放全部」+ 行点击播放。 */
@Composable
private fun SongPane(
    songs: List<Song>,
    emptyText: String,
    playState: top.aryun.yzliyin.player.PlayState,
    onPlayAll: () -> Unit,
    onPlayAt: (Int) -> Unit,
    headerContent: (@Composable () -> Unit)? = null,
    songTrailing: (@Composable (Song) -> Unit)? = null,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 220.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${songs.size} 首",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onPlayAll, enabled = songs.isNotEmpty()) {
                    Text("播放全部")
                }
            }
        }
        item { headerContent?.invoke() }
        if (songs.isEmpty()) {
            item { EmptyTip(emptyText) }
        }
        items(songs, key = { it.key }) { song ->
            val index = songs.indexOf(song)
            val emit: (@Composable () -> Unit)? = songTrailing?.let { holder -> { holder(song) } }
            SongRow(
                song = song,
                index = index + 1,
                playing = playState.current?.key == song.key,
                onClick = { onPlayAt(index) },
                trailing = emit,
            )
        }
    }
}

/** 「本地」页：在通用列表上加占用统计与缓存管理。 */
@Composable
private fun LocalPane(
    songs: List<Song>,
    bytes: Long,
    dropped: Int,
    emptyText: String,
    playState: top.aryun.yzliyin.player.PlayState,
    onPlayAll: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onClear: () -> Unit,
    onRemove: (Song) -> Unit,
) {
    SongPane(
        songs = songs,
        emptyText = emptyText,
        playState = playState,
        onPlayAll = onPlayAll,
        onPlayAt = onPlayAt,
        songTrailing = { song ->
            IconButton(onClick = { onRemove(song) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "删除本地缓存",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        headerContent = {
            Column {
                SectionHeader(
                    title = "已缓存 ${songs.size} 首 · ${formatBytes(bytes)}",
                    action = if (songs.isEmpty()) null else "清空",
                    onAction = if (songs.isEmpty()) null else onClear,
                )
                if (dropped > 0) {
                    Text(
                        "已清理 $dropped 条失效记录（文件被缓存淘汰）",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** 各源歌单标签页：未登录给登录入口，加载中给转圈，有数据就铺歌单网格。 */
@Composable
private fun SourcePane(
    loading: Boolean,
    tip: String?,
    loggedIn: Boolean,
    loginRoute: String,
    nav: NavController,
    empty: Boolean,
    emptyText: String,
    headerTitle: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 220.dp),
    ) {
        if (!loggedIn) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton(
                        onClick = { nav.navigate(loginRoute) { launchSingleTop = true } },
                    ) {
                        Text("去登录")
                    }
                }
            }
        }
        if (loading) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        tip?.let { message ->
            item {
                Text(
                    message,
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (loading) return@LazyColumn
        when {
            empty && !loggedIn -> Unit
            empty -> item { EmptyTip(if (loggedIn) emptyText else "") }
            else -> {
                if (headerTitle.isNotEmpty()) item { SectionHeader(title = headerTitle) }
                content()
            }
        }
    }
}

/** 空态提示。 */
@Composable
private fun EmptyTip(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 32.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 字节数 → 人类可读。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
