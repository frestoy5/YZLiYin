package top.aryun.yzliyin.ui.screen

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.BiliDynamicItem
import top.aryun.yzliyin.core.net.BiliLiveBrief
import top.aryun.yzliyin.core.net.BiliUpCard
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.LiveRoomEntryDialog
import top.aryun.yzliyin.ui.components.SongRow
import top.aryun.yzliyin.ui.components.formatCount
import top.aryun.yzliyin.ui.theme.NetRed
import top.aryun.yzliyin.util.NPLogger

/**
 * B 站 UP 主主页（应用内）：资料卡 + 「投稿 / 动态」两个 tab。
 *
 * 投稿 tab 列出该 UP 的视频投稿，点一条进页内播放；
 * 动态 tab 列出全部动态（文字 / 视频 / 转发等），视频动态同样可点进页内播放。
 * 分页由滚动到底触发；动态流用 offset 游标翻页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliUpScreen(nav: NavController, mid: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var card by remember { mutableStateOf<BiliUpCard?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    // 这位 UP 开没开播；查不到（没有直播间）就是 null，头像右边不显示「正在直播」
    var live by remember { mutableStateOf<BiliLiveBrief?>(null) }
    // 点了「正在直播」等用户选从哪进；null 表示不弹窗
    var liveRoomId by remember { mutableStateOf<Long?>(null) }

    var name by remember { mutableStateOf("") }

    // 投稿
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var cursor by remember { mutableStateOf("") }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 动态
    var dynamics by remember { mutableStateOf<List<BiliDynamicItem>>(emptyList()) }
    var dynOffset by remember { mutableStateOf("") }
    var dynHasMore by remember { mutableStateOf(true) }
    var dynLoading by remember { mutableStateOf(false) }
    var dynError by remember { mutableStateOf<String?>(null) }

    suspend fun loadCard() {
        runCatching { BiliApi.upCard(mid) }
            .onSuccess {
                card = it
                name = it.name.ifBlank { name }
            }
            .onFailure { NPLogger.w("BiliUpScreen", "资料卡加载失败：${it.message}") }
    }

    suspend fun loadLive() {
        val uid = mid.toLongOrNull() ?: return
        runCatching { BiliApi.liveBrief(uid) }
            .onSuccess { live = it }
            .onFailure { NPLogger.w("BiliUpScreen", "直播间状态加载失败：${it.message}") }
    }

    suspend fun load(nextCursor: String, append: Boolean) {
        if (loading || (append && !hasMore)) return
        loading = true
        runCatching { BiliApi.upVideos(mid, nextCursor) }
            .onSuccess { result ->
                val more = BiliApi.upSongs(result)
                songs = if (append) songs + more else more
                hasMore = result.hasMore
                cursor = result.nextCursor
                error = if (more.isEmpty() && !append) {
                    context.getString(R.string.bili_up_no_videos)
                } else {
                    null
                }
            }
            .onFailure { error = it.message ?: context.getString(R.string.bili_up_load_fail) }
        loading = false
    }

    suspend fun loadDynamics(nextOffset: String, append: Boolean) {
        if (dynLoading) return
        dynLoading = true
        runCatching { BiliApi.upDynamics(mid, nextOffset) }
            .onSuccess { result ->
                dynamics = if (append) dynamics + result.items else result.items
                dynOffset = result.offset
                dynHasMore = result.hasMore
                dynError = if (result.items.isEmpty() && !append) {
                    context.getString(R.string.bili_up_no_dynamics)
                } else {
                    null
                }
            }
            .onFailure { dynError = it.message ?: context.getString(R.string.bili_up_load_fail) }
        dynLoading = false
    }

    LaunchedEffect(mid) {
        if (mid.isBlank()) {
            error = context.getString(R.string.bili_up_missing_id)
            return@LaunchedEffect
        }
        // 资料卡和直播间状态都是小请求，并行拉，别让「正在直播」等在资料卡后面
        coroutineScope {
            launch { loadCard() }
            launch { loadLive() }
        }
        load("", append = false)
    }

    // 首次切到动态 tab 才拉动态流（懒加载，进页面不白拉一遍）
    LaunchedEffect(tab) {
        if (tab == 1 && dynamics.isEmpty() && !dynLoading && mid.isNotBlank()) {
            loadDynamics("", append = false)
        }
    }

    // 滚到接近底部才翻下一页；不能按 page 触发，否则一进来就把整本投稿拉完
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(shouldLoadMore, tab) {
        if (!shouldLoadMore) return@LaunchedEffect
        if (tab == 0) {
            if (!loading && hasMore && songs.isNotEmpty()) load(cursor, append = true)
        } else {
            if (!dynLoading && dynHasMore && dynamics.isNotEmpty()) loadDynamics(dynOffset, append = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    name.ifBlank { stringResource(R.string.bili_up_default_name) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.bili_back),
                    )
                }
            },
        )

        // 资料卡：头像 + 昵称 + 正在直播 + 关注/粉丝/获赞 + 大会员 + IP + 简介
        card?.let {
            UpProfileCard(
                card = it,
                live = live,
                onLiveClick = { brief -> liveRoomId = brief.roomId },
            )
        }

        // tab 切换
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(stringResource(R.string.bili_up_tab_videos)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.bili_up_tab_feed)) },
            )
        }

        if (tab == 0) {
            UploadList(
                nav = nav,
                listState = listState,
                songs = songs,
                loading = loading,
                error = error,
                onRetryMore = { scope.launch { load(cursor, append = true) } },
            )
        } else {
            DynamicsList(
                nav = nav,
                listState = listState,
                dynamics = dynamics,
                loading = dynLoading,
                error = dynError,
                onLoadMore = { scope.launch { loadDynamics(dynOffset, append = true) } },
            )
        }
    }

    // 点「正在直播」先问从哪进：1. 本软件内观看  2. 在 B 站 APP 打开
    LiveRoomEntryDialog(roomId = liveRoomId, nav = nav, onDismiss = { liveRoomId = null })
}

/** UP 主资料卡：头像、昵称、正在直播标识、关注 / 粉丝 / 获赞、大会员、IP 归属地、简介。 */
@Composable
private fun UpProfileCard(
    card: BiliUpCard,
    live: BiliLiveBrief?,
    onLiveClick: (BiliLiveBrief) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.face.isNotBlank()) {
                AsyncImage(
                    model = card.face,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.name.ifBlank { stringResource(R.string.bili_up_default_name) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 名字太长时让着右边的大会员 / 正在直播，别把它们挤出屏幕
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (card.vip) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.bili_up_vip),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (live != null) {
                        LiveBadge(live = live, onClick = { onLiveClick(live) })
                    }
                }
                if (card.sign.isNotBlank()) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        card.sign,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.size(10.dp))

        Row(Modifier.fillMaxWidth()) {
            StatItem(stringResource(R.string.bili_stat_following), card.following)
            StatItem(stringResource(R.string.bili_stat_follower), card.follower)
            StatItem(stringResource(R.string.bili_stat_likes), card.likeNum)
        }

        if (card.ipLocation.isNotBlank()) {
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.bili_up_ip_location, card.ipLocation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 头像右边的「正在直播」：红＝正在直播，灰＝没在播。
 *
 * 灰色可能是未开播，也可能是轮播（房间在放录像）——后者照样能进房看，
 * 只有真的未开播时点一下才提示一句，不弹那个「从哪进」的框。
 */
@Composable
private fun LiveBadge(live: BiliLiveBrief, onClick: () -> Unit) {
    val context = LocalContext.current
    // live_status：0 未开播 / 1 正在直播 / 2 轮播中
    val onAir = live.liveStatus == 1
    Text(
        text = stringResource(R.string.live_on_air),
        color = if (onAir) NetRed else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 8.dp)
            .clickable {
                if (live.liveStatus == 0) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.live_not_started),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    onClick()
                }
            },
    )
}

