package top.aryun.yzliyin.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.data.JsonCodecs
import top.aryun.yzliyin.core.model.Paged
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.AudioCache
import top.aryun.yzliyin.player.PlaybackResolver
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SongRow
import top.aryun.yzliyin.ui.components.formatCount

/**
 * 歌单/收藏夹详情，按 [source] 分派到对应数据源。
 * 只有网易云接入评论，其余源不显示评论入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(nav: NavController, id: String, source: Source = Source.NETEASE) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by PlayerController.state.collectAsStateWithLifecycle()

    var detail by remember { mutableStateOf<Playlist?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadDetail() {
        detail = when (source) {
            Source.NETEASE -> runCatching { NeteaseApi.playlistDetail(id) }.getOrNull()
            Source.KUGOU -> KugouApi.findPlaylist(id)
            Source.BILIBILI -> BiliApi.folderInfo(id)
        }
    }

    // 离线缓存：首次成功加载后存快照（元信息 + 首页曲目），断网时回退展示
    val cacheKey = "playlist_${source.id}_$id"

    fun saveOfflineCache(result: Paged<Song>) {
        runCatching {
            val json = JSONObject()
            detail?.let { json.put("detail", JsonCodecs.playlistToJson(it)) }
            json.put("songs", JsonCodecs.songsToJson(result.items))
            json.put("total", result.total)
            AppGraph.store.cachePut(cacheKey, json.toString())
        }
    }

    fun restoreOfflineCache(): Boolean {
        val text = AppGraph.store.cacheGet(cacheKey)
        if (text.isBlank()) return false
        return runCatching {
            val json = JSONObject(text)
            val cachedSongs = JsonCodecs.songsFromJson(json.optString("songs"))
            if (cachedSongs.isEmpty()) return false
            if (detail == null) detail = JsonCodecs.playlistFromJson(json.optJSONObject("detail"))
            songs = cachedSongs
            total = json.optInt("total", cachedSongs.size)
            hasMore = false
            error = null
            page = 1
            true
        }.getOrDefault(false)
    }

    suspend fun loadPage(p: Int) {
        loading = true
        val pageSize = if (source == Source.NETEASE) 50 else 100
        runCatching {
            when (source) {
                Source.NETEASE -> NeteaseApi.playlistTracks(id, page = p, pageSize = pageSize)
                Source.KUGOU -> KugouApi.playlistTracks(id, page = p, pageSize = pageSize)
                Source.BILIBILI -> BiliApi.folderTracks(id, page = p, pageSize = 20)
            }
        }
            .onSuccess { result: Paged<Song> ->
                songs = if (p == 1) result.items else songs + result.items
                total = result.total
                hasMore = result.hasMore
                page = p
                error = null
                if (p == 1) {
                    if (result.items.isEmpty()) restoreOfflineCache() else saveOfflineCache(result)
                }
            }
            .onFailure {
                if (!(p == 1 && restoreOfflineCache())) error = it.message ?: "加载歌单失败"
            }
        loading = false
    }

    LaunchedEffect(id, source) {
        if (id.isBlank()) {
            error = "缺少歌单 ID"
            loading = false
            return@LaunchedEffect
        }
        loadDetail()
        loadPage(1)
    }

    fun play(index: Int) {
        scope.launch { PlayerController.playQueue(context, songs, index) }
    }

    // ---------- 长按多选下载 ----------
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Song>() }
    var downloading by remember { mutableStateOf(false) }
    var downloadTip by remember { mutableStateOf<String?>(null) }

    fun exitSelection() {
        selecting = false
        selected.clear()
    }

    fun toggleSelect(song: Song) {
        if (selected.containsKey(song.key)) selected.remove(song.key) else selected[song.key] = song
    }

    /** 下载选中的曲目到本地播放缓存（并发 3 首，已缓存的直接算成功）。 */
    fun downloadSongs(targets: List<Song>) {
        if (targets.isEmpty() || downloading) return
        downloading = true
        downloadTip = "正在下载 ${targets.size} 首…"
        scope.launch {
            val results = kotlinx.coroutines.coroutineScope {
                val gate = Semaphore(3)
                targets.map { song ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            // 单曲失败只算这一首失败：异常一律就地吞掉，
                            // 绝不能让某首无版权/链接异常的歌把整批下载连同 App 一起带走
                            runCatching {
                                // 与播放同一条解析路径（含跨源找音源），保证下回来的就是能播的
                                val resolved = PlaybackResolver.resolve(song)
                                if (!resolved.ok) {
                                    return@runCatching resolved.reason.ifBlank { "取不到播放链接" }
                                }
                                val ok = AudioCache.download(song.key, resolved.url)
                                // 登记到曲库「本地」页；文件被淘汰后该页会按实际缓存复核
                                if (ok) AppGraph.store.pushDownloadedSong(song)
                                if (ok) null else "写入本地失败"
                            }.getOrDefault("未知错误")
                        }
                    }
                }.awaitAll()
            }
            val failReasons = results.filterNotNull()
            val ok = results.size - failReasons.size
            downloading = false
            downloadTip = buildString {
                append("下载完成：成功 $ok 首")
                if (failReasons.isNotEmpty()) {
                    append("，失败 ${failReasons.size} 首（${failReasons.first()}）")
                }
            }
            if (failReasons.isEmpty()) exitSelection()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (selecting) {
                    Text("已选 ${selected.size} 首")
                } else {
                    Text(detail?.name ?: source.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = {
                if (selecting) {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出多选")
                    }
                } else {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            actions = {
                if (selecting) {
                    TextButton(onClick = {
                        if (selected.size == songs.size) selected.clear()
                        else songs.forEach { selected[it.key] = it }
                    }) {
                        Text(if (selected.size == songs.size && songs.isNotEmpty()) "取消全选" else "全选")
                    }
                    TextButton(
                        onClick = { downloadSongs(selected.values.toList()) },
                        enabled = selected.isNotEmpty() && !downloading,
                    ) {
                        Text(if (downloading) "下载中…" else "下载")
                    }
                } else if (songs.isNotEmpty()) {
                    IconButton(onClick = { play(0) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放全部")
                    }
                }
            },
        )

        val listState = rememberLazyListState()

        // 滚到接近底部时自动续下一页，不再要求手动点「加载更多」
        LaunchedEffect(listState, hasMore, loading) {
            if (!hasMore || loading) return@LaunchedEffect
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) return@LaunchedEffect
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
            if (lastVisible >= info.totalItemsCount - 6) loadPage(page + 1)
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 90.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = detail?.cover.orEmpty(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail?.name ?: source.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.size(6.dp))
                        val author = detail?.author.orEmpty()
                        if (author.isNotBlank()) {
                            Text(
                                "by $author",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val count = if (total > 0) total else detail?.songCount ?: 0
                        val playCount = detail?.playCount ?: 0L
                        Text(
                            if (playCount > 0L) {
                                "播放 ${formatCount(playCount)} · $count 首"
                            } else {
                                "$count 首"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (detail?.tags?.isNotEmpty() == true) {
                            Text(
                                detail?.tags?.joinToString(" / ").orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { play(0) },
                                enabled = songs.isNotEmpty(),
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("播放全部")
                            }
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(onClick = { scope.launch { loadPage(1) } }) {
                                Text("刷新")
                            }
                        }
                    }
                }
                val intro = detail?.intro.orEmpty()
                if (intro.isNotBlank()) {
                    Text(
                        intro,
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            error?.let { msg ->
                item { Text(msg, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
            }

            downloadTip?.let { msg ->
                item {
                    Text(
                        msg,
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            itemsIndexed(songs, key = { _, s -> s.key }) { index, song ->
                SongRow(
                    song = song,
                    index = index + 1,
                    onClick = {
                        if (selecting) toggleSelect(song) else play(index)
                    },
                    onLongClick = {
                        if (!selecting) {
                            selecting = true
                            selected[song.key] = song
                        }
                    },
                    selecting = selecting,
                    selected = selected.containsKey(song.key),
                    playing = playState.current?.key == song.key,
                    trailing = if (source == Source.NETEASE && !selecting) {
                        {
                            IconButton(onClick = { nav.navigate(Routes.comments(song.id, song.name)) }) {
                                Icon(
                                    Icons.Filled.ChatBubbleOutline,
                                    contentDescription = "评论",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }

            if (loading && page > 1) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (songs.isEmpty() && error == null && !loading) {
                item {
                    Text(
                        "这个歌单没有可播放的曲目",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
