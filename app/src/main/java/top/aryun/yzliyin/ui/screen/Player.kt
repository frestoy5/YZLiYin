package top.aryun.yzliyin.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.data.LyricEdits
import top.aryun.yzliyin.core.data.SessionStore
import top.aryun.yzliyin.core.lyric.LyricCandidate
import top.aryun.yzliyin.core.lyric.LyricConfidence
import top.aryun.yzliyin.core.lyric.LyricMatch
import top.aryun.yzliyin.core.lyric.LyricMatchSource
import top.aryun.yzliyin.core.model.Artist
import top.aryun.yzliyin.core.model.LyricLine
import top.aryun.yzliyin.core.model.Parsers
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.SongInfoEdit
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.CrossSourceMatch
import top.aryun.yzliyin.player.PlayState
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.rememberWindowWidthDp
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.ui.components.BiliQuadButton
import top.aryun.yzliyin.ui.components.formatDuration
import top.aryun.yzliyin.ui.components.labelText
import kotlin.math.sin

@Composable
fun PlayerScreen(nav: NavController) {
    val state by PlayerController.state.collectAsStateWithLifecycle()
    val song = state.current
    val context = LocalContext.current

    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricTip by remember { mutableStateOf<String?>(null) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    // 编辑歌词后自增，让下面的取词效果重跑一遍
    var lyricsRevision by remember { mutableStateOf(0) }

    // 点歌手名：B 站歌曲先问在哪个应用看 UP 主主页；合作曲列出全部歌手让选；
    // 只有一位歌手时不问，直接进主页。
    var biliUpMid by remember { mutableStateOf<Long?>(null) }

    // 选择框要展示的歌手。id 来自曲目自带的 artistIds；曲目没带 id（酷狗、旧缓存、
    // B 站 upperMid 缺失）时按名字搜网易云补上，所以这里存的是搜索结果而不是 Song。
    var artistPicker by remember { mutableStateOf<List<Artist>?>(null) }
    var artistSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 「更换音频」挂在屏幕层而不是 ⋮ 菜单里：取链时菜单会因 state.resolving
    // 切换成指示圈而销毁，对话框与取链协程若挂在菜单上会被连带取消。
    var showReplaceAudio by remember { mutableStateOf(false) }
    if (showReplaceAudio && song != null) {
        ReplaceAudioDialog(
            song = song,
            onPick = { picked ->
                // 立马换：先关对话框，取链播放在屏幕层的 scope 里跑完
                showReplaceAudio = false
                scope.launch {
                    val ok = runCatching {
                        PlayerController.replaceCurrent(context, picked)
                    }.getOrDefault(false)
                    val msg = if (ok) {
                        context.getString(R.string.pl_replace_audio_done, picked.name)
                    } else {
                        context.getString(R.string.pl_replace_audio_failed)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showReplaceAudio = false },
        )
    }

    fun noArtistToast() {
        Toast.makeText(context, context.getString(R.string.pl_no_artist), Toast.LENGTH_SHORT).show()
    }

    /**
     * 曲目没给歌手 id 时的兜底：拿歌手名去网易云搜，命中一条就直接进主页，
     * 多条弹选择框，一条都没有才提示。避免点了歌手名毫无反应。
     */
    fun searchArtistsByName(rawName: String) {
        // 酷狗把多位歌手拼成「甲, 乙」、网易云拼成「甲/乙」，先按分隔符拆开
        val names = rawName.split('/', ',', '，', '、')
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.pl_no_artist), Toast.LENGTH_SHORT).show()
            return@searchArtistsByName
        }
        scope.launch {
            artistSearching = true
            // 逐个名字搜并合并，最多查三位，避免一首合唱曲连发一串请求
            val found = names.take(3).flatMap { name ->
                runCatching { NeteaseApi.searchArtists(name, pageSize = 10) }
                    .onFailure { NPLogger.w("Player", "搜歌手失败 $name：${it.message}") }
                    .getOrNull()?.items.orEmpty()
            }.distinctBy { it.id }.filter { it.id.isNotEmpty() }
            artistSearching = false

            when {
                found.isEmpty() ->
                    Toast.makeText(context, context.getString(R.string.pl_artist_not_found, names.first()), Toast.LENGTH_SHORT).show()

                // 只有一条且名字对得上，没有歧义，直接进主页
                found.size == 1 && found[0].name == names.first() ->
                    nav.navigate(Routes.artist(found[0].id))

                else -> artistPicker = found
            }
        }
    }

    /**
     * 点歌手名。B 站先问在哪个应用看 UP 主主页（应用内 / B 站 App）；
     * 其余源按歌手人数走：一个直接进，两个及以上弹选择框。
     * 曲目没带歌手 id（酷狗、旧缓存）时按名字搜一遍，保证点了总有反馈。
     */
    val onArtistClick: (Song) -> Unit = { s ->
        val candidates = s.artistCandidates
        when {
            // B 站的 artistId 存的是 UP 主 mid，主页另有两个去处，不走网易云那条 artist 页
            s.source == Source.BILIBILI ->
                candidates.firstOrNull()?.second?.toLongOrNull()?.let { biliUpMid = it }
                    ?: run { if (s.artist.isBlank()) noArtistToast() else searchArtistsByName(s.artist) }

            candidates.size == 1 -> nav.navigate(Routes.artist(candidates.single().second))
            candidates.size > 1 -> artistPicker = candidates.map { Artist(it.second, it.first) }

            // 没有任何可用 id：别默默什么都不做
            else -> if (s.artist.isBlank()) noArtistToast() else searchArtistsByName(s.artist)
        }
    }

    // 「查看投稿」里选「在应用内播放」时，进 B 站投稿的页内播放页
    val openBiliInApp: () -> Unit = {
        song?.let { s ->
            nav.navigate(
                Routes.biliVideo(s.playId.ifEmpty { s.id }, s.playSubId, s.name),
            )
        }
    }

    // 歌词外观（字号 / 波浪 / 光亮），由三个点菜单里的「歌词样式」改
    var lyricStyle by remember { mutableStateOf(LyricStyle.load(AppGraph.store)) }

    LaunchedEffect(song?.key, lyricsRevision) {
        lyrics = emptyList()
        lyricTip = null
        val s = song ?: return@LaunchedEffect
        // 用户改过歌词就用改过的：内容为空也算（等于把这首歌的歌词清空），不再回落源站
        LyricEdits.read(s.key)?.let { edited ->
            val parsed = Parsers.parseEditedLrc(edited)
            if (parsed.isEmpty()) lyricTip = context.getString(R.string.pl_lyric_none) else {
                lyrics = parsed
                lyricTip = null
            }
            return@LaunchedEffect
        }
        lyricTip = context.getString(R.string.pl_lyric_loading)
        runCatching { CrossSourceMatch.lyrics(s) }
            .onSuccess {
                if (it.isEmpty()) lyricTip = context.getString(R.string.pl_lyric_none) else {
                    lyrics = it
                    lyricTip = null
                }
            }
            .onFailure { lyricTip = context.getString(R.string.pl_lyric_load_failed) }
    }

    val listState = rememberLazyListState()
    val activeIndex = activeLyricIndex(lyrics, state.positionMs)

    // 平板 / 横屏（宽度 ≥ 600dp）：主区分成左右两栏，右边歌词常驻，不用「查看歌词」开关
    // 宽度取真实窗口测量：与 App.kt 的侧边导航判定同源，避免一处刷新一处冻结的错位
    val wide = rememberWindowWidthDp() >= WIDE_MIN_WIDTH_DP
    val lyricPaneVisible = showLyrics || wide

    // 歌词区实际高度（px）。滚动偏移与上下留白都按它算，不能再写死像素：
    // -220px 是按手机歌词区挑的，手表这类小屏上会把当前行滚出画面。
    var lyricPaneHeightPx by remember { mutableStateOf(0) }
    val lyricPanePadding = if (lyricPaneHeightPx > 0) {
        with(LocalDensity.current) { (lyricPaneHeightPx.toDp() * 0.45f).coerceAtMost(120.dp) }
    } else {
        120.dp
    }

    LaunchedEffect(activeIndex, lyricPaneVisible, lyricPaneHeightPx) {
        if (activeIndex < 0 || !lyricPaneVisible || !AppGraph.store.lyricAutoScroll) return@LaunchedEffect
        // 歌词区还没量到（面板刚显示）或量不到时不滚，免得按错的偏移跳
        if (lyricPaneHeightPx <= 0) return@LaunchedEffect
        // 手机上仍是 -220px；歌词区比它矮时（手表）收到半区高，当前行永远留在画面里
        val offset = (lyricPaneHeightPx / 2).coerceAtMost(220)
        runCatching { listState.animateScrollToItem(activeIndex, -offset) }
    }

    if (song == null) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.pl_nothing_playing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.pl_back)) }
            }
            IconButton(
                onClick = { nav.popBackStack() },
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(4.dp)
                    .align(Alignment.TopStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.pl_back))
            }
        }
        return
    }

    val progress =
        if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        else 0f

    // B 站歌曲点 UP 主名：在应用内看投稿，还是跳去 B 站 App 看主页
    biliUpMid?.let { mid ->
        val upName = song.artist.ifBlank { stringResource(R.string.pl_this_up) }
        AlertDialog(
            onDismissRequest = { biliUpMid = null },
            title = { Text(stringResource(R.string.pl_view_up_title, upName)) },
            text = { Text(stringResource(R.string.pl_choose_app)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        biliUpMid = null
                        nav.navigate(Routes.biliUp(mid.toString()))
                    },
                ) { Text(stringResource(R.string.pl_watch_in_app)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        biliUpMid = null
                        openBilibiliSpace(context, mid)
                    },
                ) { Text(stringResource(R.string.pl_watch_in_bili)) }
            },
        )
    }

    // 合作曲 / 按名字搜出来的歌手：列出全部，点谁进谁的主页
    artistPicker?.let { list ->
        AlertDialog(
            onDismissRequest = { artistPicker = null },
            title = { Text(stringResource(R.string.pl_pick_artist)) },
            text = {
                // 弹窗正文给的高度约束有限，先截断：合作歌手极少超过三五个，
                // 搜名字时若命中一串同名歌手也不该把对话框撑出屏幕
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(list, key = { _, a -> a.id + a.name }) { _, a ->
                        TextButton(
                            onClick = {
                                artistPicker = null
                                nav.navigate(Routes.artist(a.id))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(a.name, Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { artistPicker = null }) { Text(stringResource(R.string.pl_cancel)) } },
        )
    }

    // 歌曲没带歌手 id 时按名字搜，转圈期间给个反馈，免得像是点不动
    if (artistSearching) {
        AlertDialog(
            onDismissRequest = { artistSearching = false },
            title = { Text(stringResource(R.string.pl_pick_artist)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.pl_searching_artist))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { artistSearching = false }) { Text(stringResource(R.string.pl_cancel)) } },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.pl_collapse))
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            song.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (song.vip) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    "VIP",
                                    Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onArtistClick(song) },
                    )
                }
                // B 站歌曲：评论与一键四连入口（其它源不显示）
                if (song.source == Source.BILIBILI) {
                    IconButton(onClick = {
                        nav.navigate(
                            Routes.biliComments(song.playId.ifEmpty { song.id }, song.name),
                        )
                    }) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline,
                            contentDescription = stringResource(R.string.pl_comment),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BiliQuadButton(
                        bvid = song.playId.ifEmpty { song.id },
                        nav = nav,
                    )
                }
                if (state.resolving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    PlayerMoreMenu(
                        nav = nav,
                        song = song,
                        seedLyrics = lyrics,
                        onLyricsChanged = { lyricsRevision++ },
                        onReplaceAudio = { showReplaceAudio = true },
                        style = lyricStyle,
                        onStyleChange = { next ->
                            lyricStyle = next
                            next.save(AppGraph.store)
                        },
                    )
                }
            }

            // 一起听房间状态条（在房间中才显示，点击进入房间页）
            val ltSession by AppGraph.listenTogether.sessionState.collectAsStateWithLifecycle()
            if (!ltSession.roomId.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clickable { nav.navigate(Routes.LISTEN_TOGETHER) },
                ) {
                    Text(
                        text = stringResource(
                            R.string.pl_lt_status,
                            stringResource(
                                if (ltSession.role == "controller") R.string.pl_lt_host else R.string.pl_lt_listener,
                            ),
                            ltSession.roomId.orEmpty(),
                        ),
                        Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            if (wide) {
                // 平板 / 横屏：左边封面 + 进度条 + 播放控制，右边整列歌词，两栏并排
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlayerCover(
                            song = song,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        PlayerTransport(
                            song = song,
                            state = state,
                            progress = progress,
                            seeking = seeking,
                            onSeekingChange = { seeking = it },
                            onSeekingDone = { target ->
                                if (target != null && state.durationMs > 0) {
                                    PlayerController.seekTo((target * state.durationMs).toLong())
                                }
                                seeking = null
                            },
                            // 宽屏下歌词常驻右栏，不需要「查看歌词」开关
                            showLyricsToggle = false,
                            lyricsShown = true,
                            onToggleLyrics = {},
                            onOpenBiliInApp = openBiliInApp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 8.dp))

                    LyricsPane(
                        lyrics = lyrics,
                        tip = lyricTip,
                        activeIndex = activeIndex,
                        listState = listState,
                        style = lyricStyle,
                        onLineClick = { PlayerController.seekTo(it) },
                        verticalPadding = lyricPanePadding,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp)
                            .onSizeChanged { lyricPaneHeightPx = it.height },
                    )
                }
            } else {
                // 手机竖屏：封面 / 歌词上下切换，进度条与控制在底部
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!showLyrics) {
                        PlayerCover(
                            song = song,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LyricsPane(
                            lyrics = lyrics,
                            tip = lyricTip,
                            activeIndex = activeIndex,
                            listState = listState,
                            style = lyricStyle,
                            onLineClick = { PlayerController.seekTo(it) },
                            verticalPadding = lyricPanePadding,
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { lyricPaneHeightPx = it.height },
                        )
                    }
                }

                PlayerTransport(
                    song = song,
                    state = state,
                    progress = progress,
                    seeking = seeking,
                    onSeekingChange = { seeking = it },
                    onSeekingDone = { target ->
                        if (target != null && state.durationMs > 0) {
                            PlayerController.seekTo((target * state.durationMs).toLong())
                        }
                        seeking = null
                    },
                    showLyricsToggle = true,
                    lyricsShown = showLyrics,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    onOpenBiliInApp = openBiliInApp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 宽屏阈值，与 App.kt 的 WIDE_SCREEN_MIN_DP 保持一致：达到即播放页改排左右两栏。 */
private const val WIDE_MIN_WIDTH_DP = 600

/**
 * 方形封面。宽屏（平板 / 横屏）下面板高度可能小于宽度，
 * 这里取宽高的较小值做成正方形，避免封面溢出面板。
 */
@Composable
private fun PlayerCover(song: Song, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        // 封面缺失或加载失败时给兜底占位，否则播放页是一整块空白
        var coverFailed by remember(song.cover) { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .size(side)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (song.cover.isNotBlank() && !coverFailed) {
                AsyncImage(
                    model = song.cover,
                    contentDescription = song.name,
                    contentScale = ContentScale.Crop,
                    onSuccess = { coverFailed = false },
                    onError = { coverFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(96.dp),
                )
            }
        }
    }
}

/** 歌词面板：有歌词时是可滚动列表，没有就显示提示。 */
@Composable
private fun LyricsPane(
    lyrics: List<LyricLine>,
    tip: String?,
    activeIndex: Int,
    listState: LazyListState,
    style: LyricStyle,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 上下留白：够首尾歌词滚到画面中间就行，小屏（手表）按区高收，免得占掉大半高度。 */
    verticalPadding: Dp = 120.dp,
) {
    if (lyrics.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                tip ?: stringResource(R.string.pl_lyric_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(lyrics) { i, line ->
            LyricLineView(
                line = line,
                active = i == activeIndex,
                style = style,
                onClick = { onLineClick(line.timeMs) },
            )
        }
    }
}

/** 进度条 + 「查看歌词」开关 + 播放控制 + 错误提示 + 音源标识。 */
@Composable
private fun PlayerTransport(
    song: Song,
    state: PlayState,
    progress: Float,
    seeking: Float?,
    onSeekingChange: (Float) -> Unit,
    onSeekingDone: (Float?) -> Unit,
    showLyricsToggle: Boolean,
    lyricsShown: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenBiliInApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // B 站投稿的打开方式：先问跳 B 站 App，还是就地在本应用里播
    var showOpenPicker by remember(song.key) { mutableStateOf(false) }
    val biliBvid = song.playId.ifEmpty { song.id }
        .takeIf { song.source == Source.BILIBILI && it.isNotBlank() }

    if (showOpenPicker && biliBvid != null) {
        AlertDialog(
            onDismissRequest = { showOpenPicker = false },
            title = { Text(stringResource(R.string.pl_open_post_title)) },
            text = { Text(stringResource(R.string.pl_open_post_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOpenPicker = false
                        openBilibiliVideo(context, biliBvid)
                    },
                ) { Text(stringResource(R.string.pl_open_in_bili_app)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOpenPicker = false
                        onOpenBiliInApp()
                    },
                ) { Text(stringResource(R.string.pl_play_in_app)) }
            },
        )
    }

    Column(modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shownMs = ((seeking ?: progress) * state.durationMs).toLong()
            Text(
                formatDuration(shownMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = seeking ?: progress,
                onValueChange = onSeekingChange,
                onValueChangeFinished = { onSeekingDone(seeking) },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                formatDuration(state.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 「查看歌词」胶囊按钮：放在进度条与播放控制图标之间（宽屏下常驻歌词，不需要）。
        // B 站歌曲在其右侧再并排一个「查看投稿」，点了先问跳 App 还是就地播。
        if (showLyricsToggle || biliBvid != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showLyricsToggle) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onToggleLyrics() },
                    ) {
                        Text(
                            if (lyricsShown) stringResource(R.string.pl_back_to_cover)
                            else stringResource(R.string.pl_view_lyrics),
                            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                biliBvid?.let {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { showOpenPicker = true },
                    ) {
                        Text(
                            stringResource(R.string.pl_view_post),
                            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { PlayerController.toggleShuffle() }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = stringResource(R.string.pl_shuffle),
                    tint = if (state.shuffle) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = { PlayerController.prev() }) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.pl_prev),
                    modifier = Modifier.size(34.dp),
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(62.dp)
                    .clickable { PlayerController.togglePlay() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) {
                            stringResource(R.string.pl_pause)
                        } else {
                            stringResource(R.string.pl_play)
                        },
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            IconButton(onClick = { PlayerController.next() }) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.pl_next),
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = { PlayerController.cycleRepeatMode() }) {
                val repeatOne = state.repeatMode == Player.REPEAT_MODE_ONE
                Icon(
                    if (repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> stringResource(R.string.pl_repeat_one)
                        Player.REPEAT_MODE_ALL -> stringResource(R.string.pl_repeat_all)
                        else -> stringResource(R.string.pl_repeat_off)
                    },
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        state.error?.let { msg ->
            Text(
                msg,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        // 右下角：当前这首歌实际由哪个客户端提供播放链接（跨源找音源后可能已换源）
        SourceBadge(
            songSource = song.source,
            resolvedSource = state.currentSource,
            fromCache = state.fromCache,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

private fun activeLyricIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
    if (lyrics.isEmpty()) return -1
    var idx = -1
    for (i in lyrics.indices) {
        if (lyrics[i].timeMs <= positionMs) idx = i else break
    }
    return idx
}

// ================= 三点菜单 =================

private data class QualityOption(val id: String, @StringRes val label: Int)

private val neteaseQualities = listOf(
    QualityOption("standard", R.string.pl_q_standard),
    QualityOption("higher", R.string.pl_q_higher),
    QualityOption("exhigh", R.string.pl_q_exhigh),
    QualityOption("lossless", R.string.pl_q_lossless),
    QualityOption("hires", R.string.pl_q_hires),
)

private val kugouQualities = listOf(
    QualityOption("auto", R.string.pl_q_auto),
    QualityOption("128", R.string.pl_q_128k),
    QualityOption("320", R.string.pl_q_320k),
    QualityOption("flac", R.string.pl_q_lossless),
    QualityOption("high", R.string.pl_q_kugou_high),
    QualityOption("super", R.string.pl_q_hi_res),
)

private val biliQualities = listOf(
    QualityOption("auto", R.string.pl_q_auto),
    QualityOption("64", R.string.pl_q_64k),
    QualityOption("132", R.string.pl_q_132k),
    QualityOption("192", R.string.pl_q_192k),
)

/** YouTube 音质档位：id 与移植栈 buildYouTubeQualityOptions 的 qualityKey 一致。 */
private val youtubeQualities = listOf(
    QualityOption("low", R.string.pl_q_low),
    QualityOption("medium", R.string.pl_q_medium),
    QualityOption("high", R.string.pl_q_high),
    QualityOption("very_high", R.string.pl_q_exhigh),
)

/** 播放倍速档位。 */
private val playbackSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 播放页右上角的「更多」菜单：一起听 / 更换音频 / 匹配歌词 / 编辑歌曲信息 / 音质 / 倍速 / 歌词样式。
 * （原先的评论入口按需求已移除，评论仍可从歌单、榜单等列表页进入。）
 */
@Composable
private fun PlayerMoreMenu(
    nav: NavController,
    song: Song,
    seedLyrics: List<LyricLine>,
    onLyricsChanged: () -> Unit,
    onReplaceAudio: () -> Unit,
    style: LyricStyle,
    onStyleChange: (LyricStyle) -> Unit,
) {
    val songSource = PlayerController.state.collectAsStateWithLifecycle().value.current?.source
        ?: Source.NETEASE

    var expanded by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showLyricStyle by remember { mutableStateOf(false) }
    var showEditInfo by remember { mutableStateOf(false) }
    var showLyricMatch by remember { mutableStateOf(false) }

    // 打开子菜单时收起主菜单，避免两层浮层叠在一起
    fun openQuality() { expanded = false; showQuality = true }
    fun openSpeed() { expanded = false; showSpeed = true }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.pl_more))
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pl_listen_together)) },
                leadingIcon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
                onClick = {
                    expanded = false
                    nav.navigate(Routes.LISTEN_TOGETHER)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pl_replace_audio)) },
                leadingIcon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                onClick = {
                    expanded = false
                    onReplaceAudio()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pl_match_lyrics)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                onClick = {
                    expanded = false
                    showLyricMatch = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pl_edit_info)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    showEditInfo = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pl_quality)) },
                leadingIcon = { Icon(Icons.Filled.HighQuality, contentDescription = null) },
                trailingIcon = {
                    Text(
                        currentQualityLabel(songSource),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { openQuality() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pl_speed)) },
                leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null) },
                trailingIcon = {
                    Text(
                        currentSpeedLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { openSpeed() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pl_lyric_style)) },
                leadingIcon = { Icon(Icons.Filled.FormatSize, contentDescription = null) },
                trailingIcon = {
                    Text(
                        styleSummary(style),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { expanded = false; showLyricStyle = true },
            )
        }

        // 音质：按当前曲目的源给出对应档位，选中后写回设置并重新取链
        DropdownMenu(expanded = showQuality, onDismissRequest = { showQuality = false }) {
            qualityOptionsFor(songSource).forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    leadingIcon = {
                        if (currentQualityId(songSource) == option.id) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        applyQuality(songSource, option)
                        showQuality = false
                    },
                )
            }
        }

        // 倍速：直接作用于当前播放器，切歌后仍保持
        DropdownMenu(expanded = showSpeed, onDismissRequest = { showSpeed = false }) {
            playbackSpeeds.forEach { rate ->
                DropdownMenuItem(
                    text = { Text(if (rate % 1f == 0f) "${rate.toInt()}x" else "${rate}x") },
                    leadingIcon = {
                        if (kotlin.math.abs(PlayerController.currentPlaybackRate() - rate) < 0.01f) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        PlayerController.setPlaybackRate(rate)
                        showSpeed = false
                    },
                )
            }
        }

        if (showLyricStyle) {
            LyricStyleDialog(
                style = style,
                onStyleChange = onStyleChange,
                onDismiss = { showLyricStyle = false },
            )
        }

        if (showEditInfo) {
            EditSongInfoDialog(
                song = song,
                seedLyrics = seedLyrics,
                onLyricsChanged = onLyricsChanged,
                onDismiss = { showEditInfo = false },
            )
        }

        if (showLyricMatch) {
            LyricMatchDialog(
                song = song,
                onLyricsChanged = onLyricsChanged,
                onDismiss = { showLyricMatch = false },
            )
        }
    }
}

