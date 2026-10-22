package top.aryun.yzliyin.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.NetworkStatus
import top.aryun.yzliyin.listentogether.ListenTogetherInviteBus
import top.aryun.yzliyin.player.PlaybackGate
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.components.MiniPlayer
import top.aryun.yzliyin.ui.components.formatDuration
import top.aryun.yzliyin.ui.components.minutesLabel
import top.aryun.yzliyin.ui.screen.AlbumScreen
import top.aryun.yzliyin.ui.screen.ArtistScreen
import top.aryun.yzliyin.ui.screen.BiliCommentScreen
import top.aryun.yzliyin.ui.screen.BiliUpScreen
import top.aryun.yzliyin.ui.screen.BiliVideoScreen
import top.aryun.yzliyin.ui.screen.CommentScreen
import top.aryun.yzliyin.ui.screen.HomeScreen
import top.aryun.yzliyin.ui.screen.ListenTogetherDebugScreen
import top.aryun.yzliyin.ui.screen.ListenTogetherScreen
import top.aryun.yzliyin.ui.screen.LiveRoomScreen
import top.aryun.yzliyin.ui.screen.LoginScreen
import top.aryun.yzliyin.ui.screen.PlaylistDetailScreen
import top.aryun.yzliyin.ui.screen.LibraryScreen
import top.aryun.yzliyin.ui.screen.PlayerScreen
import top.aryun.yzliyin.ui.screen.SearchScreen
import top.aryun.yzliyin.ui.screen.SettingsScreen
import top.aryun.yzliyin.ui.screen.joinListenTogetherFromInvite
import top.aryun.yzliyin.ui.theme.AppTheme
import kotlin.math.roundToInt

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val DAILY = "daily"

    /** 一起听房间页与调试面板。 */
    const val LISTEN_TOGETHER = "listenTogether"
    const val LISTEN_TOGETHER_DEBUG = "listenTogetherDebug"

    /** 登录页：login/{method}，method 见下方常量。 */
    const val LOGIN = "login/{method}"
    fun login(method: String) = "login/$method"

    /** B 站投稿的页内播放：biliVideo/{bvid}/{cid}/{name}，name 走 URL 编码。 */
    const val BILI_VIDEO = "biliVideo/{bvid}/{cid}/{name}"
    fun biliVideo(bvid: String, cid: String, name: String) =
        "biliVideo/$bvid/$cid/${Uri.encode(name)}"

    /** B 站 UP 主主页（应用内）：biliUp/{mid}。 */
    const val BILI_UP = "biliUp/{mid}"
    fun biliUp(mid: String) = "biliUp/$mid"

    /** B 站直播间（应用内）：liveRoom/{roomId}。 */
    const val LIVE_ROOM = "liveRoom/{roomId}"
    fun liveRoom(roomId: Long) = "liveRoom/$roomId"

    /** B 站评论区：biliComments/{bvid}/{name}，name 走 URL 编码。 */
    const val BILI_COMMENTS = "biliComments/{bvid}/{name}"
    fun biliComments(bvid: String, name: String) =
        "biliComments/$bvid/${Uri.encode(name)}"

    const val LOGIN_NETEASE_WEB = "netease_web"
    const val LOGIN_NETEASE_CAPTCHA = "netease_captcha"
    const val LOGIN_NETEASE_QR = "netease_qr"
    const val LOGIN_KUGOU_QR = "kugou_qr"
    const val LOGIN_BILIBILI_QR = "bilibili_qr"
    const val LOGIN_YOUTUBE_WEB = "youtube_web"

    /** 歌单/收藏夹详情带源标识，各源的同一数字 id 不会互相串。 */
    const val PLAYLIST = "playlist/{source}/{id}"
    fun playlist(source: Source, id: String) = "playlist/${source.id}/$id"

    const val ARTIST = "artist/{id}"
    fun artist(id: String) = "artist/$id"

    const val ALBUM = "album/{id}"
    fun album(id: String) = "album/$id"

    const val COMMENTS = "comments/{mixsongid}/{name}"
    fun comments(mixsongid: String, name: String) = "comments/$mixsongid/${name.encodeURLPath()}"
}

