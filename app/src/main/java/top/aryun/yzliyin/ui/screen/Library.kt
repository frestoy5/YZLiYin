package top.aryun.yzliyin.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.AudioCache
import top.aryun.yzliyin.player.LocalScan
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.player.downloadSong
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
    //「已缓存 N 首」只数真归我们管的缓存；扫描进来的本机音乐不占字节，另算
    var localCachedCount by remember { mutableStateOf(0) }
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

    /**
     * 重新核对本地音频索引：文件已被淘汰的条目从索引里剔掉。
     * 扫描进来的本机音乐另外核对一遍 media id —— 用户在文件管理器里删掉的原文件要从列表摘掉。
     */
    fun refreshLocal() {
        // 先让缓存按目录实际内容重新列一遍，被文件管理器删掉的文件才不会算成「还缓存着」
        AudioCache.refresh()
        val indexed = store.readDownloadedSongs()
        val stale = indexed.filterNot { AudioCache.cachedSource(it.key) != null }
        stale.forEach { store.removeDownloadedSong(it.key) }
        val kept = indexed - stale.toSet()

        // aliveIds 返回 null 表示**查不了**（没给权限 / 查询失败）：这时原样保留，
        // 免得用户一收回权限，整份扫描清单就被当成失效一次性清空
        val scanned = store.readScannedSongs()
        val alive = LocalScan.aliveIds(context, scanned)
        if (alive == null) {
            local = kept + scanned
        } else {
            val keptScanned = scanned.filter { alive.contains(it.id) }
            if (keptScanned.size != scanned.size) store.writeScannedSongs(keptScanned)
            local = kept + keptScanned
        }
        localCachedCount = kept.size
        localBytes = AudioCache.totalBytes()
        localDropped = stale.size
    }

    // 「扫描歌曲」：先确认有权限，没给就走系统授权弹窗，拒绝了才提示原因
    var scanning by remember { mutableStateOf(false) }

    fun runScan() {
        if (scanning) return
        scanning = true
        scope.launch {
            val found = LocalScan.scan(context)
            scanning = false
            when {
                found == null -> Toast.makeText(context, R.string.lib_scan_denied, Toast.LENGTH_SHORT)
                    .show()

                found.isEmpty() -> Toast.makeText(context, R.string.lib_scan_empty, Toast.LENGTH_SHORT)
                    .show()

                else -> {
                    store.writeScannedSongs(found)
                    refreshLocal()
                    Toast.makeText(
                        context,
                        context.getString(R.string.lib_scan_done, found.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    val scanPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) runScan()
        else Toast.makeText(context, R.string.lib_scan_denied, Toast.LENGTH_SHORT).show()
    }

    // 「最近」的多选下载：长按任意一首进入多选，勾选后一次下多首
    var selecting by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var downloading by remember { mutableStateOf(false) }

    /** 是否已把「最近」里的每一首都勾上了（供「全选 / 取消全选」切换文案）。 */
    val allSelected = recent.isNotEmpty() && recent.all { it.key in selectedKeys }

    fun exitSelection() {
        selecting = false
        selectedKeys = emptySet()
    }

    fun toggleSelect(song: Song) {
        selectedKeys = if (song.key in selectedKeys) {
            selectedKeys - song.key
        } else {
            selectedKeys + song.key
        }
    }

    /** 把勾中的歌下载到本地：已缓存的跳过，其余并发下载（并发度与歌单页一致）。 */
    fun downloadSelected() {
        if (downloading) return
        val targets = recent.filter { it.key in selectedKeys }
        val skipped = targets.count { AudioCache.cachedSource(it.key) != null }
        val todo = targets.filter { AudioCache.cachedSource(it.key) == null }
        if (todo.isEmpty()) {
            Toast.makeText(context, R.string.lib_dl_exists, Toast.LENGTH_SHORT).show()
            return
        }
        downloading = true
        Toast.makeText(
            context,
            context.getString(R.string.lib_dl_batch_start, todo.size),
            Toast.LENGTH_SHORT,
        ).show()
        scope.launch {
            val results = coroutineScope {
                val gate = Semaphore(MAX_DOWNLOAD_PARALLEL)
                todo.map { song ->
                    async(Dispatchers.IO) { gate.withPermit { downloadSong(context, song) } }
                }.awaitAll()
            }
            downloading = false
            val failed = results.filterNotNull()
            val ok = results.size - failed.size
            Toast.makeText(
                context,
                if (failed.isEmpty()) {
                    context.getString(R.string.lib_dl_batch_done, ok + skipped)
                } else {
                    context.getString(R.string.lib_dl_batch_fail, ok, failed.size, failed.first())
                },
                Toast.LENGTH_SHORT,
            ).show()
            refreshLocal()
            exitSelection()
        }
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
                            context.getString(R.string.lib_netease_tip_offline)
                        } else {
                            context.getString(R.string.lib_netease_tip_fallback)
                        }
                    }
                } else {
                    neteaseTip = context.getString(R.string.lib_netease_tip_login)
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
            Text(stringResource(R.string.lib_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.lib_refresh))
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
                emptyText = stringResource(R.string.lib_recent_empty),
                playState = playState,
                onPlayAll = { play(recent, 0) },
                onPlayAt = { play(recent, it) },
                // 长按进多选（不再一长按就直接下载）：勾好之后点「下载」一次下多首
                selecting = selecting,
                selectedKeys = selectedKeys,
                onToggleSelect = ::toggleSelect,
                onSongLongClick = { song ->
                    // 未在多选时长按 = 进多选并选中这一首；已在多选时长按 = 勾 / 取消勾
                    if (!selecting) selecting = true
                    toggleSelect(song)
                },
                selectionBar = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.lib_selected_count, selectedKeys.size),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    selectedKeys = if (allSelected) emptySet() else recent.mapTo(mutableSetOf()) { it.key }
                                },
                                enabled = recent.isNotEmpty(),
                            ) {
                                Text(
                                    stringResource(
                                        if (allSelected) R.string.lib_select_clear else R.string.lib_select_all,
                                    ),
                                )
                            }
                            IconButton(onClick = { exitSelection() }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.lib_exit_selection),
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = { downloadSelected() },
                            enabled = selectedKeys.isNotEmpty() && !downloading,
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (downloading) R.string.lib_downloading
                                    else R.string.lib_dl_button_count,
                                    if (downloading) 0 else selectedKeys.size,
                                ),
                            )
                        }
                    }
                },
            )

            LibraryTab.LOCAL -> LocalPane(
                songs = local,
                cachedCount = localCachedCount,
                bytes = localBytes,
                dropped = localDropped,
                emptyText = stringResource(R.string.lib_local_empty),
                playState = playState,
                onPlayAll = { play(local, 0) },
                onPlayAt = { play(local, it) },
                scanning = scanning,
                onScan = {
                    if (LocalScan.hasPermission(context)) runScan()
                    else scanPermission.launch(LocalScan.requiredPermission())
                },
                onClear = {
                    local.forEach {
                        // 扫描进来的音乐只是记在清单里，清空本地只清缓存，不动用户自己的文件
                        if (it.source != Source.LOCAL) AudioCache.remove(it.key)
                    }
                    store.readDownloadedSongs().forEach { store.removeDownloadedSong(it.key) }
                    store.writeScannedSongs(emptyList())
                    refreshLocal()
                },
                onRemove = { song ->
                    if (song.source == Source.LOCAL) {
                        store.writeScannedSongs(
                            store.readScannedSongs().filterNot { it.key == song.key },
                        )
                    } else {
                        AudioCache.remove(song.key)
                        store.removeDownloadedSong(song.key)
                    }
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
                emptyText = stringResource(
                    if (store.isNeteaseLoggedIn) R.string.lib_netease_empty_in else R.string.lib_netease_empty_out,
                ),
                headerTitle = stringResource(
                    if (store.isNeteaseLoggedIn) R.string.lib_my_playlists else R.string.lib_recommended,
                ),
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
                emptyText = stringResource(R.string.lib_kugou_empty),
                headerTitle = stringResource(R.string.lib_my_playlists),
                content = {
                    if (kugouLikes != null) likesEntry(kugouLikes, Source.KUGOU, nav)
                    if (kugouPlaylists.isNotEmpty()) {
                        item { SectionHeader(title = stringResource(R.string.lib_my_playlists)) }
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
                emptyText = stringResource(R.string.lib_bili_empty),
                headerTitle = stringResource(R.string.lib_bili_folders),
                content = {
                    if (biliLikes != null) likesEntry(biliLikes, Source.BILIBILI, nav)
                    if (biliCreated.isNotEmpty()) {
                        item { SectionHeader(title = stringResource(R.string.lib_bili_created)) }
                        items(biliCreated.chunked(2), key = { it.first().key }) { pair ->
                            PlaylistGrid(pair, nav)
                        }
                    }
                    if (biliCollected.isNotEmpty()) {
                        item { SectionHeader(title = stringResource(R.string.lib_bili_subscribed)) }
                        items(biliCollected.chunked(2), key = { it.first().key }) { pair ->
                            PlaylistGrid(pair, nav)
                        }
                    }
                },
            )
        }
    }
}

