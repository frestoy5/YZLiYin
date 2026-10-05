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
import top.aryun.yzliyin.core.model.Album
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.components.SongRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(nav: NavController, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by PlayerController.state.collectAsStateWithLifecycle()

    var album by remember { mutableStateOf<Album?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        if (id.isBlank()) {
            error = "缺少专辑 ID"
            return@LaunchedEffect
        }
        album = runCatching { NeteaseApi.albumDetail(id) }.getOrNull()
        runCatching { NeteaseApi.albumSongs(id) }
            .onSuccess { songs = it }
            .onFailure { error = it.message ?: "加载专辑失败" }
    }

    fun play(index: Int) {
        scope.launch { PlayerController.playQueue(context, songs, index) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(album?.name ?: "专辑", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            album?.let { d ->
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
                                .size(110.dp)
                                .clip(RoundedCornerShape(12.dp)),
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
                            Spacer(Modifier.size(4.dp))
                            if (d.artist.isNotBlank()) {
                                Text(
                                    d.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                buildString {
                                    if (d.publishDate.isNotEmpty()) append(d.publishDate)
                                    append(" · ${songs.size} 首")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
        }
    }
}