private fun qualityOptionsFor(source: Source): List<QualityOption> = when (source) {
    Source.KUGOU -> kugouQualities
    Source.BILIBILI -> biliQualities
    Source.NETEASE -> neteaseQualities
    Source.YOUTUBE -> youtubeQualities
    // 本机音乐直接播文件，没有取链画质可言
    Source.LOCAL -> emptyList()
}

private fun currentQualityId(source: Source): String = when (source) {
    Source.KUGOU -> AppGraph.store.kugouQuality
    Source.BILIBILI -> AppGraph.store.biliQuality
    Source.NETEASE -> AppGraph.store.playQuality
    Source.YOUTUBE -> AppGraph.store.youtubeQuality
    Source.LOCAL -> ""
}

/** 当前源选中的音质档位名；档位 label 是资源 id，这里转成当前语言的文案。 */
@Composable
private fun currentQualityLabel(source: Source): String {
    val id = currentQualityId(source)
    val label = qualityOptionsFor(source).firstOrNull { it.id == id }?.label
        ?: R.string.pl_q_default
    return stringResource(label)
}

private fun currentSpeedLabel(): String {
    val rate = PlayerController.currentPlaybackRate()
    return if (rate % 1f == 0f) "${rate.toInt()}x" else "${rate}x"
}

