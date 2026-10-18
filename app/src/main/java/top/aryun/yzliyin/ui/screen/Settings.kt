package top.aryun.yzliyin.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.data.SessionStore
import top.aryun.yzliyin.core.model.LyricLine
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.player.AudioCache
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.theme.AppTheme
import top.aryun.yzliyin.util.applyScreenOrientation

private data class Quality(val id: String, val label: String)

private data class Appearance(val id: String, val label: String)

/** FilterChip 选项：id 落盘、label 上屏；横屏竖屏与跨源跟随两组都用它。 */
private data class ChipOption(val id: String, val label: String)

/** 外观模式选项，id 与 [AppTheme] 常量一致并持久化到 SessionStore。 */
private val appearances = listOf(
    Appearance(AppTheme.SYSTEM, "跟随系统"),
    Appearance(AppTheme.LIGHT, "白天"),
    Appearance(AppTheme.DARK, "黑夜"),
)

/** 屏幕方向：手机想用平板那样的横屏排版、平板想锁竖屏，都从这里切。 */
private val orientationOptions = listOf(
    ChipOption("auto", "自动"),
    ChipOption("portrait", "竖屏"),
    ChipOption("landscape", "横屏"),
)

/** 跨源跟随元数据：id 与 SessionStore.metadataFollow 一致（off 为关闭、用当前源的封面/歌手）。 */
private val followOptions = listOf(
    ChipOption("off", "关闭"),
    ChipOption("netease", "网易云"),
    ChipOption("kugou", "酷狗"),
    ChipOption("bilibili", "哔哩哔哩"),
)

private val neteaseQualities = listOf(
    Quality("standard", "标准"),
    Quality("higher", "较高"),
    Quality("exhigh", "极高"),
    Quality("lossless", "无损"),
    Quality("hires", "Hi·Res"),
)

private val kugouQualities = listOf(
    Quality("auto", "自动"),
    Quality("128", "128K"),
    Quality("320", "320K"),
    Quality("flac", "无损"),
    Quality("high", "高品"),
    Quality("super", "Hi-Res"),
)

private val biliQualities = listOf(
    Quality("auto", "自动"),
    Quality("64", "64K"),
    Quality("132", "132K"),
    Quality("192", "192K"),
)

/**
 * 设置页的一节。主页只列入口（图标 + 标题 + 一句话说明），具体条目放在各节的子页里，
 * 组织方式参考 NeriPlayer 的设置页。
 */
private enum class SettingSection(
    val title: String,
    val desc: String,
    val icon: ImageVector,
) {
    ACCOUNTS("账号", "管理网易云、酷狗和哔哩哔哩登录状态", Icons.Filled.AccountCircle),
    THEME("主题", "深浅色模式、界面与字体大小", Icons.Outlined.Palette),
    QUALITY("音质", "各数据源的默认播放音质", Icons.Filled.Audiotrack),
    PLAYBACK("播放", "连播、跨源找音源与低频音效", Icons.AutoMirrored.Outlined.PlaylistPlay),
    LYRICS("歌词", "字号、波浪、光亮与自动滚动", Icons.Outlined.Subtitles),
    CACHE("存储与缓存", "缓存占用与清理", Icons.Outlined.Storage),
    LISTEN_TOGETHER("一起听", "创建或加入房间，和好友同步听歌", Icons.Outlined.Cloud),
    ABOUT("关于", "版本信息、数据源与开源声明", Icons.Outlined.Info),
}

/** 主页分组：账号/主题、音质/播放/歌词、缓存/一起听/关于。 */
private val settingsGroups = listOf(
    listOf(SettingSection.ACCOUNTS, SettingSection.THEME),
    listOf(SettingSection.QUALITY, SettingSection.PLAYBACK, SettingSection.LYRICS),
    listOf(SettingSection.CACHE, SettingSection.LISTEN_TOGETHER, SettingSection.ABOUT),
)

/** 与 [settingsGroups] 一一对应的分组标题。 */
private val settingsGroupTitles = listOf("账户与外观", "播放与歌词", "其他")