/** 批量下载的并发度（与歌单详情页一致）。 */
private const val MAX_DOWNLOAD_PARALLEL = 3

/** 曲库子标签。 */
private enum class LibraryTab(@StringRes val label: Int) {
    RECENT(R.string.lib_tab_recent),
    LOCAL(R.string.lib_tab_local),
    NETEASE(R.string.lib_tab_netease),
    KUGOU(R.string.lib_tab_kugou),
    BILIBILI(R.string.lib_tab_bili),
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
                stringResource(t.label),
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

/** 通用歌曲列表页：「播放全部」+ 行点击播放，[onSongLongClick] 有值时长按会回调。
 *
 * [selecting] 打开后进入多选：行点击与长按都改成勾选，[selectionBar] 顶掉
 * 「播放全部」那一行，用来放全选 / 下载 / 退出。
 */
@Composable
private fun SongPane(
    songs: List<Song>,
    emptyText: String,
    playState: top.aryun.yzliyin.player.PlayState,
    onPlayAll: () -> Unit,
    onPlayAt: (Int) -> Unit,
    headerContent: (@Composable () -> Unit)? = null,
    songTrailing: (@Composable (Song) -> Unit)? = null,
    onSongLongClick: ((Song) -> Unit)? = null,
    selecting: Boolean = false,
    selectedKeys: Set<String> = emptySet(),
    onToggleSelect: (Song) -> Unit = {},
    selectionBar: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 220.dp),
    ) {
        item {
            if (selecting && selectionBar != null) {
                selectionBar()
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.lib_song_count, songs.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = onPlayAll, enabled = songs.isNotEmpty()) {
                        Text(stringResource(R.string.lib_play_all))
                    }
                }
            }
        }
        item { headerContent?.invoke() }
        if (songs.isEmpty()) {
            item { EmptyTip(emptyText) }
        }
        items(songs, key = { it.key }) { song ->
            val index = songs.indexOf(song)
            // 多选态下用勾选框占住尾部，别让删除 / 其它按钮混进来点错
            val emit: (@Composable () -> Unit)? = when {
                selecting -> {
                    {
                        Checkbox(
                            checked = song.key in selectedKeys,
                            onCheckedChange = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                songTrailing != null -> {{ songTrailing(song) }}
                else -> null
            }
            val longPress: (() -> Unit)? = onSongLongClick?.let { holder -> { holder(song) } }
            SongRow(
                song = song,
                index = index + 1,
                playing = !selecting && playState.current?.key == song.key,
                onClick = { if (selecting) onToggleSelect(song) else onPlayAt(index) },
                onLongClick = longPress,
                trailing = emit,
            )
        }
    }
}

/** 「本地」页：在通用列表上加占用统计与缓存管理，列表下方给出「扫描歌曲」入口。 */
@Composable
private fun LocalPane(
    songs: List<Song>,
    cachedCount: Int,
    bytes: Long,
    dropped: Int,
    emptyText: String,
    playState: top.aryun.yzliyin.player.PlayState,
    onPlayAll: () -> Unit,
    onPlayAt: (Int) -> Unit,
    scanning: Boolean,
    onScan: () -> Unit,
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
                    // 本机音乐删的只是「从列表里移除」，缓存曲删的是文件，文案分开写
                    Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(
                        if (song.source == Source.LOCAL) R.string.lib_remove_entry
                        else R.string.lib_remove_cache,
                    ),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        headerContent = {
            Column {
                SectionHeader(
                    // 只数真占磁盘的缓存；扫描进来的本机音乐不计入「已缓存」
                    title = stringResource(R.string.lib_cached_header, cachedCount, formatBytes(bytes)),
                    action = if (songs.isEmpty()) null else stringResource(R.string.lib_clear),
                    onAction = if (songs.isEmpty()) null else onClear,
                )
                if (dropped > 0) {
                    Text(
                        stringResource(R.string.lib_dropped, dropped),
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ScanEntry(scanning = scanning, onScan = onScan)
            }
        },
    )
}

/** 「本地」列表下方的「扫描歌曲」入口：点一下把手机里已有的音乐列进本地。 */
@Composable
private fun ScanEntry(scanning: Boolean, onScan: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = !scanning, onClick = onScan)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scanning) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Outlined.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (scanning) R.string.lib_scan_scanning else R.string.lib_scan),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.lib_scan_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
                        Text(stringResource(R.string.lib_go_login))
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
