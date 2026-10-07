package top.aryun.yzliyin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.ui.components.MiniPlayer
import top.aryun.yzliyin.ui.screen.AlbumScreen
import top.aryun.yzliyin.ui.screen.ArtistScreen
import top.aryun.yzliyin.ui.screen.CommentScreen
import top.aryun.yzliyin.ui.screen.HomeScreen
import top.aryun.yzliyin.ui.screen.LoginScreen
import top.aryun.yzliyin.ui.screen.MineScreen
import top.aryun.yzliyin.ui.screen.PlaylistDetailScreen
import top.aryun.yzliyin.ui.screen.PlayerScreen
import top.aryun.yzliyin.ui.screen.RankDetailScreen
import top.aryun.yzliyin.ui.screen.RankScreen
import top.aryun.yzliyin.ui.screen.SearchScreen
import top.aryun.yzliyin.ui.screen.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val RANK = "rank"
    const val MINE = "mine"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val DAILY = "daily"

    /** 登录页：login/{method}，method 见下方常量。 */
    const val LOGIN = "login/{method}"
    fun login(method: String) = "login/$method"

    const val LOGIN_NETEASE_WEB = "netease_web"
    const val LOGIN_NETEASE_CAPTCHA = "netease_captcha"
    const val LOGIN_NETEASE_QR = "netease_qr"
    const val LOGIN_KUGOU_QR = "kugou_qr"
    const val LOGIN_BILIBILI_QR = "bilibili_qr"

    /** 歌单/收藏夹详情带源标识，各源的同一数字 id 不会互相串。 */
    const val PLAYLIST = "playlist/{source}/{id}"
    fun playlist(source: Source, id: String) = "playlist/${source.id}/$id"

    const val ARTIST = "artist/{id}"
    fun artist(id: String) = "artist/$id"

    const val ALBUM = "album/{id}"
    fun album(id: String) = "album/$id"

    const val RANK_DETAIL = "rankDetail/{id}"
    fun rankDetail(id: String) = "rankDetail/$id"

    const val COMMENTS = "comments/{mixsongid}/{name}"
    fun comments(mixsongid: String, name: String) = "comments/$mixsongid/${name.encodeURLPath()}"
}

private val topLevel = setOf(Routes.HOME, Routes.SEARCH, Routes.RANK, Routes.MINE)

/** 顶层 tab（标准 Material 3 NavigationBar）。 */
private data class TopTab(val route: String, val label: String, val icon: ImageVector)

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

    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBar = route in topLevel

    // 根容器显式铺底色：各页面自身不画背景，否则浅色模式下会透出窗口底色（历史上恒为深色）
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.SEARCH) { SearchScreen(nav) }
            composable(Routes.RANK) { RankScreen(nav) }
            composable(Routes.MINE) { MineScreen(nav) }

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
            composable(Routes.RANK_DETAIL) { entry ->
                RankDetailScreen(nav, entry.arguments?.getString("id").orEmpty())
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
                route = Routes.LOGIN,
                arguments = listOf(navArgument("method") { defaultValue = Routes.LOGIN_NETEASE_WEB }),
            ) { entry ->
                LoginScreen(
                    nav,
                    method = entry.arguments?.getString("method") ?: Routes.LOGIN_NETEASE_WEB,
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
        }

        // 停靠式迷你播放条 + 标准底栏
        val showMini = route != null &&
            route != Routes.PLAYER &&
            route != Routes.LOGIN &&
            route != Routes.SETTINGS &&
            !route.startsWith("comments")
        if (showBar || showMini) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showMini) {
                    MiniPlayer(
                        nav = nav,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (showBar) Modifier else Modifier.navigationBarsPadding()),
                    )
                }
                if (showBar) {
                    val tabs = listOf(
                        TopTab(Routes.HOME, "首页", Icons.Rounded.Home),
                        TopTab(Routes.SEARCH, "乐库", Icons.Rounded.Search),
                        TopTab(Routes.RANK, "排行", Icons.AutoMirrored.Rounded.TrendingUp),
                        TopTab(Routes.MINE, "我的", Icons.Rounded.Person),
                    )
                    NavigationBar(Modifier.fillMaxWidth()) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        }
    }
}
