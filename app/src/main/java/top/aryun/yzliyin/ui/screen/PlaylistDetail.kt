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
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SongRow
import top.aryun.yzliyin.ui.components.formatCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(nav: NavController, id: String) {
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

    suspend fun loadPage(p: Int) {
        loading = true
        runCatching { NeteaseApi.playlistTracks(id, page = p, pageSize = 50) }
            .onSuccess {
                songs = if (p == 1) it.items else songs + it.items
                total = it.total
                hasMore = it.hasMore
                page = p
                error = null
            }
            .onFailure { error = it.message ?: "加载歌单失败" }
        loading = false
    }

    LaunchedEffect(id) {
        if (id.isBlank()) {
            error = "缺少歌单 ID"
            loading = false
            return@LaunchedEffect
        }
        detail = runCatching { NeteaseApi.playlistDetail(id) }.getOrNull()
        loadPage(1)
    }

    fun play(index: Int) {
        scope.launch { PlayerController.playQueue(context, songs, index) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(detail?.name ?: "歌单", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            detail?.let { d ->
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = d.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.size(6.dp))
                            if (d.author.isNotBlank()) {
                                Text(
                                    "by ${d.author}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                "播放 ${formatCount(d.playCount)} · ${if (total > 0) total else d.songCount} 首",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (d.tags.isNotEmpty()) {
                                Text(
                                    d.tags.joinToString(" / "),
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
                    if (d.intro.isNotBlank()) {
                        Text(
                            d.intro,
                            Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            error?.let { msg ->
                item { Text(msg, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
            }

            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    index = index + 1,
                    onClick = { play(index) },
                    playing = playState.current?.id == song.id,
                    trailing = {
                        IconButton(onClick = { nav.navigate(Routes.comments(song.id, song.name)) }) {
                            Icon(
                                Icons.Filled.ChatBubbleOutline,
                                contentDescription = "评论",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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

            if (songs.isEmpty() && error == null && !loading && detail != null) {
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