/** 写入对应源的音质设置；当前源取链的曲子立即重新解析。 */
private fun applyQuality(source: Source, option: QualityOption) {
    val store = AppGraph.store
    when (source) {
        Source.KUGOU -> store.kugouQuality = option.id
        Source.BILIBILI -> store.biliQuality = option.id
        Source.NETEASE -> store.playQuality = option.id
        Source.YOUTUBE -> store.youtubeQuality = option.id
        // 本机音乐没有音质档位，这一支只是让 when 覆盖到它
        Source.LOCAL -> Unit
    }
    kotlinx.coroutines.MainScope().launch {
        runCatching { PlayerController.reapplyQuality() }
    }
}

// ================= 右下角客户端标识 =================

/**
 * 右下角小字：这首歌的播放链接实际来自哪个客户端。
 *
 * 跨源找音源后可能与曲目自身的源不同，此时额外标注「已切换」；
 * 命中本机缓存时标注「本地缓存」。
 */
@Composable
private fun SourceBadge(
    songSource: Source,
    resolvedSource: Source?,
    fromCache: Boolean,
    modifier: Modifier = Modifier,
) {
    // 还没取到链接时不显示，避免出现空白标签
    val source = resolvedSource ?: return

    val (label, note) = when {
        fromCache -> source.labelText() to stringResource(R.string.pl_source_cache)
        source != songSource -> source.labelText() to stringResource(R.string.pl_source_switched)
        else -> source.labelText() to null
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (note == null) {
                        stringResource(R.string.pl_source_from, label)
                    } else {
                        stringResource(R.string.pl_source_from_note, label, note)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ================= 歌词样式（字号 / 弯曲 / 光亮） =================

/**
 * 播放页歌词的外观档位，改完立即生效并落盘。
 *
 * 波浪与光亮只作用于**当前高亮的那一行**，其它行保持正常排版，
 * 免得整屏文字都在动，读不了。
 * [showTranslation] 管原文下面那行译文显不显示（译文来自歌词接口，不负责现翻）。
 */
internal data class LyricStyle(
    val fontSize: Float = 1f,
    val wave: Float = 0f,
    val glow: Float = 0f,
    val showTranslation: Boolean = true,
) {
    fun save(store: SessionStore) {
        store.lyricFontSize = fontSize
        store.lyricWave = wave
        store.lyricGlow = glow
        store.lyricShowTranslation = showTranslation
    }

    companion object {
        fun load(store: SessionStore) = LyricStyle(
            fontSize = store.lyricFontSize,
            wave = store.lyricWave,
            glow = store.lyricGlow,
            showTranslation = store.lyricShowTranslation,
        )
    }
}

/** 菜单项右侧的摘要，例如「115% · 波浪 · 光亮」。 */
@Composable
private fun styleSummary(s: LyricStyle): String {
    val parts = mutableListOf("${(s.fontSize * 100).toInt()}%")
    if (s.wave > 0f) parts.add(stringResource(R.string.pl_style_wave))
    if (s.glow > 0f) parts.add(stringResource(R.string.pl_style_glow))
    return parts.joinToString(" · ")
}

/** 三个滑杆 + 实时预览。 */
/**
 * 编辑歌曲信息：改歌名 / 歌手 / 封面链接（参考 NeriPlayer 的编辑面板）。
 *
 * 保存后写进 [SessionStore] 并就地更新播放队列，播放页、迷你条、通知栏同步换新信息，
 * 播放不断、进度不动；三项都保持原值时按撤销处理，不留下编辑记录。
 * 「恢复原信息」还原的是**首次编辑前**源站给的值，不会把上一次的编辑当成原值。
 */
@Composable
private fun EditSongInfoDialog(
    song: Song,
    seedLyrics: List<LyricLine>,
    onLyricsChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val saved = remember(song.key) { AppGraph.store.songInfoEdit(song.key) }
    var showLyricsEditor by remember(song.key) { mutableStateOf(false) }
    var showLyricMatch by remember(song.key) { mutableStateOf(false) }
    // 歌词是否已被手工编辑过（按钮右侧的状态提示）；读文件只在首次与编辑后各来一次
    var lyricsEdited by remember(song.key) { mutableStateOf(LyricEdits.read(song.key) != null) }

    // 原始信息：编辑过就取编辑记录里的 base*，否则就是当前值
    val baseName = (saved?.baseName ?: song.name).ifBlank { song.name }.trim()
    val baseArtist = (saved?.baseArtist ?: song.artist).ifBlank { song.artist }.trim()
    val baseCover = (saved?.baseCover ?: song.cover).ifBlank { song.cover }.trim()

    var name by remember(song.key) { mutableStateOf(song.name) }
    var artist by remember(song.key) { mutableStateOf(song.artist) }
    var cover by remember(song.key) { mutableStateOf(song.cover) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pl_edit_info)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Text(
                        stringResource(R.string.pl_edit_info_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                EditInfoField(R.string.pl_field_name, name, { name = it }, { name = baseName })
                EditInfoField(R.string.pl_field_artist, artist, { artist = it }, { artist = baseArtist })
                EditInfoField(R.string.pl_field_cover, cover, { cover = it }, { cover = baseCover })

                OutlinedButton(
                    onClick = { showLyricsEditor = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pl_edit_lyrics))
                    Spacer(Modifier.weight(1f, fill = true))
                    Text(
                        text = when {
                            lyricsEdited -> stringResource(R.string.pl_lyrics_edited)
                            seedLyrics.isEmpty() -> stringResource(R.string.pl_none)
                            else -> stringResource(R.string.pl_line_count, seedLyrics.size)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedButton(
                    onClick = { showLyricMatch = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pl_match_lyrics))
                    Spacer(Modifier.weight(1f, fill = true))
                    Text(
                        text = stringResource(R.string.pl_match_lyrics_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        textAlign = TextAlign.End,
                    )
                }

                OutlinedButton(
                    onClick = {
                        name = baseName
                        artist = baseArtist
                        cover = baseCover
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pl_restore_info))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    commitSongInfoEdit(song, saved, name, artist, cover, baseName, baseArtist, baseCover)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.pl_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pl_cancel)) } },
    )

    // 歌词编辑器叠在信息面板之上，保存/恢复后由它负责让播放页重取歌词
    if (showLyricsEditor) {
        LyricsEditorDialog(
            song = song,
            seedLines = seedLyrics,
            onLyricsChanged = {
                lyricsEdited = LyricEdits.read(song.key) != null
                onLyricsChanged()
            },
            onDismiss = { showLyricsEditor = false },
        )
    }

    // 「匹配歌词」叠在最上层：选中候选后同样写进 LyricEdits，播放页重取歌词
    if (showLyricMatch) {
        LyricMatchDialog(
            song = song,
            onLyricsChanged = {
                lyricsEdited = LyricEdits.read(song.key) != null
                onLyricsChanged()
            },
            onDismiss = { showLyricMatch = false },
        )
    }
}

/**
 * 歌词编辑器：把当前歌词摊成 LRC 文本给用户改，保存后立刻替换播放页正在显示的歌词。
 *
 * 存的是 LRC 原文（译文另起一行、与原文共用时间戳），存取见 [LyricEdits]；
 * 内容留空保存等于声明这首歌没有歌词，不再回落源站。
 */
@Composable
private fun LyricsEditorDialog(
    song: Song,
    seedLines: List<LyricLine>,
    onLyricsChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 已有编辑内容就用它，其次才是当前正在播的歌词
    var text by remember(song.key) {
        mutableStateOf(LyricEdits.read(song.key) ?: Parsers.toLrc(seedLines))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pl_edit_lyrics)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.pl_lrc_label)) },
                    placeholder = { Text(stringResource(R.string.pl_lrc_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                )
                OutlinedButton(
                    onClick = {
                        LyricEdits.clear(song.key)
                        onLyricsChanged()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pl_restore_lyrics))
                }
                Text(
                    stringResource(R.string.pl_lrc_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    LyricEdits.save(song.key, text)
                    onLyricsChanged()
                    onDismiss()
                },
            ) { Text(stringResource(R.string.pl_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pl_cancel)) } },
    )
}

