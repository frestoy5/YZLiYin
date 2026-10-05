package top.aryun.yzliyin.ui.screen

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import top.aryun.yzliyin.core.model.Rank
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.components.SongRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankDetailScreen(nav: NavController, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by PlayerController.state.collectAsStateWithLifecycle()

    var info by remember { mutableStateOf<Rank?>(null) }
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
            .onFailure { error = it.message ?: "加载榜单失败" }
        loading = false
    }

    LaunchedEffect(id) {
        if (id.isBlank()) {
            error = "缺少榜单 ID"
            loading = false
            return@LaunchedEffect
        }
        info = runCatching { NeteaseApi.rankInfo(id) }.getOrNull()
            ?: runCatching {
                NeteaseApi.playlistDetail(id)?.let { pl ->
                    Rank(id = pl.id, name = pl.name, cover = pl.cover, intro = pl.intro)
                }
            }.getOrNull()
        loadPage(1)
    }

    fun play(index: Int) {
        scope.launch { PlayerController.playQueue(context, songs, index) }
    }

    val title = info?.name?.ifBlank { "排行榜" } ?: "排行榜"

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            info?.let { r ->
                if (r.cover.isNotBlank() || r.updateFrequency.isNotBlank()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (r.cover.isNotBlank()) {
                                AsyncImage(
                                    model = r.cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                                Spacer(Modifier.width(14.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (r.updateFrequency.isNotBlank()) {
                                    Text(
                                        r.updateFrequency,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "共 ${if (total > 0) total else songs.size} 首",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            error?.let { msg ->
                item { Text(msg, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
            }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                SongRow(
                    song = song,
                    index = i + 1,
                    onClick = { play(i) },
                    playing = playState.current?.id == song.id,
                )
            }
            if (hasMore && !loading) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Button(onClick = { scope.launch { loadPage(page + 1) } }) { Text("加载更多") }
                    }
                }
            }
            if (songs.isEmpty() && error == null && !loading) {
                item {
                    Text(
                        "榜单暂无内容",
                        Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