private val topLevel = setOf(Routes.HOME, Routes.SEARCH, Routes.LIBRARY, Routes.SETTINGS)

/**
 * 顶层 tab（标准 Material 3 NavigationBar）。
 *
 * label 存资源 id 而不是字符串：这个列表是顶层 val、不在组合期，
 * 取文案要到使用处再 stringResource，才能跟着设置里选的语言走。
 */
private data class TopTab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
)

/** 顶层 tab：贴底 NavigationBar 与宽屏侧边 NavigationRail 共用同一批。 */
private val topLevelTabs = listOf(
    TopTab(Routes.HOME, R.string.app_tab_home, Icons.Rounded.Home),
    TopTab(Routes.SEARCH, R.string.app_tab_search, Icons.Rounded.Search),
    TopTab(Routes.LIBRARY, R.string.app_tab_library, Icons.Rounded.LibraryMusic),
    TopTab(Routes.SETTINGS, R.string.app_tab_settings, Icons.Rounded.Settings),
)

/** 宽屏阈值（Material medium 窗口下限）：达到即换侧边导航并给内容限宽。 */
private const val WIDE_SCREEN_MIN_DP = 600

/** 侧边导航栏宽度（dp）。 */
private const val RAIL_WIDTH_DP = 80

/** 平板 / 横屏下内容列的最大宽度（dp），超出部分两侧留白居中。 */
private const val MAX_CONTENT_WIDTH_DP = 640