@Composable
private fun StatItem(label: String, value: Long) {
    Column(
        Modifier.padding(end = 24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            formatCount(value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 投稿列表（原 BiliUpScreen 主体，抽出复用）。 */
@Composable
private fun UploadList(
    nav: NavController,
    listState: LazyListState,
    songs: List<Song>,
    loading: Boolean,
    error: String?,
    onRetryMore: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 点投稿先问「怎么播」，不再直接进页内播放：同一条投稿可能想看画面、
    // 也可能只想锁屏当音频听、或者干脆丢给装好的 B 站 App
    var pendingIndex by remember { mutableStateOf<Int?>(null) }

    pendingIndex?.let { index ->
        val song = songs.getOrNull(index)
        if (song == null) {
            pendingIndex = null
        } else {
            PlayChooserDialog(
                song = song,
                onDismiss = { pendingIndex = null },
                onBiliApp = {
                    pendingIndex = null
                    openBilibiliVideo(context, song.playId.ifEmpty { song.id })
                },
                onListen = {
                    pendingIndex = null
                    scope.launch { PlayerController.playQueue(context, songs, index) }
                },
                onPlayUpload = {
                    pendingIndex = null
                    nav.navigate(
                        Routes.biliVideo(song.playId.ifEmpty { song.id }, song.playSubId, song.name),
                    )
                },
            )
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 90.dp),
    ) {
        error?.let { msg ->
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        msg,
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (songs.isNotEmpty()) {
                        TextButton(onClick = onRetryMore) { Text(stringResource(R.string.bili_retry)) }
                    }
                }
            }
        }

        itemsIndexed(songs, key = { _, s -> s.key }) { index, song ->
            // 只记下点的是哪条，真正去哪播交给上面的 PlayChooserDialog 决定
            SongRow(song = song, onClick = { pendingIndex = index })
        }

        if (loading) {
            item {
                CircularProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * 点投稿后的播放方式选择。
 *
 * 三条去向对应本 App 现有的三条路径：丢给 B 站 App / 浏览器（[openBilibiliVideo]，
 * 没装 App 会退回 https 直链进浏览器）、当纯音频进音乐播放器（可锁屏后台、走音效、入队列）、
 * 以及默认的页内视频播放（看画面、有弹幕）。
 *
 * 「播放投稿」放在确认位，因为它就是点投稿原本的行为，这样习惯老流程的人不用改手。
 */
@Composable
private fun PlayChooserDialog(
    song: Song,
    onDismiss: () -> Unit,
    onBiliApp: () -> Unit,
    onListen: () -> Unit,
    onPlayUpload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bili_play_where)) },
        text = { Text(song.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        confirmButton = {
            TextButton(onClick = onPlayUpload) { Text(stringResource(R.string.bili_play_upload)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onBiliApp) { Text(stringResource(R.string.bili_play_biliapp)) }
                TextButton(onClick = onListen) { Text(stringResource(R.string.bili_play_listen)) }
            }
        },
    )
}

/** 动态列表：文字 / 视频 / 转发；视频动态可点进页内播放，其余只展示。 */
@Composable
private fun DynamicsList(
    nav: NavController,
    listState: LazyListState,
    dynamics: List<BiliDynamicItem>,
    loading: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 90.dp),
    ) {
        error?.let { msg ->
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    if (dynamics.isNotEmpty()) {
                        TextButton(onClick = onLoadMore) { Text(stringResource(R.string.bili_retry)) }
                    }
                }
            }
        }

        itemsIndexed(dynamics, key = { _, d -> d.id }) { _, d ->
            DynamicRow(d) {
                if (d.bvid.isNotBlank()) {
                    nav.navigate(Routes.biliVideo(d.bvid, "", d.title.ifBlank { d.text }))
                }
            }
        }

        if (loading) {
            item {
                CircularProgressIndicator(
                    Modifier.fillMaxWidth().padding(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** 单条动态。视频动态整行可点，纯文字 / 转发不可点。 */
@Composable
private fun DynamicRow(item: BiliDynamicItem, onClick: () -> Unit) {
    val clickable = item.isVideo && item.bvid.isNotBlank()
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.extra.isNotBlank()) {
                Text(
                    item.extra,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
            }
            if (item.timeText.isNotBlank()) {
                Text(
                    item.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.isVideo) {
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.bili_up_video),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (item.title.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (item.text.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                item.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
