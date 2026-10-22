package top.aryun.yzliyin.ui.screen

import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.BiliLiveDanmaku
import top.aryun.yzliyin.core.net.BiliLiveDmInfo
import top.aryun.yzliyin.core.net.BiliLiveMsg
import top.aryun.yzliyin.core.net.BiliLiveRoom
import top.aryun.yzliyin.player.PlaybackService
import top.aryun.yzliyin.player.PlayerController
import kotlin.math.min

/** 聊天区一行正文的字数上限（B 站普通用户 20 条弹幕，留点余量）。 */
private const val MAX_DRAFT_LEN = 40

/** 悬浮弹幕的轨道数，也是同屏最多几行字。 */
private const val LANE_COUNT = 5

/** 同屏最多留多少条弹幕，防止刷屏卡顿。 */
private const val MAX_FLOATING = 48

/** 弹幕横穿画面所需时间（纳秒）。 */
private const val DURATION_NS = 9_000_000_000L

/** 正在画面上飘的单条弹幕。 */
private class FloatingDanmaku(
    val text: String,
    val color: Color,
    val startNs: Long,
    val lane: Int,
)

/**
 * 直播间页。
 *
 * 画面走 `getRoomPlayInfo` 解出的 HLS 直链交给 ExoPlayer，弹幕用 [BiliLiveDanmaku] 的
 * websocket 推送，同时铺两层：一层 [DanmakuLayer] 飘在画面上，一层聊天列表落在画面下方，
 * 底部是发弹幕的输入框；主播头像右边是关注按钮。
 */
