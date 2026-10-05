package top.aryun.yzliyin.ui.screen

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import top.aryun.yzliyin.core.model.Album
import top.aryun.yzliyin.core.model.Artist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SongRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(nav: NavController, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by PlayerController.state.collectAsStateWithLifecycle()

    var artist by remember { mutableStateOf<Artist?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        if (id.isBlank()) {
            error = "缺少歌手 ID"
            return@LaunchedEffect
        }
        artist = runCatching { NeteaseApi.artistDetail(id) }.getOrNull()
        runCatching { NeteaseApi.artistSongs(id, page = 1, pageSize = 50) }
            .onSuccess { songs = it.items }
            .onFailure { error = it.message ?: "加载歌曲失败" }
        runCatching { NeteaseApi.artistAlbums(id, page = 1, pageSize = 30) }
            .onSuccess { albums = it.items }
            .onFailure { error = it.message ?: "加载专辑失败" }
    }

    fun play(index: Int) {
        scope.launch { PlayerController.playQueue(context, songs, index) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    artist?.name?.ifBlank { "歌手" } ?: "歌手",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("热门歌曲") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("专辑 ${albums.size}") })
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp),
        ) {
            if (tab == 0) {
                artist?.let { a ->
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (a.avatar.isNotBlank()) {
                                AsyncImage(
                                    model = a.avatar,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape),
                                )
                                Spacer(Modifier.width(14.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    a.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "单曲 ${a.songCount} · 专辑 ${a.albumCount}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (a.intro.isNotBlank()) {
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        a.intro,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
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
                if (songs.isEmpty() && error == null) {
                    item {
                        Text(
                            "暂无歌曲",
                            Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(albums, key = { it.id + it.name }) { album ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (album.id.isNotEmpty()) nav.navigate(Routes.album(album.id))
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = album.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                album.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    if (album.publishDate.isNotEmpty()) append(album.publishDate)
                                    if (album.songCount > 0) append(" · ${album.songCount} 首")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (albums.isEmpty()) {
                    item {
                        Text(
                            "暂无专辑",
                            Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
