package top.aryun.yzliyin.ui.screen

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SectionHeader

/** 网易云热歌榜（最新热度）。 */
private const val HOT_RANK_ID = "3778678"

@Composable
fun HomeScreen(nav: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by PlayerController.state.collectAsStateWithLifecycle()

    var hots by remember { mutableStateOf<List<String>>(emptyList()) }
    var hotSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var fresh by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            val h = async { NeteaseApi.hotSearch() }
            val hs = async {
                runCatching { NeteaseApi.playlistTracks(HOT_RANK_ID, page = 1, pageSize = 10).items }
                    .getOrDefault(emptyList())
            }
            val n = async { runCatching { NeteaseApi.newSongs(12) }.getOrDefault(emptyList()) }
            hots = h.await()
            hotSongs = hs.await()
            fresh = n.await()
        }
        loading = false
    }

    LaunchedEffect(refreshKey) {
        playlists = runCatching {
            NeteaseApi.recommendedPlaylists(limit = 10)
        }.getOrDefault(emptyList())
    }

    fun play(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return
        scope.launch { PlayerController.playQueue(context, songs, index) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 220.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "音之理音",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = {
                    nav.navigate(Routes.SEARCH) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
            }
        }

        if (hots.isNotEmpty()) {
            item {
                SectionHeader(title = "热搜")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(hots, key = { it }) { word ->
                        SuggestionChip(
                            onClick = {
                                SearchQueryBus.pending = word
                                nav.navigate(Routes.SEARCH) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            label = { Text(word, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        }

        if (hotSongs.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "最新热度",
                    action = "播放全部",
                    onAction = { play(hotSongs, 0) },
                )
                Text(
                    "网易云热歌榜 · 实时热度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(hotSongs, key = { it.id }) { song ->
                        val index = hotSongs.indexOf(song)
                        Column(
                            Modifier
                                .width(116.dp)
                                .clickable { play(hotSongs, index) },
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp)),
                            ) {
                                AsyncImage(
                                    model = song.cover,
                                    contentDescription = song.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                song.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (fresh.isNotEmpty()) {
            item {
                SectionHeader(title = "新歌速递", action = "播放全部", onAction = { play(fresh, 0) })
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(fresh, key = { it.id }) { song ->
                        val index = fresh.indexOf(song)
                        Column(
                            Modifier
                                .width(116.dp)
                                .clickable { play(fresh, index) },
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp)),
                            ) {
                                AsyncImage(
                                    model = song.cover,
                                    contentDescription = song.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                song.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "推荐歌单",
                action = "换一批",
                onAction = { refreshKey++ },
            )
            if (playlists.isEmpty() && loading) {
                Text(
                    "加载中…",
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(playlists, key = { it.id }) { p ->
                    Column(
                        Modifier
                            .width(132.dp)
                            .clickable { nav.navigate(Routes.playlist(p.id)) },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)),
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
                            p.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (!loading && hotSongs.isEmpty() && fresh.isEmpty() && playlists.isEmpty()) {
            item {
                Text(
                    "网络不可达，请稍后下拉重试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}