@Composable
fun LiveRoomScreen(roomId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<BiliLiveRoom?>(null) }
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var dmInfo by remember { mutableStateOf<BiliLiveDmInfo?>(null) }
    var history by remember { mutableStateOf<List<BiliLiveMsg>>(emptyList()) }
    var liveMessages by remember { mutableStateOf<List<BiliLiveMsg>>(emptyList()) }
    var following by remember { mutableStateOf<Boolean?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var followBusy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 聊天区把历史弹幕和实时弹幕拼在一起；飘屏只吃实时的那半边
    val chat = history + liveMessages
    val isFollowing = following == true

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }

    // 首帧出来前一直转圈，`else` 分支里的播放器自己也会带缓冲态
    var buffering by remember { mutableStateOf(true) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(toast) {
        val message = toast ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        toast = null
    }

    // 进房先停掉正在放的音乐，免得和直播声打架
    LaunchedEffect(roomId, retryTick) {
        loading = true
        error = null
        streamUrl = null
        dmInfo = null
        liveMessages = emptyList()
        PlayerController.pause()

        val roomInfo = runCatching { BiliApi.liveRoomInfo(roomId) }.getOrNull()
        if (roomInfo == null) {
            error = context.getString(R.string.live_load_fail)
            loading = false
            return@LaunchedEffect
        }
        info = roomInfo

        val first = runCatching { BiliApi.liveStreams(roomId) }
            .getOrDefault(emptyList())
            .firstOrNull()
        if (first != null) {
            streamUrl = first.url
        } else {
            error = context.getString(
                if (roomInfo.liveStatus != 1) R.string.live_not_started else R.string.live_no_stream,
            )
        }

        // 弹幕通道 / 历史弹幕 / 关注态都不挡开播，并行拉
        coroutineScope {
            val dm = async { runCatching { BiliApi.liveDmInfo(roomId) }.getOrNull() }
            val old = async {
                runCatching { BiliApi.liveDmHistory(roomId) }.getOrDefault(emptyList<BiliLiveMsg>())
            }
            val followed = async {
                if (BiliApi.isLoggedIn) {
                    runCatching { BiliApi.liveRelation(roomInfo.uid) }.getOrNull()
                } else {
                    null
                }
            }
            dmInfo = dm.await()
            history = old.await()
            following = followed.await()
        }
        loading = false
    }

    // 直链到手后开播；数据源带上 B 站 CDN 要求的 Referer / UA，否则 403
    LaunchedEffect(streamUrl) {
        val url = streamUrl ?: return@LaunchedEffect
        val factory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(PlaybackService.BILI_REQUEST_HEADERS)
        val source = HlsMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    // 弹幕连接跟着弹道走：换房 / 重试各建各的，退出房间即断开
    DisposableEffect(dmInfo, roomId) {
        val dm = dmInfo
        val client = if (dm != null && dm.hosts.isNotEmpty()) {
            BiliLiveDanmaku(
                hosts = dm.hosts,
                token = dm.token,
                roomId = roomId,
                uid = BiliApi.userId,
                onDanmaku = { message ->
                    scope.launch { liveMessages = (liveMessages + message).takeLast(500) }
                },
                onState = { state ->
                    // 主播下播：服务端会广播 PREPARING，收播后别继续放缓冲里剩下的声音。
                    // 这个回调落在 OkHttp 线程，经 scope 切回主线程；页面若已退出，
                    // scope 已取消（body 不会跑），播放器也可能已被释放，故不吞异常外抛。
                    if (state == "preparing" && streamUrl != null) {
                        scope.launch {
                            runCatching { player.stop() }
                            error = context.getString(R.string.live_room_offline)
                        }
                    }
                },
            )
            .also { it.start() }
        } else {
            null
        }
        onDispose { client?.close() }
    }

    fun sendDraft() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        if (!BiliApi.isLoggedIn) {
            toast = context.getString(R.string.live_not_login)
            return
        }
        sending = true
        scope.launch {
            val sent = runCatching { BiliApi.sendLiveMsg(roomId, text) }.isSuccess
            sending = false
            if (sent) {
                draft = ""
            } else {
                toast = context.getString(
                    if (BiliApi.isLoggedIn) R.string.live_send_fail else R.string.live_not_login,
                )
            }
        }
    }

    fun toggleFollow() {
        val uid = info?.uid ?: return
        if (followBusy) return
        if (!BiliApi.isLoggedIn) {
            toast = context.getString(R.string.live_not_login)
            return
        }
        val target = !isFollowing
        followBusy = true
        scope.launch {
            val ok = runCatching { BiliApi.liveRelationModify(uid, target) }.getOrNull()
            followBusy = false
            if (ok == null) {
                toast = context.getString(R.string.bili_comments_action_fail)
            } else {
                following = target
            }
        }
    }

    // 16:9 的画面，但最多占约四成屏高，聊天区才留得下位置
    val videoHeight = min(config.screenWidthDp * 9f / 16f, config.screenHeightDp * 0.42f)

    Column(Modifier.fillMaxSize().background(Color.Black)) {

        // ---------- 画面 + 飘屏弹幕 + 顶栏 ----------
        Box(Modifier.fillMaxWidth().height(videoHeight.dp)) {
            when {
                loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(30.dp))
                    Text(
                        stringResource(R.string.live_reloading),
                        color = Color(0xFF9E9E9E),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                error != null -> Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        error.orEmpty(),
                        color = Color(0xFFCCCCCC),
                        textAlign = TextAlign.Center,
                    )
                    Row {
                        TextButton(
                            onClick = { retryTick++ },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) {
                            Text(stringResource(R.string.bili_retry))
                        }
                        TextButton(
                            onClick = { openBilibiliLive(context, roomId) },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        ) {
                            Text(stringResource(R.string.live_watch_in_bili))
                        }
                    }
                }

                else -> {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                useController = true
                            }
                        },
                        update = { view -> view.player = player },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 让开顶栏（状态栏 + 48dp 的返回键），别让弹幕从标题底下钻过去
                    DanmakuLayer(
                        messages = liveMessages,
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(top = 52.dp),
                    )
                    if (buffering) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(28.dp),
                        )
                    }
                }
            }

            // 顶栏：返回 + 房间标题，盖在画面上
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.bili_back),
                        tint = Color.White,
                    )
                }
                Text(
                    info?.title.orEmpty(),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
            }
        }

        val room = info
        if (room == null) {
            Spacer(Modifier.weight(1f))
        } else {
            // ---------- 主播行：头像 → 关注（就在头像右边）→ 昵称/分区 ----------
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = room.anchorFace,
                    contentDescription = room.anchorName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF2A2A2A)),
                )
                Spacer(Modifier.width(8.dp))
                val watched = room.watchedText.ifBlank { room.online.toString() }
                // 接口有时直接给 text_large「1.6万人看过」，本身就带「人」，别再拼一遍
                val watchers =
                    if ('人' in watched) watched else stringResource(R.string.live_watchers, watched)
                Button(
                    onClick = { toggleFollow() },
                    enabled = !followBusy,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    // 黑底页里显式给色：已关注用灰底白字，未关注交给主题
                    //（primary/onPrimary 的对比度主题本身保证，且在黑底上一定显眼）
                    colors = if (isFollowing) {
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3A3A3A),
                            contentColor = Color.White,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(
                        stringResource(if (isFollowing) R.string.live_following else R.string.live_follow),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        room.anchorName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(room.areaName, watchers)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        color = Color(0xFF9E9E9E),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E1E1E)))

            // ---------- 聊天区：历史 + 实时弹幕 ----------
            val listState = rememberLazyListState()
            LaunchedEffect(chat.size) {
                if (chat.isEmpty()) return@LaunchedEffect
                // 还留在底部才跟着滚，往上翻历史时不去拽人
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    ?: Int.MAX_VALUE
                if (lastVisible >= chat.size - 3) listState.animateScrollToItem(chat.size - 1)
            }
            if (chat.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.live_no_danmaku),
                        color = Color(0xFF777777),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    itemsIndexed(chat) { _, message ->
                        ChatRow(message)
                    }
                }
            }

            // ---------- 发弹幕 ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= MAX_DRAFT_LEN) draft = it },
                    placeholder = { Text(stringResource(R.string.live_input_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        // 黑底上一律显式给色，浅色主题下 onSurface 是黑的，会看不见字
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                        focusedPlaceholderColor = Color(0xFF777777),
                        unfocusedPlaceholderColor = Color(0xFF777777),
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color(0xFF4A4A4A),
                        focusedContainerColor = Color(0xFF151515),
                        unfocusedContainerColor = Color(0xFF151515),
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { sendDraft() },
                    enabled = !sending && draft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.live_send))
                }
            }
        }
    }
}

