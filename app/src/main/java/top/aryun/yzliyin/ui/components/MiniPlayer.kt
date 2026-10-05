package top.aryun.yzliyin.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes

/** 停靠式迷你播放条（贴在底栏正上方，非悬浮）。 */
@Composable
fun MiniPlayer(nav: NavController, modifier: Modifier = Modifier) {
    val state by PlayerController.state.collectAsStateWithLifecycle()
    val song = state.current ?: return

    val target =
        if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        else 0f
    val progress by animateFloatAsState(targetValue = target, label = "miniProgress")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { nav.navigate(Routes.PLAYER) },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CoilImage(
                    url = song.cover,
                    contentDescription = song.name,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        song.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.error != null) {
                    Text(
                        "!",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(onClick = { PlayerController.togglePlay() }) {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "暂停" else "播放",
                    )
                }
                IconButton(onClick = { PlayerController.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 简单封面图（避免在多处重复 AsyncImage 配置）。 */
@Composable
fun CoilImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url.isNotBlank()) {
            coil3.compose.AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