/**
 * 设置页（NeriPlayer 式）：主页是分组入口，点进去是对应子页。
 * 「我的」页已并入本页的「账号」节，底栏第四个 tab 由「我的」改为「设置」。
 *
 * 字体颜色固定为 黑夜纯白 / 白天纯黑，不跟随 Material You 动态取色，
 * 避免系统壁纸色导致深浅模式下正文对比度不足。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black

    // 存 name 而不是枚举本身，rememberSaveable 对枚举的可保存性没有保证
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val section = sectionName?.let { name -> SettingSection.values().firstOrNull { it.name == name } }

    if (section == null) {
        SettingsScaffold(topBar = { SettingsTitleBar(textColor, "设置") }) {
            settingsGroups.forEachIndexed { index, group ->
                GroupTitle(settingsGroupTitles[index])
                SettingsGroupCard(Modifier.padding(horizontal = 16.dp)) {
                    group.forEachIndexed { i, s ->
                        SettingsNavRow(s, textColor) { sectionName = s.name }
                        if (i != group.lastIndex) GroupDivider()
                    }
                }
                if (index != settingsGroups.lastIndex) Spacer(Modifier.height(14.dp))
            }
        }
    } else {
        SettingsDetail(
            section = section,
            onBack = { sectionName = null },
            nav = nav,
            textColor = textColor,
        )
    }
}

/** 页面骨架：补状态栏留白、提供滚动，顶部条与内容由调用方给出。 */
@Composable
private fun SettingsScaffold(
    topBar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        topBar()
        content()
        Spacer(Modifier.height(220.dp))
    }
}

@Composable
private fun SettingsTitleBar(textColor: Color, title: String) {
    Text(
        title,
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = textColor,
    )
}

/** 分组卡片：圆角容器里若干行，行间分隔线缩进到文字起点。 */
@Composable
private fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** 首页分组标题：primary 色小标题压在分组卡片上方。 */
@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 22.dp, top = 10.dp, end = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 区块引导标题：标题 + 一行说明，同为 primary 色。 */
@Composable
private fun SectionIntro(title: String, desc: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        desc?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.76f),
            )
        }
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        Modifier.padding(start = 74.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 主页入口行：42dp 图标位 + 标题/说明 + 右箭头，行高不低于 76dp。 */
@Composable
private fun SettingsNavRow(
    section: SettingSection,
    textColor: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.width(42.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                section.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(section.title, style = MaterialTheme.typography.bodyLarge, color = textColor)
            Text(
                section.desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f),
        )
    }
}

/** 子页：顶部返回 + 节头卡 + 内容分组卡。 */
@Composable
private fun SettingsDetail(
    section: SettingSection,
    onBack: () -> Unit,
    nav: NavController,
    textColor: Color,
) {
    SettingsScaffold(
        topBar = {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = textColor)
            }
        },
    ) {
        Surface(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        section.desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (section) {
                SettingSection.ACCOUNTS -> AccountsPane(nav)
                SettingSection.THEME -> ThemeSection()
                SettingSection.QUALITY -> QualitySection()
                SettingSection.PLAYBACK -> PlaybackSection()
                SettingSection.LYRICS -> LyricsSection()
                SettingSection.CACHE -> CacheSection()
                SettingSection.LISTEN_TOGETHER -> ListenTogetherSection(nav)
                SettingSection.ABOUT -> AboutSection()
            }
        }
    }
}

// ==================== 各节内容 ====================

@Composable
private fun ThemeSection() {
    val store = AppGraph.store
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black
    val activity = LocalContext.current as? Activity
    var orientation by remember { mutableStateOf(store.screenOrientation) }
    var uiScale by remember { mutableStateOf(AppTheme.uiScale) }
    var fontScale by remember { mutableStateOf(AppTheme.fontScale) }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro("外观模式", "白天 / 黑夜可随时切换，也可跟随手机的深色模式")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                appearances.forEach { a ->
                    FilterChip(
                        selected = AppTheme.mode == a.id,
                        onClick = { AppTheme.applyMode(a.id) },
                        label = { Text(a.label, color = textColor) },
                    )
                }
            }

            SectionIntro("屏幕方向", "锁定竖屏 / 横屏，或自动获取手机的方向")
            Text(
                "选「横屏」后界面会自动换成横屏排版（左侧导航栏、内容限宽居中）；改完立即生效",
                Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.75f),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                orientationOptions.forEach { o ->
                    FilterChip(
                        selected = orientation == o.id,
                        onClick = {
                            orientation = o.id
                            store.screenOrientation = o.id
                            activity?.let { applyScreenOrientation(it, o.id) }
                        },
                        label = { Text(o.label, color = textColor) },
                    )
                }
            }

            GroupDivider()

            SectionIntro("界面大小", "整体放大 / 缩小控件与间距，字体跟着一起变；改完立即生效")
            StyleSlider(
                label = "界面大小",
                valueLabel = scalePercent(uiScale),
                value = uiScale,
                range = SessionStore.UI_SCALE_MIN..SessionStore.UI_SCALE_MAX,
            ) {
                uiScale = it
                AppTheme.applyUiScale(it)
            }

            SectionIntro("字体大小", "只调文字大小，控件尺寸不变；在系统字号的基础上按比例缩放")
            StyleSlider(
                label = "字体大小",
                valueLabel = scalePercent(fontScale),
                value = fontScale,
                range = SessionStore.FONT_SCALE_MIN..SessionStore.FONT_SCALE_MAX,
            ) {
                fontScale = it
                AppTheme.applyFontScale(it)
            }
            TextButton(
                onClick = {
                    uiScale = 1f
                    fontScale = 1f
                    AppTheme.applyUiScale(1f)
                    AppTheme.applyFontScale(1f)
                },
                modifier = Modifier.padding(horizontal = 18.dp),
            ) { Text("恢复默认大小", color = textColor) }
        }
    }
}