/** 「更换音频」要搜的源：四个源都搜——跨源匹配可能跳到任何一个，换也得哪个源都能选。 */
private val REPLACE_AUDIO_SOURCES = listOf(
    Source.NETEASE,
    Source.KUGOU,
    Source.BILIBILI,
    Source.YOUTUBE,
)

/** 单源搜索超时：YouTube 走抓取可能偏慢，超时当没结果，不拖垮整次搜索。 */
private const val REPLACE_AUDIO_TIMEOUT_MS = 8_000L

/**
 * 「更换音频」：跨源匹配跳到错误的歌（现场版 / 翻唱 / 张冠李戴）时，
 * 从各平台搜索结果里挑一首对的，就地顶替队列当前位并从头播放（其余队列不动）。
 *
 * 关键字默认填当前曲的「歌名 歌手」，打开就自动搜一轮；四个源并行、单源超时 8 秒，
 * 超时的源当作没结果，四个源全失败才报错。当前正在播的这条会从结果里剔掉——换它等于没换。
 *
 * 点选即交给 [onPick] 并随手关掉对话框；取链与播放在**屏幕层**的协程里跑
 * （PlayerScreen 挂载、用 PlayerScreen 的 scope 启动）——⋮ 菜单在取链时会因
 * state.resolving 切成指示圈而销毁，协程若挂在菜单上会被连带取消。
 */
