package top.aryun.yzliyin.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.model.Song
import java.util.Locale

/** 封面图（占位 + 圆角）。 */
@Composable
fun Cover(
    url: String,
    modifier: Modifier = Modifier,
    corner: Int = 10,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val shape = RoundedCornerShape(corner.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isEmpty()) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 毫秒 → mm:ss */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * 分钟数 → 人话标签：「30 分钟」/「1 小时」/「1 小时 30 分」。
 * 设置里的时长滑杆和长时长确认弹框共用同一个函数，保证两处显示的阈值一字不差。
 */
@Composable
fun minutesLabel(minutes: Int): String = when {
    minutes >= 60 && minutes % 60 == 0 -> stringResource(R.string.set_hours, minutes / 60)
    minutes >= 60 -> stringResource(R.string.set_hours_minutes, minutes / 60, minutes % 60)
    else -> stringResource(R.string.set_minutes, minutes)
}

/**
 * 播放量的紧凑写法：单位**随语言变**，不能统一翻成一个词。
 *  - 中文：1.2万 / 1.3亿
 *  - 日文：1.2万 / 1.3億（「億」不是「亿」）
 *  - 其它（含英文）：12K / 1.3M / 1.2B
 *
 * 依据是 [Locale.getDefault]——AppLocale.wrap 已经把进程默认 Locale
 * 设成了设置里选的语言（跟随系统时还原则是系统语言）。
 */
fun formatCount(n: Long): String = when (Locale.getDefault().language) {
    Locale.CHINESE.language -> when {
        n >= 100_000_000 -> "%.1f亿".format(n / 100_000_000.0)
        n >= 10_000 -> "%.1f万".format(n / 10_000.0)
        else -> n.toString()
    }

    Locale.JAPANESE.language -> when {
        n >= 100_000_000 -> "%.1f億".format(n / 100_000_000.0)
        n >= 10_000 -> "%.1f万".format(n / 10_000.0)
        else -> n.toString()
    }

    else -> when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }
}

/**
 * 源的**显示名**，随界面语言切换（中文 / 日本語 / English）。
 *
 * ⚠ 与 [Source.label] 的分工：`label` 是写死的中文，**只喂 NPLogger**（日志不翻译）；
 * 凡是进界面的文案一律走这里。这样 `core/model` 不必依赖 R，也顺带给
 * 「日志恒中文」划出了一条硬边界。
 *
 * 用法：`stringResource(R.string.srch_in_source, source.labelText())`、
 * `Text(detail?.name ?: source.labelText())`。
 */
@Composable
fun Source.labelText(): String = stringResource(
    when (this) {
        Source.NETEASE -> R.string.src_netease
        Source.KUGOU -> R.string.src_kugou
        Source.BILIBILI -> R.string.src_bilibili
        Source.YOUTUBE -> R.string.src_youtube
        Source.LOCAL -> R.string.src_local
    },
)

/** 区块标题 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 歌曲列表行（支持选中态与长按进入多选）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playing: Boolean = false,
    selecting: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.width(4.dp))
        }
        if (index != null && !selecting) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = if (playing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        } else {
            Cover(url = song.cover, modifier = Modifier.size(44.dp), corner = 8)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (playing) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    if (song.artist.isNotEmpty()) append(song.artist)
                    if (song.albumName.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(song.albumName)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (song.durationMs > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

/** 加载中 */
@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 空态 / 错误态 */
@Composable
fun StateTip(
    text: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

/** 标签 chip */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