private fun String.encodeURLPath(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

/**
 * 应用根：
 *  - 顶层 tab 用标准 Material 3 NavigationBar（贴底、纯色，非玻璃非悬浮）；
 *  - 迷你播放条停靠在底栏正上方。
 */
@Composable
fun App() {
    val nav: NavHostController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 冷启动恢复上次播放的队列与进度（暂停态，点播放才继续）
    LaunchedEffect(Unit) { PlayerController.restoreLastSession(context) }

    // 深链邀请：MainActivity 解析后在此弹确认框
    val pendingInvite by ListenTogetherInviteBus.pending.collectAsStateWithLifecycle()
    var inviteJoining by remember { mutableStateOf(false) }
    pendingInvite?.let { invite ->
        AlertDialog(
            onDismissRequest = { ListenTogetherInviteBus.consume() },
            title = { Text(stringResource(R.string.app_invite_title)) },
            text = {
                val inviter = invite.inviterNickname?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.app_invite_friend)
                Text(stringResource(R.string.app_invite_text, inviter, invite.roomId))
            },
            confirmButton = {
                TextButton(
                    enabled = !inviteJoining,
                    onClick = {
                        inviteJoining = true
                        scope.launch {
                            val message = runCatching {
                                joinListenTogetherFromInvite(
                                    manager = AppGraph.listenTogether,
                                    prefs = AppGraph.listenTogetherPrefs,
                                    invite = invite,
                                )
                            }.getOrElse { it.message ?: context.getString(R.string.app_invite_join_fail) }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            inviteJoining = false
                            ListenTogetherInviteBus.consume()
                            nav.navigate(Routes.LISTEN_TOGETHER)
                        }
                    },
                ) {
                    Text(
                        if (inviteJoining) stringResource(R.string.app_invite_joining)
                        else stringResource(R.string.app_invite_join),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { ListenTogetherInviteBus.consume() }) {
                    Text(stringResource(R.string.app_invite_ignore))
                }
            },
        )
    }

    // 长时长播放确认：第一问「确定播放吗」，第二问一道数学题，两问都过了才开播
    val durWarn by PlaybackGate.pending.collectAsStateWithLifecycle()
    var durMath by remember { mutableStateOf(false) }
    var durAnswer by remember { mutableStateOf("") }
    var durWrong by remember { mutableStateOf(false) }
    // 换一个请求（或请求被取消）就回到第一问，别把上一题的答案带到下一首
    LaunchedEffect(durWarn) {
        durMath = false
        durAnswer = ""
        durWrong = false
    }
    durWarn?.let { request ->
        if (!durMath) {
            AlertDialog(
                onDismissRequest = { PlaybackGate.cancel() },
                title = { Text(stringResource(R.string.app_durwarn_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.app_durwarn_text,
                            request.song.name,
                            formatDuration(request.durationMs),
                            minutesLabel((request.limitMs / 60_000L).toInt()),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { durMath = true }) {
                        Text(stringResource(R.string.app_durwarn_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { PlaybackGate.cancel() }) {
                        Text(stringResource(R.string.app_durwarn_cancel))
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { PlaybackGate.cancel() },
                title = { Text(stringResource(R.string.app_durmath_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(
                                R.string.app_durmath_prompt,
                                "${request.left} ${request.op} ${request.right}",
                            ),
                        )
                        OutlinedTextField(
                            value = durAnswer,
                            onValueChange = {
                                durAnswer = it.filter(Char::isDigit).take(6)
                                durWrong = false
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = durWrong,
                            supportingText = if (durWrong) {
                                { Text(stringResource(R.string.app_durmath_wrong)) }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (durAnswer.trim().toIntOrNull() == request.answer) {
                                PlaybackGate.proceed()
                            } else {
                                durWrong = true
                            }
                        },
                    ) { Text(stringResource(R.string.app_durmath_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { PlaybackGate.cancel() }) {
                        Text(stringResource(R.string.app_durmath_cancel))
                    }
                },
            )
        }
    }

    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBar = route in topLevel

    // 平板 / 横屏：宽度达到 600dp 即视为宽屏 —— 导航挪到左侧，内容限宽居中，避免大屏被拉伸
    // 宽度取自真实窗口测量（rememberWindowWidthDp）而非 Configuration：语言重包可能把
    // 尺寸配置冻在启动那一刻，平板旋转后就再也读不到新宽度，宽屏样式会整个丢掉。
    // 界面放大后一块屏幕能装下的 dp 更少，因此按缩放后的逻辑宽度判断，宽窄形态才符合实际观感
    val screenWidthDp = (rememberWindowWidthDp() / AppTheme.uiScale).roundToInt()
    val isWide = screenWidthDp >= WIDE_SCREEN_MIN_DP
    val railVisible = isWide && showBar
    // 宽屏内容列的可用宽度（扣掉侧边导航栏），据此算左右留白；
    // 手机上 maxWidth < 640 → 留白恒为 0，布局与原先完全一致。
    // 全屏页（B 站投稿播放 / 直播间）不吃这套限宽，否则平板上画面两侧会露出底色。
    val fullScreen = route == Routes.BILI_VIDEO || route == Routes.LIVE_ROOM
    val contentWidthDp = screenWidthDp - if (railVisible) RAIL_WIDTH_DP else 0
    val sidePadDp = if (fullScreen) {
        0
    } else {
        ((contentWidthDp - MAX_CONTENT_WIDTH_DP) / 2).coerceAtLeast(0)
    }
    // 侧栏是盖在 NavHost 上面的，起点要额外让开它，内容才在可视区里真正居中
    val startPadDp = sidePadDp + if (railVisible) RAIL_WIDTH_DP else 0

    // 播放页在宽屏上要排成「左封面 + 进度 / 右歌词」两栏，B 站投稿的页内播放页与
    // 直播间要铺满画面；640dp 的内容限宽会把它们挤得过窄、四周留黑边，因此这几屏
    // 不吃限宽，用满整屏宽度（其它页面保持两侧留白居中）
    val playerFullBleed = isWide &&
        (route == Routes.PLAYER || route == Routes.BILI_VIDEO || route == Routes.LIVE_ROOM)
    val navStartPadDp = if (playerFullBleed) 0f else startPadDp.toFloat()
    val navEndPadDp = if (playerFullBleed) 0f else sidePadDp.toFloat()

    // 根容器显式铺底色：各页面自身不画背景，否则浅色模式下会透出窗口底色（历史上恒为深色）
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = navStartPadDp.dp, end = navEndPadDp.dp),
        ) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.SEARCH) { SearchScreen(nav) }
            composable(Routes.LIBRARY) { LibraryScreen(nav) }

            composable(Routes.DAILY) { SearchScreen(nav, initialTab = 0) }
            composable(Routes.PLAYLIST) { entry ->
                PlaylistDetailScreen(
                    nav,
                    id = entry.arguments?.getString("id").orEmpty(),
                    source = Source.fromId(entry.arguments?.getString("source").orEmpty()),
                )
            }
            composable(Routes.ARTIST) { entry ->
                ArtistScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable(Routes.ALBUM) { entry ->
                AlbumScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            composable(Routes.COMMENTS) { entry ->
                CommentScreen(
                    nav,
                    mixsongid = entry.arguments?.getString("mixsongid").orEmpty(),
                    name = entry.arguments?.getString("name").orEmpty(),
                )
            }
            composable(Routes.PLAYER) { PlayerScreen(nav) }
            composable(
                route = Routes.LIVE_ROOM,
                arguments = listOf(navArgument("roomId") { type = NavType.LongType }),
            ) { entry ->
                LiveRoomScreen(
                    roomId = entry.arguments?.getLong("roomId") ?: 0L,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = Routes.BILI_VIDEO,
                arguments = listOf(
                    navArgument("bvid") { type = NavType.StringType },
                    navArgument("cid") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("name") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                BiliVideoScreen(
                    bvid = entry.arguments?.getString("bvid").orEmpty(),
                    cid = entry.arguments?.getString("cid").orEmpty(),
                    name = entry.arguments?.getString("name").orEmpty(),
                    nav = nav,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = Routes.BILI_COMMENTS,
                arguments = listOf(
                    navArgument("bvid") { type = NavType.StringType },
                    navArgument("name") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                BiliCommentScreen(
                    nav = nav,
                    bvid = entry.arguments?.getString("bvid").orEmpty(),
                    name = entry.arguments?.getString("name").orEmpty(),
                )
            }
            composable(
                route = Routes.BILI_UP,
                arguments = listOf(navArgument("mid") { type = NavType.StringType }),
            ) { entry ->
                BiliUpScreen(
                    nav = nav,
                    mid = entry.arguments?.getString("mid").orEmpty(),
                )
            }
            composable(
                route = Routes.LOGIN,
                arguments = listOf(navArgument("method") { defaultValue = Routes.LOGIN_NETEASE_WEB }),
            ) { entry ->
                LoginScreen(
                    nav,
                    method = entry.arguments?.getString("method") ?: Routes.LOGIN_NETEASE_WEB,
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.LISTEN_TOGETHER) { ListenTogetherScreen(nav) }
            composable(Routes.LISTEN_TOGETHER_DEBUG) {
                ListenTogetherDebugScreen(onBack = { nav.popBackStack() })
            }
        }

        // 宽屏：侧边导航栏盖在左侧（NavHost 已按 railVisible 让出左边距）
        if (railVisible) {
            NavigationRail(Modifier.align(Alignment.CenterStart).fillMaxHeight()) {
                Spacer(Modifier.height(8.dp))
                topLevelTabs.forEach { tab ->
                    NavigationRailItem(
                        selected = route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.label)) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        }

        // 停靠式迷你播放条 + 标准底栏
        val showMini = route != null &&
            route != Routes.PLAYER &&
            route != Routes.LOGIN &&
            route != Routes.BILI_VIDEO &&
            route != Routes.LIVE_ROOM &&
            route != Routes.LISTEN_TOGETHER &&
            route != Routes.LISTEN_TOGETHER_DEBUG &&
            !route.startsWith("comments") &&
            !route.startsWith("biliComments")
        if (showBar || showMini) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 宽屏时让开侧边导航栏，迷你播放条不被导航栏压住
                    .padding(start = if (railVisible) RAIL_WIDTH_DP.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showMini) {
                    // 无网络时在播放卡片正上方提示
                    if (NetworkStatus.offline) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.app_offline),
                                Modifier.padding(vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    MiniPlayer(
                        nav = nav,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (showBar) Modifier else Modifier.navigationBarsPadding()),
                    )
                }
                // 宽屏由左侧导航栏负责顶层切换，底栏只在手机形态下出现
                if (showBar && !isWide) {
                    NavigationBar(Modifier.fillMaxWidth()) {
                        topLevelTabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = stringResource(tab.label)) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            }
        }
    }
}
