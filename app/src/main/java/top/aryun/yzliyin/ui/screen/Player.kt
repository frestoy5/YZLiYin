package top.aryun.yzliyin.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.data.LyricEdits
import top.aryun.yzliyin.core.data.SessionStore
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
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.ui.components.BiliQuadButton
import top.aryun.yzliyin.ui.components.formatDuration
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

    fun noArtistToast() {
        Toast.makeText(context, "这首歌没有歌手信息", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "这首歌没有歌手信息", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "没找到歌手「${names.first()}」", Toast.LENGTH_SHORT).show()

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
            if (parsed.isEmpty()) lyricTip = "暂无歌词" else {
                lyrics = parsed
                lyricTip = null
            }
            return@LaunchedEffect
        }
        lyricTip = "歌词加载中…"
        runCatching { CrossSourceMatch.lyrics(s) }
            .onSuccess {
                if (it.isEmpty()) lyricTip = "暂无歌词" else {
                    lyrics = it
                    lyricTip = null
                }
            }
            .onFailure { lyricTip = "歌词加载失败" }
    }

    val listState = rememberLazyListState()
    val activeIndex = activeLyricIndex(lyrics, state.positionMs)

    // 平板 / 横屏（宽度 ≥ 600dp）：主区分成左右两栏，右边歌词常驻，不用「查看歌词」开关
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_MIN_WIDTH_DP
    val lyricPaneVisible = showLyrics || wide

    LaunchedEffect(activeIndex, lyricPaneVisible) {
        val autoScroll = AppGraph.store.lyricAutoScroll
        if (activeIndex >= 0 && lyricPaneVisible && autoScroll) {
            runCatching { listState.animateScrollToItem(activeIndex, -220) }
        }
    }

    if (song == null) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("当前没有播放的歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { nav.popBackStack() }) { Text("返回") }
            }
            IconButton(
                onClick = { nav.popBackStack() },
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(4.dp)
                    .align(Alignment.TopStart),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }
        return
    }

    val progress =
        if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        else 0f

    // B 站歌曲点 UP 主名：在应用内看投稿，还是跳去 B 站 App 看主页
    biliUpMid?.let { mid ->
        val upName = song.artist.ifBlank { "这位 UP 主" }
        AlertDialog(
            onDismissRequest = { biliUpMid = null },
            title = { Text("查看 $upName 的主页") },
            text = { Text("选择在哪个应用里观看？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        biliUpMid = null
                        nav.navigate(Routes.biliUp(mid.toString()))
                    },
                ) { Text("在应用内观看") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        biliUpMid = null
                        openBilibiliSpace(context, mid)
                    },
                ) { Text("在 B 站内观看") }
            },
        )
    }

    // 合作曲 / 按名字搜出来的歌手：列出全部，点谁进谁的主页
    artistPicker?.let { list ->
        AlertDialog(
            onDismissRequest = { artistPicker = null },
            title = { Text("选择歌手") },
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
            dismissButton = { TextButton(onClick = { artistPicker = null }) { Text("取消") } },
        )
    }

    // 歌曲没带歌手 id 时按名字搜，转圈期间给个反馈，免得像是点不动
    if (artistSearching) {
        AlertDialog(
            onDismissRequest = { artistSearching = false },
            title = { Text("选择歌手") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在查找歌手…")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { artistSearching = false }) { Text("取消") } },
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "收起")
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
                            contentDescription = "评论",
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
                        text = "一起听 · ${if (ltSession.role == "controller") "房主" else "听众"}" +
                            " · 房间 ${ltSession.roomId}",
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp),
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
                            modifier = Modifier.fillMaxSize(),
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
        AsyncImage(
            model = song.cover,
            contentDescription = song.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(side)
                .clip(RoundedCornerShape(24.dp)),
        )
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
) {
    if (lyrics.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                tip ?: "暂无歌词",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 120.dp),
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
            title = { Text("打开投稿") },
            text = { Text("跳到 B 站 App 观看，还是直接在本应用里播这条投稿？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOpenPicker = false
                        openBilibiliVideo(context, biliBvid)
                    },
                ) { Text("B 站 App") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOpenPicker = false
                        onOpenBiliInApp()
                    },
                ) { Text("在应用内播放") }
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
                            if (lyricsShown) "返回封面" else "查看歌词",
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
                            "查看投稿",
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
                    contentDescription = "随机播放",
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
                    contentDescription = "上一首",
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
                        contentDescription = if (state.playing) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            IconButton(onClick = { PlayerController.next() }) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "下一首",
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = { PlayerController.cycleRepeatMode() }) {
                val repeatOne = state.repeatMode == Player.REPEAT_MODE_ONE
                Icon(
                    if (repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "单曲循环（点击切换）"
                        Player.REPEAT_MODE_ALL -> "列表循环（点击切换）"
                        else -> "顺序播放（点击切换）"
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

private data class QualityOption(val id: String, val label: String)

private val neteaseQualities = listOf(
    QualityOption("standard", "标准"),
    QualityOption("higher", "较高"),
    QualityOption("exhigh", "极高"),
    QualityOption("lossless", "无损"),
    QualityOption("hires", "Hi·Res"),
)

private val kugouQualities = listOf(
    QualityOption("auto", "自动"),
    QualityOption("128", "128K"),
    QualityOption("320", "320K"),
    QualityOption("flac", "无损"),
    QualityOption("high", "高品"),
    QualityOption("super", "Hi-Res"),
)

private val biliQualities = listOf(
    QualityOption("auto", "自动"),
    QualityOption("64", "64K"),
    QualityOption("132", "132K"),
    QualityOption("192", "192K"),
)

/** 播放倍速档位。 */
private val playbackSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 播放页右上角的「更多」菜单：一起听 / 编辑歌曲信息 / 音质 / 倍速 / 歌词样式。
 * （原先的评论入口按需求已移除，评论仍可从歌单、榜单等列表页进入。）
 */
@Composable
private fun PlayerMoreMenu(
    nav: NavController,
    song: Song,
    seedLyrics: List<LyricLine>,
    onLyricsChanged: () -> Unit,
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

    // 打开子菜单时收起主菜单，避免两层浮层叠在一起
    fun openQuality() { expanded = false; showQuality = true }
    fun openSpeed() { expanded = false; showSpeed = true }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("一起听") },
                leadingIcon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
                onClick = {
                    expanded = false
                    nav.navigate(Routes.LISTEN_TOGETHER)
                },
            )
            DropdownMenuItem(
                text = { Text("编辑歌曲信息") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    showEditInfo = true
                },
            )
            DropdownMenuItem(
                text = { Text("音质") },
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
                text = { Text("倍速") },
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
                text = { Text("歌词样式") },
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
                    text = { Text(option.label) },
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
    }
}

private fun qualityOptionsFor(source: Source): List<QualityOption> = when (source) {
    Source.KUGOU -> kugouQualities
    Source.BILIBILI -> biliQualities
    Source.NETEASE -> neteaseQualities
}

private fun currentQualityId(source: Source): String = when (source) {
    Source.KUGOU -> AppGraph.store.kugouQuality
    Source.BILIBILI -> AppGraph.store.biliQuality
    Source.NETEASE -> AppGraph.store.playQuality
}

private fun currentQualityLabel(source: Source): String {
    val id = currentQualityId(source)
    return qualityOptionsFor(source).firstOrNull { it.id == id }?.label ?: "默认"
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
        fromCache -> source.label to "本地缓存"
        source != songSource -> source.label to "已切换"
        else -> source.label to null
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
                    buildString {
                        append("来自")
                        append(label)
                        note?.let {
                            append(" · ")
                            append(it)
                        }
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
 * 播放页歌词的三个外观档位，改完立即生效并落盘。
 *
 * 波浪与光亮只作用于**当前高亮的那一行**，其它行保持正常排版，
 * 免得整屏文字都在动，读不了。
 */
internal data class LyricStyle(
    val fontSize: Float = 1f,
    val wave: Float = 0f,
    val glow: Float = 0f,
) {
    fun save(store: SessionStore) {
        store.lyricFontSize = fontSize
        store.lyricWave = wave
        store.lyricGlow = glow
    }

    companion object {
        fun load(store: SessionStore) = LyricStyle(
            fontSize = store.lyricFontSize,
            wave = store.lyricWave,
            glow = store.lyricGlow,
        )
    }
}

/** 菜单项右侧的摘要，例如「115% · 波浪 · 光亮」。 */
private fun styleSummary(s: LyricStyle): String = buildList {
    add("${(s.fontSize * 100).toInt()}%")
    if (s.wave > 0f) add("波浪")
    if (s.glow > 0f) add("光亮")
}.joinToString(" · ")

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
        title = { Text("编辑歌曲信息") },
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
                        "改完立即生效：播放页、迷你条、通知栏与曲库都会按新信息显示，不影响播放进度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                EditInfoField("歌名", name, { name = it }, { name = baseName })
                EditInfoField("歌手", artist, { artist = it }, { artist = baseArtist })
                EditInfoField("封面链接", cover, { cover = it }, { cover = baseCover })

                OutlinedButton(
                    onClick = { showLyricsEditor = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("编辑歌词")
                    Spacer(Modifier.weight(1f, fill = true))
                    Text(
                        text = when {
                            lyricsEdited -> "已编辑"
                            seedLyrics.isEmpty() -> "暂无"
                            else -> "共 ${seedLyrics.size} 行"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Text("恢复原信息")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    commitSongInfoEdit(song, saved, name, artist, cover, baseName, baseArtist, baseCover)
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
        title = { Text("编辑歌词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("LRC 歌词") },
                    placeholder = { Text("[00:12.34]歌词一行一句\n同一时间戳再写一行即为译文") },
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
                    Text("恢复原歌词")
                }
                Text(
                    "格式 [分:秒.百分秒]歌词，一行一句；同一时间戳再写一行会被当成译文。",
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
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 编辑面板里的单行输入框，右侧图标一键还原该项的原值。 */
@Composable
private fun EditInfoField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onRestore: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = onRestore) {
                Icon(Icons.Rounded.Refresh, contentDescription = "恢复${label}原值")
            }
        },
    )
}

/**
 * 打开这条投稿。先试 B 站的自定义 scheme（没装 App 这一步就会失败），
 * 再退回 https 直链（装了由应用链接拉起 App，没装就进浏览器）；都没有时提示。
 */
private fun openBilibiliVideo(context: Context, bvid: String) {
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
    Toast.makeText(context, "未检测到能打开 B 站页面的应用", Toast.LENGTH_SHORT).show()
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
    Toast.makeText(context, "未检测到能打开 B 站页面的应用", Toast.LENGTH_SHORT).show()
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
        title = { Text("歌词样式") },
        text = {
            Column {
                StyleSlider(
                    label = "字体大小",
                    valueLabel = "${(style.fontSize * 100).toInt()}%",
                    value = style.fontSize,
                    range = SessionStore.LYRIC_FONT_MIN..SessionStore.LYRIC_FONT_MAX,
                ) { onStyleChange(style.copy(fontSize = it)) }

                StyleSlider(
                    label = "弯曲程度",
                    valueLabel = "${(style.wave * 100).toInt()}%",
                    value = style.wave,
                    range = 0f..1f,
                ) { onStyleChange(style.copy(wave = it)) }

                StyleSlider(
                    label = "光亮程度",
                    valueLabel = "${(style.glow * 100).toInt()}%",
                    value = style.glow,
                    range = 0f..1f,
                ) { onStyleChange(style.copy(glow = it)) }

                Spacer(Modifier.height(8.dp))
                LyricLineView(
                    line = LyricLine(0L, "Yesterday once more", "昨日重现"),
                    active = true,
                    style = style,
                    onClick = {},
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = { onStyleChange(LyricStyle()) }) { Text("恢复默认") }
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
            modifier = Modifier.width(44.dp),
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