@Composable
private fun ReplaceAudioDialog(
    song: Song,
    onPick: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyword by remember(song.key) {
        mutableStateOf(listOf(song.name, song.artist).filter { it.isNotBlank() }.joinToString(" "))
    }
    var searching by remember(song.key) { mutableStateOf(false) }
    var searched by remember(song.key) { mutableStateOf(false) }
    var error by remember(song.key) { mutableStateOf<String?>(null) }
    var results by remember(song.key) { mutableStateOf<List<Song>>(emptyList()) }

    fun search() {
        if (searching || keyword.isBlank()) return
        error = null
        searched = false
        results = emptyList()
        searching = true
        scope.launch {
            val outcomes = REPLACE_AUDIO_SOURCES.map { source ->
                async {
                    withTimeoutOrNull(REPLACE_AUDIO_TIMEOUT_MS) {
                        runCatching { CrossSourceMatch.search(source, keyword) }
                    }
                }
            }.awaitAll()
            if (outcomes.all { it == null || it.isFailure }) {
                val reason = outcomes.filterNotNull().firstOrNull()
                    ?.exceptionOrNull()?.message.orEmpty()
                NPLogger.w("ReplaceAudio", "搜索失败：$reason")
                error = context.getString(
                    R.string.pl_replace_audio_error,
                    reason.ifEmpty { "timeout" },
                )
                results = emptyList()
            } else {
                results = outcomes.filterNotNull()
                    .mapNotNull { it.getOrNull() }
                    .flatten()
                    .filterNot { it.key == song.key }
            }
            searched = true
            searching = false
        }
    }

    // 打开就按默认关键字搜一轮，省掉一次点按
    LaunchedEffect(song.key) { search() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pl_replace_audio)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.pl_replace_audio_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text(stringResource(R.string.pl_replace_audio_keyword)) },
                    placeholder = { Text(stringResource(R.string.pl_match_keyword_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedButton(
                    onClick = { search() },
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pl_match_search))
                }

                when {
                    searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.pl_replace_audio_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    error != null -> Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    results.isEmpty() && searched -> Text(
                        stringResource(R.string.pl_replace_audio_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(results, key = { _, it -> it.key }) { _, picked ->
                            ReplaceAudioResultRow(song = picked, onClick = {
                                // 立马换：点选即换、对话框随手关，取链播放在调用方协程里跑
                                onPick(picked)
                                onDismiss()
                            })
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pl_cancel))
            }
        },
    )
}

