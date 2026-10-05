package top.aryun.yzliyin.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.LyricLine
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.formatDuration

@Composable
fun PlayerScreen(nav: NavController) {
    val state by PlayerController.state.collectAsStateWithLifecycle()
    val song = state.current

    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricTip by remember { mutableStateOf<String?>(null) }
    var seeking by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(song?.id) {
        lyrics = emptyList()
        lyricTip = null
        val s = song ?: return@LaunchedEffect
        lyricTip = "歌词加载中…"
        runCatching { NeteaseApi.lyric(s.id) }
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

    LaunchedEffect(activeIndex, showLyrics) {
        val autoScroll = AppGraph.store.lyricAutoScroll
        if (activeIndex >= 0 && showLyrics && autoScroll) {
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
                        modifier = Modifier.clickable {
                            if (song.artistId.isNotEmpty()) {
                                nav.navigate(Routes.artist(song.artistId))
                            }
                        },
                    )
                }
                if (state.resolving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { nav.navigate(Routes.comments(song.id, song.name)) }) {
                        Icon(
                            Icons.Filled.ChatBubbleOutline,
                            contentDescription = "评论",
                        )
                    }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!showLyrics) {
                    AsyncImage(
                        model = song.cover,
                        contentDescription = song.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp)),
                    )
                } else if (lyrics.isEmpty()) {
                    Text(
                        lyricTip ?: "暂无歌词",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(lyrics) { i, line ->
                            Text(
                                line.text,
                                fontSize = if (i == activeIndex) 19.sp else 15.sp,
                                fontWeight = if (i == activeIndex) FontWeight.Bold else FontWeight.Normal,
                                color = if (i == activeIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { PlayerController.seekTo(line.timeMs) },
                            )
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shownMs = ((seeking ?: progress) * state.durationMs).toLong()
                    Text(
                        formatDuration(shownMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = seeking ?: progress,
                        onValueChange = { seeking = it },
                        onValueChangeFinished = {
                            val target = seeking
                            if (target != null && state.durationMs > 0) {
                                PlayerController.seekTo((target * state.durationMs).toLong())
                            }
                            seeking = null
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        formatDuration(state.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        Icon(
                            Icons.Filled.Repeat,
                            contentDescription = "循环模式",
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

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (showLyrics) "点击歌词可跳转 · 点此返回封面" else "点击此处查看歌词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { showLyrics = !showLyrics },
                    )
                }
            }
        }
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