/** 一条聊天记录：昵称 + 正文，正文用弹幕自带颜色（太暗的提亮，否则黑底上看不见）。 */
@Composable
private fun ChatRow(message: BiliLiveMsg) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (message.userName.isNotBlank()) {
            Text(
                message.userName,
                color = Color(0xFF8AB4F8),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp).padding(end = 6.dp),
            )
        }
        Text(
            message.text,
            color = readableDanmakuColor(message.color),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 弹幕飘屏：只把新到的实时弹幕铺到画面上，横向从右往左滑，滑出左边就退场。
 *
 * 用帧回调驱动位置，轨道按「谁最久没用」挑，尽量不叠字；进房时已有的历史弹幕
 * 不进这里（否则一进来满屏都是旧消息），它们只出现在下面的聊天区。
 */
@Composable
private fun DanmakuLayer(messages: List<BiliLiveMsg>, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val floating = remember { mutableStateListOf<FloatingDanmaku>() }
    val laneLastStart = remember { LongArray(LANE_COUNT) }
    var lastSeen by remember { mutableStateOf<BiliLiveMsg?>(null) }
    var now by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now = it }
        }
    }

    LaunchedEffect(messages) {
        val latest = messages.lastOrNull() ?: return@LaunchedEffect
        // 列表整体重建时元素是复用的，靠引用比对认出「真的是新来的」
        if (latest === lastSeen) return@LaunchedEffect
        lastSeen = latest

        var lane = 0
        var oldest = Long.MAX_VALUE
        for (i in 0 until LANE_COUNT) {
            if (laneLastStart[i] < oldest) {
                oldest = laneLastStart[i]
                lane = i
            }
        }
        val startNs = System.nanoTime()
        laneLastStart[lane] = startNs
        if (floating.size >= MAX_FLOATING) floating.removeAt(0)
        floating.add(
            FloatingDanmaku(
                text = latest.text,
                color = readableDanmakuColor(latest.color),
                startNs = startNs,
                lane = lane,
            ),
        )
    }

    Canvas(modifier) {
        if (floating.isEmpty() || now == 0L) return@Canvas
        val speed = size.width / DURATION_NS
        val laneHeight = size.height / LANE_COUNT
        val expired = mutableListOf<FloatingDanmaku>()
        for (item in floating) {
            val elapsed = now - item.startNs
            if (elapsed < 0) continue
            val x = size.width - elapsed * speed
            if (x < -size.width) {
                expired.add(item)
                continue
            }
            val style = TextStyle(
                color = item.color,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val shadowStyle = style.copy(color = Color.Black)
            val layout = textMeasurer.measure(AnnotatedString(item.text), style)
            val shadow = textMeasurer.measure(AnnotatedString(item.text), shadowStyle)
            val y = item.lane * laneHeight + (laneHeight - layout.size.height) / 2
            if (x + layout.size.width < 0) {
                expired.add(item)
                continue
            }
            // 先垫一层黑描边再上色，亮画面上也读得清
            drawText(textLayoutResult = shadow, topLeft = Offset(x + 1.5f, y + 1.5f))
            drawText(textLayoutResult = layout, topLeft = Offset(x, y))
        }
        if (expired.isNotEmpty()) floating.removeAll(expired)
    }
}

/** 黑底上过暗的弹幕色直接换成白，免得整条弹幕隐身。 */
private fun readableDanmakuColor(argb: Int): Color {
    val color = Color(argb)
    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (luminance < 0.35f) Color.White else color
}
