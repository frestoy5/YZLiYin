package top.aryun.yzliyin.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.BiliLiveRoom
import top.aryun.yzliyin.core.net.NetworkStatus
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.LiveRoomEntryDialog
import top.aryun.yzliyin.ui.components.SectionHeader
import top.aryun.yzliyin.ui.theme.NetGreen
import top.aryun.yzliyin.ui.theme.NetRed
import top.aryun.yzliyin.ui.theme.NetYellow

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
    var lives by remember { mutableStateOf<List<BiliLiveRoom>>(emptyList()) }
    // 点了哪张直播卡片还没决定从哪进（软件内 / B 站 App）
    var livePicker by remember { mutableStateOf<Long?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }

    // 首次进入与右上角「刷新」共用同一条加载链路：几块内容一起重拉，
    // 拉不到就保留上一次的结果，免得刷新一次失败就把整页清空。
    LaunchedEffect(refreshKey) {
        if (refreshKey == 0) loading = true
        NetworkStatus.refresh()
        kotlinx.coroutines.coroutineScope {
            val h = async { NeteaseApi.hotSearch() }
            val hs = async {
                runCatching { NeteaseApi.playlistTracks(HOT_RANK_ID, page = 1, pageSize = 10).items }
                    .getOrDefault(emptyList())
            }
            val n = async { runCatching { NeteaseApi.newSongs(12) }.getOrDefault(emptyList()) }
            val p = async {
                runCatching { NeteaseApi.recommendedPlaylists(limit = 10) }.getOrDefault(emptyList())
            }
            // 直播是可失败的一块：拿不到就整块不显示，不拖累其它几块
            val l = async {
                runCatching { BiliApi.liveRooms(page = 1, pageSize = 12) }.getOrDefault(emptyList())
            }
            h.await().takeIf { it.isNotEmpty() }?.let { hots = it }
            hs.await().takeIf { it.isNotEmpty() }?.let { hotSongs = it }
            n.await().takeIf { it.isNotEmpty() }?.let { fresh = it }
            p.await().takeIf { it.isNotEmpty() }?.let { playlists = it }
            l.await().takeIf { it.isNotEmpty() }?.let { lives = it }
        }
        loading = false
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
                Spacer(Modifier.width(10.dp))
                NetworkBadge()
                Spacer(Modifier.weight(1f))
                // 刷新时图标转圈；转完（loading 复位）即停，期间不接受连点
                val spin by rememberInfiniteTransition(label = "refresh").animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "refreshRotation",
                )
                FilledTonalIconButton(
                    onClick = { refreshKey++ },
                    enabled = !loading,
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.home_refresh_desc),
                        modifier = Modifier.rotate(if (loading) spin else 0f),
                    )
                }
            }
        }

        if (hots.isNotEmpty()) {
            item {
                SectionHeader(title = stringResource(R.string.home_hot_search))
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

        // 「正在直播中」：B 站直播入口，点了先问从哪进
        if (lives.isNotEmpty()) {
            item {
                SectionHeader(title = stringResource(R.string.live_home_section))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(lives, key = { it.roomId }) { room ->
                        LiveRoomCard(room = room, onClick = { livePicker = room.roomId })
                    }
                }
            }
        }

        if (hotSongs.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(R.string.home_hot_rank),
                    action = stringResource(R.string.home_play_all),
                    onAction = { play(hotSongs, 0) },
                )
                Text(
                    stringResource(R.string.home_hot_rank_note),
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
                SectionHeader(title = stringResource(R.string.home_new_songs), action = stringResource(R.string.home_play_all), onAction = { play(fresh, 0) })
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
                title = stringResource(R.string.home_rec_playlists),
                action = stringResource(R.string.home_refresh_batch),
                onAction = { refreshKey++ },
            )
            if (playlists.isEmpty() && loading) {
                Text(
                    stringResource(R.string.home_loading),
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
                            .clickable { nav.navigate(Routes.playlist(p.source, p.id)) },
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
                    stringResource(R.string.home_offline_retry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }

    // 进直播间前先问从哪进：1. 本软件内观看  2. 在 B 站 APP 打开（与 UP 主页共用一个弹窗）
    LiveRoomEntryDialog(roomId = livePicker, nav = nav, onDismiss = { livePicker = null })
}

/** 首页标题旁的网络状态徽标：延迟数值 + 状态色点（绿快 / 黄一般 / 红未连接），不再写「优秀 / 中等」。 */
@Composable
private fun NetworkBadge() {
    val level = NetworkStatus.level
    val latency = NetworkStatus.latencyMs
    val color = when (level) {
        NetworkStatus.Level.GOOD -> NetGreen
        NetworkStatus.Level.FAIR -> NetYellow
        NetworkStatus.Level.OFFLINE -> NetRed
        NetworkStatus.Level.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when (level) {
        NetworkStatus.Level.GOOD, NetworkStatus.Level.FAIR ->
            stringResource(R.string.home_latency, latency ?: 0)
        NetworkStatus.Level.OFFLINE -> stringResource(R.string.home_not_connected)
        NetworkStatus.Level.CHECKING -> stringResource(R.string.home_checking)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

/** 首页「正在直播中」的一张卡片：封面 + LIVE 角标 + 标题 + 主播/分区。 */
@Composable
private fun LiveRoomCard(room: BiliLiveRoom, onClick: () -> Unit) {
    Column(
        Modifier
            .width(152.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = room.cover,
                contentDescription = room.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color(0xFFFF3B30), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    "LIVE",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color(0xA6000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                val watched = room.watchedText.ifBlank { room.online.toString() }
                Text(
                    // 接口有时直接给 text_large「1.6万人看过」，本身就带「人」，别再拼一遍
                    if ('人' in watched) {
                        watched
                    } else {
                        stringResource(R.string.live_watchers, watched)
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            room.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = room.anchorFace,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                listOf(room.anchorName, room.areaName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