@Composable
private fun QualitySection() {
    val store = AppGraph.store
    val scope = rememberCoroutineScope()
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black

    var tip by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableStateOf(store.playQuality) }
    var kugouQuality by remember { mutableStateOf(store.kugouQuality) }
    var biliQuality by remember { mutableStateOf(store.biliQuality) }

    fun applyQualityTip(label: String, q: Quality) {
        tip = "音质已切换为${q.label}，正在重新取链"
        scope.launch {
            runCatching { PlayerController.reapplyQuality() }
            tip = "$label 音质已切换为${q.label}"
        }
    }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro("播放音质", "会员歌曲按账号权益下发，非会员自动降到可用音质；酷狗 / 哔哩哔哩按所选音质取链，取不到自动回退")
            Text(
                "会员歌曲按账号权益下发，非会员自动降到可用音质；酷狗 / 哔哩哔哩按所选音质取链，取不到自动回退",
                Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.75f),
            )
            QualityGroup("网易云音乐", neteaseQualities, quality, textColor) { q ->
                quality = q.id
                store.playQuality = q.id
                applyQualityTip("网易云", q)
            }
            QualityGroup("酷狗音乐", kugouQualities, kugouQuality, textColor) { q ->
                kugouQuality = q.id
                store.kugouQuality = q.id
                applyQualityTip("酷狗", q)
            }
            QualityGroup("哔哩哔哩", biliQualities, biliQuality, textColor) { q ->
                biliQuality = q.id
                store.biliQuality = q.id
                applyQualityTip("哔哩哔哩", q)
            }
            tip?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun PlaybackSection() {
    val store = AppGraph.store
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black

    var autoPlayOnLaunch by remember { mutableStateOf(store.autoPlayOnLaunch) }
    var autoPlay by remember { mutableStateOf(store.autoPlayNext) }
    var autoSourceFallback by remember { mutableStateOf(store.autoSourceFallback) }
    var metadataFollow by remember { mutableStateOf(store.metadataFollow) }
    var effectNetease by remember { mutableStateOf(store.effectEnabled(Source.NETEASE)) }
    var effectKugou by remember { mutableStateOf(store.effectEnabled(Source.KUGOU)) }
    var effectBili by remember { mutableStateOf(store.effectEnabled(Source.BILIBILI)) }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro("播放行为", null)
            SwitchRow(
                title = "启动时自动播放",
                desc = "打开 App 后自动接着上次的队列继续播放；关闭则停在暂停状态，等你手动点播放",
                checked = autoPlayOnLaunch,
                textColor = textColor,
                onCheckedChange = {
                    autoPlayOnLaunch = it
                    store.autoPlayOnLaunch = it
                },
            )
            SwitchRow(
                title = "自动连播",
                desc = "关闭后，当前列表播完将暂停在最后一首",
                checked = autoPlay,
                textColor = textColor,
                onCheckedChange = {
                    autoPlay = it
                    store.autoPlayNext = it
                },
            )
            SwitchRow(
                title = "自动找音源",
                desc = "当前源放不出来时，按 网易云 → 酷狗 → 哔哩哔哩 的顺序去别的源找同一首歌（按歌名、歌手、时长匹配）",
                checked = autoSourceFallback,
                textColor = textColor,
                onCheckedChange = {
                    autoSourceFallback = it
                    store.autoSourceFallback = it
                },
            )
            SectionIntro("跨源跟随", "播放时的封面 / 歌手 / 歌词要不要跟着另一个源")
            Text(
                "选了某个源后，播放页与迷你条的封面、歌手、歌词优先取自该源（按歌名 + 歌手 + 时长匹配同一首，取不到再回落当前源）。" +
                    "选「关闭」时封面歌手用当前源的，歌词在本源取不到时（如 B 站）仍会自动去网易云 / 酷狗补上。",
                Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.75f),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                followOptions.forEach { o ->
                    FilterChip(
                        selected = metadataFollow == o.id,
                        onClick = {
                            metadataFollow = o.id
                            store.metadataFollow = o.id
                            // 在播的封面 / 歌手立刻按新选择重算（没选就还原成源站信息）
                            PlayerController.refreshDisplayMetadata()
                        },
                        label = { Text(o.label, color = textColor) },
                    )
                }
            }
        }
    }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro("音效", "低频增强按数据源单独生效，只影响该源歌曲（部分机型可能无效）")
            Text(
                "低频增强按数据源单独生效，只影响该源歌曲（部分机型可能无效）",
                Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.75f),
            )
            SwitchRow(
                title = "网易云音乐",
                desc = "播放网易云歌曲时对低频做增益",
                checked = effectNetease,
                textColor = textColor,
                onCheckedChange = {
                    effectNetease = it
                    store.setEffect(Source.NETEASE, it)
                    PlayerController.refreshAudioEffect()
                },
            )
            SwitchRow(
                title = "酷狗音乐",
                desc = "播放酷狗歌曲时对低频做增益",
                checked = effectKugou,
                textColor = textColor,
                onCheckedChange = {
                    effectKugou = it
                    store.setEffect(Source.KUGOU, it)
                    PlayerController.refreshAudioEffect()
                },
            )
            SwitchRow(
                title = "哔哩哔哩",
                desc = "播放 B 站音频时对低频做增益",
                checked = effectBili,
                textColor = textColor,
                onCheckedChange = {
                    effectBili = it
                    store.setEffect(Source.BILIBILI, it)
                    PlayerController.refreshAudioEffect()
                },
            )
        }
    }
}

