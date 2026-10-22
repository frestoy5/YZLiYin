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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.SectionHeader
import top.aryun.yzliyin.ui.components.labelText

/**
 * 账号面板：三个数据源各一张账号卡片（登录态、昵称头像、登录/切换/退出）。
 * 「我的」页已并入「设置 → 账号」，这里只提供卡片内容，不再自带页面框架。
 */
@Composable
internal fun AccountsPane(nav: NavController) {
    val store = AppGraph.store
    val scope = rememberCoroutineScope()

    var loggedIn by remember { mutableStateOf(store.isNeteaseLoggedIn) }
    var kugouLoggedIn by remember { mutableStateOf(store.isLoggedIn(Source.KUGOU)) }
    var biliLoggedIn by remember { mutableStateOf(store.isLoggedIn(Source.BILIBILI)) }

    var kugouName by remember { mutableStateOf(store.displayName(Source.KUGOU)) }
    var kugouAvatar by remember { mutableStateOf(store.avatar(Source.KUGOU)) }
    var biliName by remember { mutableStateOf(store.displayName(Source.BILIBILI)) }
    var biliAvatar by remember { mutableStateOf(store.avatar(Source.BILIBILI)) }

    var refreshKey by remember { mutableStateOf(0) }

    /** 只刷新登录态与各源昵称/头像；歌单数据归「曲库」管，这里不再拉。 */
    suspend fun reload() {
        loggedIn = store.isNeteaseLoggedIn
        kugouLoggedIn = store.isLoggedIn(Source.KUGOU)
        biliLoggedIn = store.isLoggedIn(Source.BILIBILI)

        if (store.isNeteaseLoggedIn) runCatching { NeteaseApi.refreshAccount() }
        if (kugouLoggedIn) {
            // 昵称与头像需要单独拉，扫码响应里没有；拉不到就沿用已存的值
            runCatching { KugouApi.refreshProfile() }
            kugouName = store.displayName(Source.KUGOU)
            kugouAvatar = store.avatar(Source.KUGOU)
        }
        if (biliLoggedIn) {
            runCatching { BiliApi.refreshProfile() }
            biliName = store.displayName(Source.BILIBILI)
            biliAvatar = store.avatar(Source.BILIBILI)
        }
    }

    // 每次回到前台刷新一次登录态与资料
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { runCatching { reload() } }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshKey) { runCatching { reload() } }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ===== 网易云音乐 =====
        AccountCard(
            title = stringResource(R.string.min_netease_title),
            avatar = store.neteaseAvatar.takeIf { loggedIn && it.isNotEmpty() },
            name = store.neteaseName.takeIf { loggedIn && it.isNotEmpty() },
            loggedIn = loggedIn,
            statusText = {
                if (loggedIn) {
                    stringResource(
                        if (store.neteaseVip) R.string.min_netease_status_vip else R.string.min_netease_status,
                        store.neteaseName.ifBlank { stringResource(R.string.min_logged_in) },
                    )
                } else {
                    stringResource(R.string.min_netease_offline)
                }
            },
            actions = {
                if (loggedIn) {
                    OutlinedButton(onClick = {
                        NeteaseApi.logout()
                        loggedIn = false
                        refreshKey++
                        nav.navigate(Routes.login(Routes.LOGIN_NETEASE_WEB)) { launchSingleTop = true }
                    }) { Text(stringResource(R.string.min_switch_account)) }
                    TextButton(onClick = {
                        NeteaseApi.logout()
                        loggedIn = false
                        refreshKey++
                    }) { Text(stringResource(R.string.min_sign_out), color = MaterialTheme.colorScheme.error) }
                } else {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                nav.navigate(Routes.login(Routes.LOGIN_NETEASE_WEB)) { launchSingleTop = true }
                            }) { Text(stringResource(R.string.min_web_login)) }
                            OutlinedButton(onClick = {
                                nav.navigate(Routes.login(Routes.LOGIN_NETEASE_CAPTCHA)) { launchSingleTop = true }
                            }) { Text(stringResource(R.string.min_captcha)) }
                        }
                        TextButton(onClick = {
                            nav.navigate(Routes.login(Routes.LOGIN_NETEASE_QR)) { launchSingleTop = true }
                        }) { Text(stringResource(R.string.min_qr_login)) }
                    }
                }
            },
        )

        // ===== 酷狗音乐 =====
        AccountCard(
            title = stringResource(R.string.min_kugou_title),
            avatar = kugouAvatar.takeIf { kugouLoggedIn && it.isNotEmpty() },
            name = kugouName.takeIf { kugouLoggedIn && it.isNotEmpty() },
            loggedIn = kugouLoggedIn,
            statusText = {
                if (kugouLoggedIn) {
                    stringResource(
                        R.string.min_kugou_status,
                        kugouName.ifBlank { stringResource(R.string.min_logged_in) },
                    )
                } else {
                    stringResource(R.string.min_kugou_offline)
                }
            },
            actions = {
                if (kugouLoggedIn) {
                    TextButton(onClick = {
                        KugouApi.logout()
                        kugouLoggedIn = false
                        kugouName = ""
                        kugouAvatar = ""
                    }) { Text(stringResource(R.string.min_sign_out), color = MaterialTheme.colorScheme.error) }
                } else {
                    FilledTonalButton(onClick = {
                        nav.navigate(Routes.login(Routes.LOGIN_KUGOU_QR)) { launchSingleTop = true }
                    }) { Text(stringResource(R.string.min_qr_login)) }
                }
            },
        )

        // ===== 哔哩哔哩 =====
        AccountCard(
            title = stringResource(R.string.min_bili_title),
            avatar = biliAvatar.takeIf { biliLoggedIn && it.isNotEmpty() },
            name = biliName.takeIf { biliLoggedIn && it.isNotEmpty() },
            loggedIn = biliLoggedIn,
            statusText = {
                if (biliLoggedIn) {
                    stringResource(
                        R.string.min_bili_status,
                        biliName.ifBlank { stringResource(R.string.min_logged_in) },
                    )
                } else {
                    stringResource(R.string.min_bili_offline)
                }
            },
            actions = {
                if (biliLoggedIn) {
                    TextButton(onClick = {
                        BiliApi.logout()
                        biliLoggedIn = false
                        biliName = ""
                        biliAvatar = ""
                    }) { Text(stringResource(R.string.min_sign_out), color = MaterialTheme.colorScheme.error) }
                } else {
                    FilledTonalButton(onClick = {
                        nav.navigate(Routes.login(Routes.LOGIN_BILIBILI_QR)) { launchSingleTop = true }
                    }) { Text(stringResource(R.string.min_qr_login)) }
                }
            },
        )
    }
}

