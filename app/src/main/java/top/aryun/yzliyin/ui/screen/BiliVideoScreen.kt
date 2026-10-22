package top.aryun.yzliyin.ui.screen

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.BiliVideoStreams
import top.aryun.yzliyin.player.PlaybackService
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.BiliQuadButton

/**
 * 应用内观看 B 站投稿：取直链后用 ExoPlayer 播放（DASH 画面轨 + 声音轨合并成一路）。
 *
 * 打开即暂停正在放的音乐，免得两路声音打架；退出不自动恢复，
 * 迷你条还挂着，想接着听自己点播放。
 * 顶栏右侧加了评论与一键四连入口。
 */
@Composable
fun BiliVideoScreen(
    bvid: String,
    cid: String,
    name: String,
    nav: NavController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var streams by remember { mutableStateOf<BiliVideoStreams?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    // 进出这个页面各建各的播放器，退出即释放
    val player = remember {
        ExoPlayer.Builder(context).build()
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(bvid, cid, retryTick) {
        loading = true
        error = null
        streams = null
        PlayerController.pause()
        val result = runCatching { BiliApi.videoStreams(bvid, cid) }.getOrNull()
        if (result == null) {
            error = context.getString(R.string.bili_video_load_fail)
        } else {
            streams = result
        }
        loading = false
    }

    // 直链到手后组装音画两路并开播；数据源带上 B 站 CDN 要求的 Referer / UA，否则 403
    LaunchedEffect(streams) {
        val s = streams ?: return@LaunchedEffect
        val factory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(PlaybackService.BILI_REQUEST_HEADERS)
        val video = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(s.videoUrl)
                    .setMimeType(s.videoMime.ifBlank { "video/mp4" })
                    .build(),
            )
        val mediaSource = if (s.audioUrl.isBlank()) {
            // durl 兜底的整条 MP4 自带音轨
            video
        } else {
            MergingMediaSource(
                video,
                ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(
                        MediaItem.Builder().setUri(s.audioUrl).setMimeType("audio/mp4").build(),
                    ),
            )
        }
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            loading -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )

            error != null -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(error.orEmpty(), color = Color.White, modifier = Modifier.padding(horizontal = 32.dp))
                TextButton(onClick = { retryTick++ }) { Text(stringResource(R.string.bili_retry)) }
            }

            else -> AndroidView(
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
        }

        // 顶部：返回 + 投稿标题，盖在画面上
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
                name.ifBlank { stringResource(R.string.bili_video_title_fallback) },
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            // 评论入口
            IconButton(onClick = {
                nav.navigate(Routes.biliComments(bvid, name))
            }) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = stringResource(R.string.bili_video_comment),
                    tint = Color.White,
                )
            }
            // 一键四连
            BiliQuadButton(
                bvid = bvid,
                nav = nav,
                tint = Color.White,
            )
        }
    }
}