@Composable
private fun LyricsSection() {
    val store = AppGraph.store
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black

    var style by remember { mutableStateOf(LyricStyle.load(store)) }
    var autoScroll by remember { mutableStateOf(store.lyricAutoScroll) }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro("歌词样式", "调整后立即生效；播放页右上角的三个点菜单里也能改")
            Text(
                "调整后立即生效；播放页右上角的三个点菜单里也能改",
                Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.75f),
            )
            StyleSlider(
                label = "字体大小",
                valueLabel = "${(style.fontSize * 100).toInt()}%",
                value = style.fontSize,
                range = SessionStore.LYRIC_FONT_MIN..SessionStore.LYRIC_FONT_MAX,
            ) {
                style = style.copy(fontSize = it)
                style.save(store)
            }
            StyleSlider(
                label = "弯曲程度",
                valueLabel = "${(style.wave * 100).toInt()}%",
                value = style.wave,
                range = 0f..1f,
            ) {
                style = style.copy(wave = it)
                style.save(store)
            }
            StyleSlider(
                label = "光亮程度",
                valueLabel = "${(style.glow * 100).toInt()}%",
                value = style.glow,
                range = 0f..1f,
            ) {
                style = style.copy(glow = it)
                style.save(store)
            }

            // 预览用高亮行渲染，和播放页是同一套实现
            LyricLineView(
                line = LyricLine(0L, "Yesterday once more", "昨日重现"),
                active = true,
                style = style,
                onClick = {},
            )
            TextButton(
                onClick = {
                    style = LyricStyle()
                    style.save(store)
                },
                modifier = Modifier.padding(horizontal = 18.dp),
            ) { Text("恢复默认", color = textColor) }
        }
    }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro("歌词行为", null)
            SwitchRow(
                title = "自动滚动",
                desc = "播放时歌词跟随进度自动滚动",
                checked = autoScroll,
                textColor = textColor,
                onCheckedChange = {
                    autoScroll = it
                    store.lyricAutoScroll = it
                },
            )
        }
    }
}