/** 「我喜欢」入口；该源没有对应列表时给出说明而不是留空。 */
internal fun LazyListScope.likesEntry(
    likes: Playlist?,
    source: Source,
    nav: NavController,
) {
    item {
        SectionHeader(title = stringResource(R.string.min_likes))
        ListItem(
            headlineContent = { Text(likes?.name ?: stringResource(R.string.min_likes)) },
            supportingContent = {
                Text(
                    when {
                        likes == null && source == Source.KUGOU -> stringResource(R.string.min_kugou_likes_missing)
                        likes == null -> stringResource(R.string.min_no_folder)
                        likes.songCount > 0 -> stringResource(R.string.min_song_count, likes.songCount)
                        else -> source.labelText()
                    },
                )
            },
            leadingContent = {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = likes != null) {
                    likes?.let { nav.navigate(Routes.playlist(it.source, it.id)) }
                },
        )
    }
}

internal fun LazyListScope.playlistGroup(
    title: String,
    playlists: List<Playlist>,
    nav: NavController,
) {
    if (playlists.isEmpty()) return
    item { SectionHeader(title = title) }
    items(playlists.chunked(2), key = { it.first().key }) { pair ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pair.forEach { p ->
                PlaylistCard(p, Modifier.weight(1f)) { nav.navigate(Routes.playlist(p.source, p.id)) }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun PlaylistCard(p: Playlist, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
            if (p.songCount > 0) stringResource(R.string.min_song_count, p.songCount) else p.author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AccountCard(
    title: String,
    avatar: String?,
    name: String?,
    loggedIn: Boolean,
    statusText: @Composable () -> String,
    actions: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avatar != null) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title.take(1),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (loggedIn && name != null) name else title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        statusText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}
