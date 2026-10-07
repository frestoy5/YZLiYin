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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.model.Paged
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
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
            }
            .onFailure { error = it.message ?: "加载歌单失败" }
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

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(detail?.name ?: source.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                if (songs.isNotEmpty()) {
                    IconButton(onClick = { play(0) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放全部")
                    }
                }
            },
        )

        LazyColumn(
            Modifier.fillMaxSize(),
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

            itemsIndexed(songs, key = { _, s -> s.key }) { index, song ->
                SongRow(
                    song = song,
                    index = index + 1,
                    onClick = { play(index) },
                    playing = playState.current?.key == song.key,
                    trailing = if (source == Source.NETEASE) {
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

            if (hasMore && !loading) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Button(onClick = { scope.launch { loadPage(page + 1) } }) { Text("加载更多") }
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