/** 「更换音频」结果里的一条：标题 + 歌手 · 源 · 时长。 */
@Composable
private fun ReplaceAudioResultRow(song: Song, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            song.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(
                song.artist.ifBlank { null },
                song.source.labelText(),
                song.durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
            ).filterNotNull().joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「匹配歌词」：把关键字丢给网易云 / 酷狗搜出多条候选，由用户挑一条对的替换当前歌词。
 *
 * 做的事和播放页自动取词一样（跨源找一首匹配的歌再下载它的 LRC + 译文），
 * 区别是**不替用户做决定**：自动匹配挑到现场版 / 翻唱时，这里可以自己点一条对的。
 * 选中即写进 [LyricEdits]（与手工编辑同一份存储），播放页立刻重取歌词，
 * 之后仍可在「编辑歌词」里继续改，或「恢复原歌词」回到源站。
 */
@Composable
private fun LyricMatchDialog(
    song: Song,
    onLyricsChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyword by remember(song.key) {
        mutableStateOf(listOf(song.name, song.artist).filter { it.isNotBlank() }.joinToString(" "))
    }
    var platforms by remember(song.key) { mutableStateOf(LyricMatchSource.entries.toSet()) }
    var searching by remember(song.key) { mutableStateOf(false) }
    var searched by remember(song.key) { mutableStateOf(false) }
    var error by remember(song.key) { mutableStateOf<String?>(null) }
    var results by remember(song.key) { mutableStateOf<List<LyricCandidate>>(emptyList()) }

    fun search() {
        if (searching) return
        if (platforms.isEmpty()) {
            error = context.getString(R.string.pl_match_no_source)
            return
        }
        error = null
        searched = false
        results = emptyList()
        searching = true
        scope.launch {
            val found = runCatching {
                LyricMatch.search(song, keyword, LyricMatchSource.entries.filter { it in platforms })
            }.onFailure {
                NPLogger.w("LyricMatch", "匹配失败：${it.message}")
                error = context.getString(R.string.pl_match_error, it.message.orEmpty())
            }.getOrNull().orEmpty()
            results = found
            searched = true
            searching = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pl_match_lyrics)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text(stringResource(R.string.pl_match_keyword)) },
                    placeholder = { Text(stringResource(R.string.pl_match_keyword_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column {
                    Text(
                        stringResource(R.string.pl_match_platforms),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LyricMatchSource.entries.forEach { source ->
                        val checked = source in platforms
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    platforms = if (checked) platforms - source else platforms + source
                                },
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on -> platforms = if (on) platforms + source else platforms - source },
                            )
                            Text(matchSourceLabel(source), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                OutlinedButton(
                    onClick = { search() },
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pl_match_search))
                }

                when {
                    searching -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.pl_match_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    error != null -> Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    // 还没搜过：先说明这一步要干什么
                    !searched -> Text(
                        stringResource(R.string.pl_match_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    results.isEmpty() -> Text(
                        stringResource(R.string.pl_match_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(results, key = { _, it -> it.identity }) { _, candidate ->
                            LyricMatchResultRow(candidate = candidate, onClick = {
                                LyricEdits.save(song.key, Parsers.toLrc(candidate.lines))
                                onLyricsChanged()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.pl_match_applied),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onDismiss()
                            })
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pl_cancel)) } },
    )
}

/** 「匹配歌词」候选列表里的一条：标题 + 歌手 + 来源 / 时长差 / 置信度 / 行数。 */
@Composable
private fun LyricMatchResultRow(candidate: LyricCandidate, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            candidate.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            candidate.artist.ifBlank { stringResource(R.string.pl_none) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            MatchTag(matchSourceLabel(candidate.source), primary = true)
            MatchTag(
                stringResource(R.string.pl_match_score, candidate.score),
                primary = false,
            )
            MatchTag(
                stringResource(
                    when (candidate.confidence) {
                        LyricConfidence.HIGH -> R.string.pl_match_confidence_high
                        LyricConfidence.MEDIUM -> R.string.pl_match_confidence_medium
                        LyricConfidence.LOW -> R.string.pl_match_confidence_low
                    },
                ),
                primary = false,
            )
        }
        // 换行放第二排：弹窗正文宽度有限，一行塞不下来源 / 分数 / 置信度 / 时长差 / 行数
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            candidate.durationDeltaMs?.let { delta ->
                Text(
                    stringResource(R.string.pl_match_duration_delta, formatDuration(delta)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.pl_line_count, "${candidate.lines.size}"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (candidate.translatedLines > 0) {
                Text(
                    stringResource(R.string.pl_match_has_translation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 候选行里的小标签。 */
@Composable
private fun MatchTag(text: String, primary: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (primary) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** 平台名，取自字符串资源。 */
@Composable
private fun matchSourceLabel(source: LyricMatchSource): String = stringResource(
    when (source) {
        LyricMatchSource.NETEASE -> R.string.pl_match_source_netease
        LyricMatchSource.KUGOU -> R.string.pl_match_source_kugou
    },
)

/** 编辑面板里的单行输入框，右侧图标一键还原该项的原值。 */
@Composable
private fun EditInfoField(
    @StringRes label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    onRestore: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = onRestore) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.pl_restore_field, stringResource(label)),
                )
            }
        },
    )
}

/**
 * 打开这条投稿。先试 B 站的自定义 scheme（没装 App 这一步就会失败），
 * 再退回 https 直链（装了由应用链接拉起 App，没装就进浏览器）；都没有时提示。
 *
 * [BiliUpScreen][top.aryun.yzliyin.ui.screen.BiliUpScreen] 的播放方式选择里也复用它，
 * 故放宽为 internal，避免在两处各抄一份兜底逻辑。
 */
internal fun openBilibiliVideo(context: Context, bvid: String) {
    val urls = listOf(
        "bilibili://www.bilibili.com/video/$bvid",
        "https://www.bilibili.com/video/$bvid",
    )
    for (url in urls) {
        val launched = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (launched) return
    }
    Toast.makeText(context, context.getString(R.string.pl_no_bili_app), Toast.LENGTH_SHORT).show()
}

/**
 * 在 B 站 App / 浏览器里打开某个直播间。
 * 与 [openBilibiliVideo] 同一套兜底：先试 `bilibili://live/<房间号>`，再退回网页链接。
 */
internal fun openBilibiliLive(context: Context, roomId: Long) {
    val urls = listOf(
        "bilibili://live/$roomId",
        "https://live.bilibili.com/$roomId",
    )
    for (url in urls) {
        val launched = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (launched) return
    }
    Toast.makeText(context, context.getString(R.string.pl_no_bili_app), Toast.LENGTH_SHORT).show()
}

/**
 * 在 B 站 App / 浏览器里打开某位 UP 主的主页。
 * 与 [openBilibiliVideo] 同一套兜底：先试自定义 scheme，再退回 https 空间页。
 */
private fun openBilibiliSpace(context: Context, mid: Long) {
    val urls = listOf(
        "bilibili://space/$mid",
        "https://space.bilibili.com/$mid",
    )
    for (url in urls) {
        val launched = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (launched) return
    }
    Toast.makeText(context, context.getString(R.string.pl_no_bili_app), Toast.LENGTH_SHORT).show()
}

/** 落库并刷新当前队列；歌名留空时回退原名，避免出现空白标题。 */
private fun commitSongInfoEdit(
    song: Song,
    saved: SongInfoEdit?,
    name: String,
    artist: String,
    cover: String,
    baseName: String,
    baseArtist: String,
    baseCover: String,
) {
    val finalName = name.trim().ifBlank { baseName }
    val finalArtist = artist.trim()
    val finalCover = cover.trim()
    val unchanged = finalName == baseName && finalArtist == baseArtist && finalCover == baseCover
    AppGraph.store.saveSongInfoEdit(
        song.key,
        if (unchanged) {
            null
        } else {
            SongInfoEdit(
                name = finalName,
                artist = finalArtist,
                cover = finalCover,
                baseName = baseName,
                baseArtist = baseArtist,
                baseCover = baseCover,
            )
        },
    )
    PlayerController.applySongInfoEdit(
        song.copy(name = finalName, artist = finalArtist, cover = finalCover),
    )
}

@Composable
private fun LyricStyleDialog(
    style: LyricStyle,
    onStyleChange: (LyricStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pl_lyric_style)) },
        text = {
            Column {
                StyleSlider(
                    label = stringResource(R.string.pl_font_size),
                    valueLabel = "${(style.fontSize * 100).toInt()}%",
                    value = style.fontSize,
                    range = SessionStore.LYRIC_FONT_MIN..SessionStore.LYRIC_FONT_MAX,
                ) { onStyleChange(style.copy(fontSize = it)) }

                StyleSlider(
                    label = stringResource(R.string.pl_wave_amount),
                    valueLabel = "${(style.wave * 100).toInt()}%",
                    value = style.wave,
                    range = 0f..1f,
                ) { onStyleChange(style.copy(wave = it)) }

                StyleSlider(
                    label = stringResource(R.string.pl_glow_amount),
                    valueLabel = "${(style.glow * 100).toInt()}%",
                    value = style.glow,
                    range = 0f..1f,
                ) { onStyleChange(style.copy(glow = it)) }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onStyleChange(style.copy(showTranslation = !style.showTranslation)) }
                        .padding(top = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Translate,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.pl_show_translation),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = style.showTranslation,
                        onCheckedChange = { onStyleChange(style.copy(showTranslation = it)) },
                    )
                }

                Spacer(Modifier.height(8.dp))
                LyricLineView(
                    line = LyricLine(0L, "Yesterday once more", "昨日重现"),
                    active = true,
                    style = style,
                    onClick = {},
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pl_done)) } },
        dismissButton = {
            TextButton(onClick = { onStyleChange(LyricStyle()) }) { Text(stringResource(R.string.pl_restore_default)) }
        },
    )
}