@Composable
private fun CacheSection() {
    val context = LocalContext.current
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black

    var dataCache by remember { mutableStateOf("") }
    var audioCache by remember { mutableStateOf(0L) }
    var audioCount by remember { mutableStateOf(0) }

    fun refresh() {
        dataCache = formatSize(context.cacheDir?.let { dir ->
            runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        } ?: 0L)
        audioCache = AudioCache.totalBytes()
        audioCount = AudioCache.cachedCount()
    }

    LaunchedEffect(Unit) { refresh() }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro("缓存占用", null)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("图片与数据缓存", style = MaterialTheme.typography.bodyMedium, color = textColor)
                    Text(
                        "当前占用 $dataCache",
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.75f),
                    )
                }
                TextButton(onClick = {
                    runCatching {
                        context.cacheDir?.deleteRecursively()
                        refresh()
                    }
                }) { Text("清除缓存", color = textColor) }
            }

            GroupDivider()

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("音频缓存", style = MaterialTheme.typography.bodyMedium, color = textColor)
                    Text(
                        "$audioCount 首 · ${formatSize(audioCache)}，在「曲库 → 本地」里可单曲删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ListenTogetherSection(nav: NavController) {
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black
    val ltSession by AppGraph.listenTogether.sessionState.collectAsStateWithLifecycle()

    SettingsGroupCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { nav.navigate(Routes.LISTEN_TOGETHER) }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.width(42.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                )
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("一起听房间", style = MaterialTheme.typography.bodyLarge, color = textColor)
                Text(
                    if (!ltSession.roomId.isNullOrBlank()) {
                        "房间 ${ltSession.roomId} · ${if (ltSession.role == "controller") "房主" else "听众"}"
                    } else {
                        "创建或加入房间，和好友同步听歌"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f),
            )
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            InfoRow("应用", "音之理音 1.0.0", textColor)
            InfoRow("包名", "top.aryun.yzliyin", textColor)
            InfoRow("数据源", "网易云音乐 · 酷狗音乐 · 哔哩哔哩", textColor)
            InfoRow("技术栈", "Jetpack Compose · Material 3 · Media3", textColor)
            InfoRow("开源许可", "参考项目 NeriPlayer（GPLv3），保留原版权声明", textColor)
        }
    }

    // 两张外链卡片：点击交给系统浏览器打开
    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            LinkCard(
                icon = Icons.Outlined.Code,
                title = "Frestoy5",
                desc = "github.com/frestoy5",
                url = GITHUB_URL,
                context = context,
            )
            GroupDivider()
            LinkCard(
                icon = Icons.Outlined.PlayCircle,
                title = "UP主:猫羽圆圆",
                desc = "b23.tv/YU6ge0n",
                url = VIDEO_URL,
                context = context,
            )
        }
    }
}

/** 关于页外链卡片的目标地址：第一个是 GitHub 主页，第二个是 B 站视频。 */
private const val GITHUB_URL = "https://github.com/frestoy5/"
private const val VIDEO_URL = "https://b23.tv/YU6ge0n"

/** 外链卡片行：图标 + 标题 + 说明 + 右箭头，点击在浏览器里打开 [url]。 */
@Composable
private fun LinkCard(
    icon: ImageVector,
    title: String,
    desc: String,
    url: String,
    context: Context,
) {
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { openInBrowser(context, url) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.width(42.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = textColor)
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f),
        )
    }
}

/**
 * 在浏览器里打开链接。先按 MAIN + APP_BROWSER 挑出系统默认浏览器并把链接指定给它，
 * 这样 b23.tv 这类被 App Links 认领的域名不会被 B 站等应用抢走；
 * 挑不到浏览器时退回普通 VIEW 跳转，都失败再给个提示。
 */
private fun openInBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    val browserPkg = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_APP_BROWSER)
        .resolveActivity(context.packageManager)
        ?.packageName
    val launched = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    browserPkg?.let { setPackage(it) }
                },
        )
    }.isSuccess
    if (launched) return
    val fallback = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val ok = runCatching { context.startActivity(fallback) }.isSuccess
    if (!ok) Toast.makeText(context, "未检测到能打开链接的应用", Toast.LENGTH_SHORT).show()
}

// ==================== 通用小部件 ====================

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    textColor: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = textColor)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.75f),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 某个数据源的音质选项组（标签 + 可换行的音质 chips）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityGroup(
    title: String,
    options: List<Quality>,
    selected: String,
    textColor: Color,
    onSelect: (Quality) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(
            title,
            Modifier.padding(horizontal = 18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { q ->
                FilterChip(
                    selected = selected == q.id,
                    onClick = { if (selected != q.id) onSelect(q) },
                    label = { Text(q.label, color = textColor) },
                )
            }
        }
    }
}

/** 倍数 → 百分比标签，如 1f → "100%"。 */
private fun scalePercent(scale: Float): String = "${(scale * 100).toInt()}%"

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

@Composable
private fun InfoRow(k: String, v: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Text(
            k,
            Modifier.width(76.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color.copy(alpha = 0.75f),
        )
        Text(v, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
