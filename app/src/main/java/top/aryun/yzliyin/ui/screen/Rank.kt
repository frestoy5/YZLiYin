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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Rank
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SongRow
import top.aryun.yzliyin.ui.components.SectionHeader
import top.aryun.yzliyin.ui.components.formatCount

/** 新歌榜：排行榜页「最新歌曲」区的数据来源。 */
private const val NEW_SONG_RANK_ID = "3779629"

@Composable
fun RankScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playState by PlayerController.state.collectAsStateWithLifecycle()

    var ranks by remember { mutableStateOf<List<Rank>>(emptyList()) }
    var latest by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            val r = async { runCatching { NeteaseApi.toplists() }.getOrDefault(emptyList()) }
            val l = async {
                runCatching {
                    NeteaseApi.playlistTracks(NEW_SONG_RANK_ID, page = 1, pageSize = 15).items
                }.getOrDefault(emptyList())
            }
            val p = async { runCatching { NeteaseApi.recommendedPlaylists(12) }.getOrDefault(emptyList()) }
            ranks = r.await()
            latest = l.await()
            playlists = p.await()
        }
        loading = false
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 220.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                "排行榜",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (latest.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "最新歌曲",
                    action = "播放全部",
                    onAction = {
                        scope.launch { PlayerController.playQueue(context, latest, 0) }
                    },
                )
                Text(
                    "网易云新歌榜 · 每日更新",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            itemsIndexed(latest, key = { _, s -> s.id }) { i, song ->
                SongRow(
                    song = song,
                    onClick = {
                        scope.launch { PlayerController.playQueue(context, latest, i) }
                    },
                    index = i + 1,
                    playing = playState.current?.id == song.id,
                )
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
        }

        if (ranks.isNotEmpty()) {
            item { SectionHeader(title = "官方榜单") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(ranks, key = { it.id + it.name }) { r ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            modifier = Modifier
                                .width(140.dp)
                                .clickable { nav.navigate(Routes.rankDetail(r.id)) },
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp)),
                                ) {
                                    AsyncImage(
                                        model = r.cover,
                                        contentDescription = r.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    r.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (r.updateFrequency.isNotBlank()) {
                                    Text(
                                        r.updateFrequency,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item { SectionHeader(title = "热门歌单") }

        if (playlists.isEmpty() && loading) {
            item {
                Text(
                    "加载中…",
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(playlists.chunked(2), key = { it.first().id }) { pair ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pair.forEach { p ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { nav.navigate(Routes.playlist(p.id)) },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.4f)
                                .clip(RoundedCornerShape(12.dp)),
                        ) {
                            AsyncImage(
                                model = p.cover,
                                contentDescription = p.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (p.songCount > 0) {
                                "${p.songCount} 首 · 播放 ${formatCount(p.playCount)}"
                            } else {
                                "播放 ${formatCount(p.playCount)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