@Composable
internal fun StyleSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(72.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            valueLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

/** 歌词列表里的一行：原文 + 中文译文 + 字号缩放，高亮行可加波浪与光亮。 */
@Composable
internal fun LyricLineView(
    line: LyricLine,
    active: Boolean,
    style: LyricStyle,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    // 光亮强度只对当前行生效
    val glow = if (active) style.glow else 0f
    val baseColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val color = if (glow > 0f) lerp(baseColor, Color.White, 0.4f * glow) else baseColor
    val textStyle = if (glow > 0f) {
        TextStyle(
            shadow = Shadow(
                // Shadow 的 blurRadius 单位是像素，这里按 14dp 换算
                color = Color.White.copy(alpha = 0.25f + 0.5f * glow),
                blurRadius = with(density) { 14.dp.toPx() } * glow,
            ),
        )
    } else {
        TextStyle.Default
    }

    val size = (if (active) 19.sp else 15.sp) * style.fontSize
    val weight = if (active) FontWeight.Bold else FontWeight.Normal

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (active && style.wave > 0f) {
            WavyText(
                text = line.text,
                fontSize = size,
                fontWeight = weight,
                color = color,
                style = textStyle,
                wave = style.wave,
            )
        } else {
            Text(
                line.text,
                fontSize = size,
                fontWeight = weight,
                color = color,
                textAlign = TextAlign.Center,
                style = textStyle,
            )
        }

        // 译文开关关掉时整段不渲染（不是调透明度），免得留一截空白
        if (style.showTranslation) {
            line.translation?.let { translation ->
                Spacer(Modifier.height(2.dp))
                Text(
                    translation,
                    fontSize = size * 0.78f,
                    fontWeight = FontWeight.Normal,
                    color = color.copy(alpha = if (active) 0.9f else 0.72f),
                    textAlign = TextAlign.Center,
                    style = textStyle,
                )
            }
        }
    }
}

/** 每个字符走正弦波的相位步长（弧度）；7 个字一个完整波形。 */
private const val WAVE_STEP_RAD = (2.0 * Math.PI / 7.0).toFloat()

/**
 * 沿正弦波排布的一行字。
 *
 * 逐字 `graphicsLayer` 只改 `translationY`，相位由无限动画推进；动画值在
 * layer 块里读，只重绘图层不重组。波形随时间缓慢游走，看起来是在「起伏」。
 */
@Composable
private fun WavyText(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    color: Color,
    style: TextStyle,
    wave: Float,
    modifier: Modifier = Modifier,
) {
    if (text.isEmpty()) return
    val phase = rememberInfiniteTransition(label = "lyricWave").animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "lyricWavePhase",
    )
    val amplitude = 14.dp * wave
    val chars = remember(text) { text.toList() }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chars.forEachIndexed { index, ch ->
            Text(
                text = ch.toString(),
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = color,
                style = style,
                modifier = Modifier.graphicsLayer {
                    translationY = amplitude.toPx() * sin(index * WAVE_STEP_RAD - phase.value)
                },
            )
        }
    }
}
