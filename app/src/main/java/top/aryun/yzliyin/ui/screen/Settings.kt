package top.aryun.yzliyin.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.pow
import top.aryun.yzliyin.BuildConfig
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.data.SessionStore
import top.aryun.yzliyin.core.model.LyricLine
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.player.AudioCache
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.minutesLabel
import top.aryun.yzliyin.ui.theme.AppTheme
import top.aryun.yzliyin.util.AppLocale
import top.aryun.yzliyin.util.applyScreenOrientation

private data class Quality(val id: String, @StringRes val label: Int)

private data class Appearance(val id: String, @StringRes val label: Int)

/**
 * FilterChip 选项：id 落盘、label 上屏；横屏竖屏与跨源跟随两组都用它。
 *
 * label 是泛型而不是固定 @StringRes Int：界面语言那组要按各自原生语言显示
 * 「中文 / 日本語 / English」（不翻译、不上资源），其余组存资源 id、上屏前再
 * stringResource。
 */
private data class ChipOption<T>(val id: String, val label: T)

/** 外观模式选项，id 与 [AppTheme] 常量一致并持久化到 SessionStore。 */
private val appearances = listOf(
    Appearance(AppTheme.SYSTEM, R.string.lang_follow_system),
    Appearance(AppTheme.LIGHT, R.string.set_theme_light),
    Appearance(AppTheme.DARK, R.string.set_theme_dark),
)

/** 屏幕方向：手机想用平板那样的横屏排版、平板想锁竖屏，都从这里切。 */
private val orientationOptions = listOf(
    ChipOption("auto", R.string.set_auto),
    ChipOption("portrait", R.string.set_orientation_portrait),
    ChipOption("landscape", R.string.set_orientation_landscape),
)

/**
 * 界面语言选项：id 与 [AppLocale] 常量一致，与 SessionStore.language 对齐。
 * 三个语言名按**它们自己的语言**显示（不翻译）；「跟随系统」那项的 label 不上屏，
 * 实际取 stringResource(lang_follow_system)，才能跟着当前界面语言走。
 */
private val languageOptions = listOf(
    ChipOption(AppLocale.SYSTEM, "跟随系统"),
    ChipOption(AppLocale.ZH, "中文"),
    ChipOption(AppLocale.JA, "日本語"),
    ChipOption(AppLocale.EN, "English"),
)

/** 跨源跟随元数据：id 与 SessionStore.metadataFollow 一致（off 为关闭、用当前源的封面/歌手）。 */
private val followOptions = listOf(
    ChipOption("off", R.string.set_follow_off),
    ChipOption("netease", R.string.set_src_netease),
    ChipOption("kugou", R.string.set_src_kugou),
    ChipOption("bilibili", R.string.set_src_bilibili),
)

private val neteaseQualities = listOf(
    Quality("standard", R.string.set_q_standard),
    Quality("higher", R.string.set_q_higher),
    Quality("exhigh", R.string.set_q_exhigh),
    Quality("lossless", R.string.set_q_lossless),
    Quality("hires", R.string.set_q_hires),
)

private val kugouQualities = listOf(
    Quality("auto", R.string.set_auto),
    Quality("128", R.string.set_q_128k),
    Quality("320", R.string.set_q_320k),
    Quality("flac", R.string.set_q_lossless),
    Quality("high", R.string.set_q_high),
    Quality("super", R.string.set_q_super),
)

private val biliQualities = listOf(
    Quality("auto", R.string.set_auto),
    Quality("64", R.string.set_q_64k),
    Quality("132", R.string.set_q_132k),
    Quality("192", R.string.set_q_192k),
)

/**
 * 设置页的一节。主页只列入口（图标 + 标题 + 一句话说明），具体条目放在各节的子页里，
 * 组织方式参考 NeriPlayer 的设置页。
 */
private enum class SettingSection(
    @StringRes val title: Int,
    @StringRes val desc: Int,
    val icon: ImageVector,
) {
    ACCOUNTS(
        R.string.set_sec_accounts_title,
        R.string.set_sec_accounts_desc,
        Icons.Filled.AccountCircle,
    ),
    THEME(R.string.set_sec_theme_title, R.string.set_sec_theme_desc, Icons.Outlined.Palette),
    QUALITY(R.string.set_sec_quality_title, R.string.set_sec_quality_desc, Icons.Filled.Audiotrack),
    PLAYBACK(
        R.string.set_sec_playback_title,
        R.string.set_sec_playback_desc,
        Icons.AutoMirrored.Outlined.PlaylistPlay,
    ),
    LYRICS(R.string.set_sec_lyrics_title, R.string.set_sec_lyrics_desc, Icons.Outlined.Subtitles),
    CACHE(R.string.set_sec_cache_title, R.string.set_sec_cache_desc, Icons.Outlined.Storage),
    LISTEN_TOGETHER(
        R.string.set_sec_listen_together_title,
        R.string.set_sec_listen_together_desc,
        Icons.Outlined.Cloud,
    ),
    HOOK(R.string.set_sec_hook_title, R.string.set_sec_hook_desc, Icons.Outlined.Extension),
    ABOUT(R.string.set_sec_about_title, R.string.set_sec_about_desc, Icons.Outlined.Info),
}

/** 主页分组：账号/主题、音质/播放/歌词、缓存/一起听/Hook/关于。 */
private val settingsGroups = listOf(
    listOf(SettingSection.ACCOUNTS, SettingSection.THEME),
    listOf(SettingSection.QUALITY, SettingSection.PLAYBACK, SettingSection.LYRICS),
    listOf(SettingSection.CACHE, SettingSection.LISTEN_TOGETHER, SettingSection.HOOK, SettingSection.ABOUT),
)

/** 与 [settingsGroups] 一一对应的分组标题。 */
private val settingsGroupTitles = listOf(
    R.string.set_group_accounts,
    R.string.set_group_playback,
    R.string.set_group_other,
)

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
        SettingsScaffold(topBar = { SettingsTitleBar(textColor, stringResource(R.string.set_settings_title)) }) {
            settingsGroups.forEachIndexed { index, group ->
                GroupTitle(stringResource(settingsGroupTitles[index]))
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
            Text(stringResource(section.title), style = MaterialTheme.typography.bodyLarge, color = textColor)
            Text(
                stringResource(section.desc),
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.set_back), tint = textColor)
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
                    Text(stringResource(section.title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(section.desc),
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
                SettingSection.HOOK -> HookSection()
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
    var language by remember { mutableStateOf(store.language) }
    var uiScale by remember { mutableStateOf(AppTheme.uiScale) }
    var fontScale by remember { mutableStateOf(AppTheme.fontScale) }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(
                stringResource(R.string.set_appearance_title),
                stringResource(R.string.set_appearance_desc),
            )
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
                        label = { Text(stringResource(a.label), color = textColor) },
                    )
                }
            }

            SectionIntro(
                stringResource(R.string.settings_language_title),
                stringResource(R.string.settings_language_desc),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                languageOptions.forEach { l ->
                    FilterChip(
                        selected = language == l.id,
                        onClick = {
                            if (language != l.id) {
                                language = l.id
                                store.language = l.id
                                // 让深层代码（core/net 的错误文案、通知栏）立刻按新语言取文案；
                                // Activity 那边还要重包 Context，只能靠下面的重建。
                                AppLocale.setLanguage(l.id)
                                activity?.recreate()
                            }
                        },
                        label = {
                            // 「跟随系统」要跟着当前界面语言翻译；
                            // 三个语言名按各自的原生语言显示，这是通行做法、不翻译。
                            Text(
                                if (l.id == AppLocale.SYSTEM) {
                                    stringResource(R.string.lang_follow_system)
                                } else {
                                    l.label
                                },
                                color = textColor,
                            )
                        },
                    )
                }
            }

            SectionIntro(
                stringResource(R.string.set_orientation_title),
                stringResource(R.string.set_orientation_desc),
            )
            Text(
                stringResource(R.string.set_orientation_note),
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
                        label = { Text(stringResource(o.label), color = textColor) },
                    )
                }
            }

            GroupDivider()

            SectionIntro(
                stringResource(R.string.set_ui_size),
                stringResource(R.string.set_ui_size_desc),
            )
            StyleSlider(
                label = stringResource(R.string.set_ui_size),
                valueLabel = scalePercent(uiScale),
                value = uiScale,
                range = SessionStore.UI_SCALE_MIN..SessionStore.UI_SCALE_MAX,
            ) {
                uiScale = it
                AppTheme.applyUiScale(it)
            }

            SectionIntro(
                stringResource(R.string.set_font_size),
                stringResource(R.string.set_font_size_desc),
            )
            StyleSlider(
                label = stringResource(R.string.set_font_size),
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
            ) { Text(stringResource(R.string.set_restore_default_size), color = textColor) }
        }
    }

    // 网络徽标的刷新与探测参数：改完下一次轮询即生效
    var netRefreshSec by remember { mutableStateOf(store.netRefreshSec) }
    var netTimeoutMs by remember { mutableStateOf(store.netProbeTimeoutMs) }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(
                stringResource(R.string.set_net_refresh_title),
                stringResource(R.string.set_net_refresh_desc),
            )
            StyleSlider(
                label = stringResource(R.string.set_refresh_interval),
                valueLabel = secLabel(netRefreshSec),
                value = secToSliderPos(netRefreshSec),
                range = 0f..1f,
            ) {
                netRefreshSec = sliderPosToSec(it)
                store.netRefreshSec = netRefreshSec
            }

            // 在刷新速度下面：单次延迟探测的超时，调大后慢网下不容易被判成未连接
            SectionIntro(
                stringResource(R.string.set_net_timeout_title),
                stringResource(R.string.set_net_timeout_desc),
            )
            StyleSlider(
                label = stringResource(R.string.set_probe_timeout),
                valueLabel = "${netTimeoutMs}ms",
                value = netTimeoutMs.toFloat(),
                range = SessionStore.NET_PROBE_TIMEOUT_MIN.toFloat()..SessionStore.NET_PROBE_TIMEOUT_MAX.toFloat(),
            ) {
                netTimeoutMs = it.toInt()
                store.netProbeTimeoutMs = netTimeoutMs
            }
        }
    }
}

@Composable
private fun QualitySection() {
    val store = AppGraph.store
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black

    var tip by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableStateOf(store.playQuality) }
    var kugouQuality by remember { mutableStateOf(store.kugouQuality) }
    var biliQuality by remember { mutableStateOf(store.biliQuality) }

    fun applyQualityTip(label: String, q: Quality) {
        tip = context.getString(R.string.set_quality_tip_switching, context.getString(q.label))
        scope.launch {
            runCatching { PlayerController.reapplyQuality() }
            tip = context.getString(R.string.set_quality_tip_done, label, context.getString(q.label))
        }
    }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(
                stringResource(R.string.set_quality_playback_title),
                stringResource(R.string.set_quality_playback_desc),
            )
            Text(
                stringResource(R.string.set_quality_playback_desc),
                Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.75f),
            )
            QualityGroup(R.string.set_src_netease_music, neteaseQualities, quality, textColor) { q ->
                quality = q.id
                store.playQuality = q.id
                applyQualityTip(context.getString(R.string.set_src_netease), q)
            }
            QualityGroup(R.string.set_src_kugou_music, kugouQualities, kugouQuality, textColor) { q ->
                kugouQuality = q.id
                store.kugouQuality = q.id
                applyQualityTip(context.getString(R.string.set_src_kugou), q)
            }
            QualityGroup(R.string.set_src_bilibili, biliQualities, biliQuality, textColor) { q ->
                biliQuality = q.id
                store.biliQuality = q.id
                applyQualityTip(context.getString(R.string.set_src_bilibili), q)
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
    var longWarn by remember { mutableStateOf(store.durationWarnEnabled) }
    var longMinutes by remember { mutableStateOf(store.durationWarnMinutes) }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(stringResource(R.string.set_pb_behavior_title))
            SwitchRow(
                title = R.string.set_auto_play_on_launch_title,
                desc = R.string.set_auto_play_on_launch_desc,
                checked = autoPlayOnLaunch,
                textColor = textColor,
                onCheckedChange = {
                    autoPlayOnLaunch = it
                    store.autoPlayOnLaunch = it
                },
            )
            SwitchRow(
                title = R.string.set_auto_next_title,
                desc = R.string.set_auto_next_desc,
                checked = autoPlay,
                textColor = textColor,
                onCheckedChange = {
                    autoPlay = it
                    store.autoPlayNext = it
                },
            )
            SwitchRow(
                title = R.string.set_auto_source_title,
                desc = R.string.set_auto_source_desc,
                checked = autoSourceFallback,
                textColor = textColor,
                onCheckedChange = {
                    autoSourceFallback = it
                    store.autoSourceFallback = it
                },
            )
            SectionIntro(
                stringResource(R.string.set_follow_title),
                stringResource(R.string.set_follow_desc),
            )
            Text(
                stringResource(R.string.set_follow_note),
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
                        label = { Text(stringResource(o.label), color = textColor) },
                    )
                }
            }
        }
    }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(
                stringResource(R.string.set_effect_title),
                stringResource(R.string.set_effect_desc),
            )
            Text(
                stringResource(R.string.set_effect_desc),
                Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.75f),
            )
            SwitchRow(
                title = R.string.set_src_netease_music,
                desc = R.string.set_effect_netease_desc,
                checked = effectNetease,
                textColor = textColor,
                onCheckedChange = {
                    effectNetease = it
                    store.setEffect(Source.NETEASE, it)
                    PlayerController.refreshAudioEffect()
                },
            )
            SwitchRow(
                title = R.string.set_src_kugou_music,
                desc = R.string.set_effect_kugou_desc,
                checked = effectKugou,
                textColor = textColor,
                onCheckedChange = {
                    effectKugou = it
                    store.setEffect(Source.KUGOU, it)
                    PlayerController.refreshAudioEffect()
                },
            )
            SwitchRow(
                title = R.string.set_src_bilibili,
                desc = R.string.set_effect_bilibili_desc,
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

    // 长时长确认：超长音频（电台、直播回放、长视频）点开前先弹「确定播放 → 算题」
    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(stringResource(R.string.set_pb_longwarn_title))
            SwitchRow(
                title = R.string.set_pb_longwarn_switch,
                desc = R.string.set_pb_longwarn_switch_desc,
                checked = longWarn,
                textColor = textColor,
                onCheckedChange = {
                    longWarn = it
                    store.durationWarnEnabled = it
                },
            )
            GroupDivider()
            SectionIntro(
                stringResource(R.string.set_pb_longwarn_limit),
                stringResource(R.string.set_pb_longwarn_limit_desc),
            )
            StyleSlider(
                label = stringResource(R.string.set_pb_longwarn_limit),
                valueLabel = minutesLabel(longMinutes),
                value = longMinutes.toFloat(),
                range = SessionStore.DURATION_WARN_MIN_MIN.toFloat()
                    ..SessionStore.DURATION_WARN_MIN_MAX.toFloat(),
            ) {
                longMinutes = it.toInt()
                store.durationWarnMinutes = longMinutes
            }
        }
    }
}

@Composable
private fun LyricsSection() {
    val store = AppGraph.store
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black

    var style by remember { mutableStateOf(LyricStyle.load(store)) }
    var autoScroll by remember { mutableStateOf(store.lyricAutoScroll) }
    var showTranslation by remember { mutableStateOf(store.lyricShowTranslation) }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(
                stringResource(R.string.set_lyric_style_title),
                stringResource(R.string.set_lyric_style_desc),
            )
            Text(
                stringResource(R.string.set_lyric_style_desc),
                Modifier.padding(horizontal = 18.dp),
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.75f),
            )
            StyleSlider(
                label = stringResource(R.string.set_font_size),
                valueLabel = stringResource(R.string.set_percent, "${(style.fontSize * 100).toInt()}"),
                value = style.fontSize,
                range = SessionStore.LYRIC_FONT_MIN..SessionStore.LYRIC_FONT_MAX,
            ) {
                style = style.copy(fontSize = it)
                style.save(store)
            }
            StyleSlider(
                label = stringResource(R.string.set_lyric_wave),
                valueLabel = stringResource(R.string.set_percent, "${(style.wave * 100).toInt()}"),
                value = style.wave,
                range = 0f..1f,
            ) {
                style = style.copy(wave = it)
                style.save(store)
            }
            StyleSlider(
                label = stringResource(R.string.set_lyric_glow),
                valueLabel = stringResource(R.string.set_percent, "${(style.glow * 100).toInt()}"),
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
            ) { Text(stringResource(R.string.set_restore_default), color = textColor) }
        }
    }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(stringResource(R.string.set_lyric_behavior_title))
            SwitchRow(
                title = R.string.set_autoscroll_title,
                desc = R.string.set_autoscroll_desc,
                checked = autoScroll,
                textColor = textColor,
                onCheckedChange = {
                    autoScroll = it
                    store.lyricAutoScroll = it
                },
            )
            SwitchRow(
                title = R.string.set_lyric_translation_title,
                desc = R.string.set_lyric_translation_desc,
                checked = showTranslation,
                textColor = textColor,
                onCheckedChange = {
                    showTranslation = it
                    // 与上面的外观滑杆共用一份存储，改完连同预览一起刷新
                    style = style.copy(showTranslation = it)
                    style.save(store)
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
    var autoCache by remember { mutableStateOf(AppGraph.store.autoCacheAudio) }
    // 缓存文件夹与容量上限：改完要同步回界面，所以各自持一份 state
    var cacheDir by remember { mutableStateOf(AudioCache.dirUri()) }
    var limitBytes by remember { mutableStateOf(AppGraph.store.audioCacheLimitBytes) }
    var showLimitDialog by remember { mutableStateOf(false) }

    fun refresh() {
        // 先复核目录里实际有哪些文件再统计，被文件管理器删掉的才不会继续算进占用
        AudioCache.refresh()
        dataCache = formatSize(context.cacheDir?.let { dir ->
            runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        } ?: 0L)
        audioCache = AudioCache.totalBytes()
        audioCount = AudioCache.cachedCount()
    }

    LaunchedEffect(Unit) { refresh() }

    // SAF 目录选择器：返回 tree uri，授权后切过去，之后新缓存都写进这个文件夹
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (AudioCache.setCacheDir(context, uri.toString())) {
            cacheDir = AudioCache.dirUri()
            Toast.makeText(context, R.string.set_cache_loc_done, Toast.LENGTH_SHORT).show()
            refresh()
        } else {
            Toast.makeText(context, R.string.set_cache_loc_fail, Toast.LENGTH_SHORT).show()
        }
    }

    if (showLimitDialog) {
        CacheLimitDialog(
            initialBytes = limitBytes,
            defaultLabel = formatSize(AudioCache.defaultMaxBytes()),
            onDismiss = { showLimitDialog = false },
            onConfirm = { bytes ->
                AppGraph.store.audioCacheLimitBytes = bytes
                limitBytes = bytes
                AudioCache.trimToLimit()
                showLimitDialog = false
                refresh()
            },
        )
    }

    // 自动缓存单独一张卡，下面是缓存用量
    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SwitchRow(
                title = R.string.set_cache_auto_title,
                desc = R.string.set_cache_auto_desc,
                checked = autoCache,
                textColor = textColor,
                onCheckedChange = {
                    autoCache = it
                    AppGraph.store.autoCacheAudio = it
                },
            )
        }
    }

    // 存储文件管理：换缓存目录 + 自定义容量上限
    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(stringResource(R.string.set_cache_loc_title))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.set_cache_loc_current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                    Text(
                        if (cacheDir.isBlank()) {
                            stringResource(R.string.set_cache_loc_default)
                        } else {
                            cacheDirLabel(cacheDir)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.75f),
                    )
                }
                TextButton(onClick = { dirPicker.launch(null) }) {
                    Text(stringResource(R.string.set_cache_loc_choose), color = textColor)
                }
            }
            if (cacheDir.isNotBlank()) {
                TextButton(
                    onClick = {
                        AudioCache.setCacheDir(context, "")
                        cacheDir = ""
                        refresh()
                    },
                    modifier = Modifier.padding(horizontal = 18.dp),
                ) { Text(stringResource(R.string.set_cache_loc_reset), color = textColor) }
            }

            GroupDivider()

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showLimitDialog = true }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.set_cache_limit_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                    Text(
                        stringResource(
                            R.string.set_cache_limit_value,
                            if (limitBytes > 0) formatSize(limitBytes)
                            else stringResource(R.string.set_cache_limit_auto),
                            formatSize(AudioCache.defaultMaxBytes()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.75f),
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

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(stringResource(R.string.set_cache_usage_title))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.set_cache_image_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                    Text(
                        stringResource(R.string.set_cache_image_size, dataCache),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.75f),
                    )
                }
                TextButton(onClick = {
                    runCatching {
                        context.cacheDir?.deleteRecursively()
                        refresh()
                    }
                }) { Text(stringResource(R.string.set_cache_clear), color = textColor) }
            }

            GroupDivider()

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.set_cache_audio_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                    Text(
                        stringResource(
                            R.string.set_cache_audio_desc,
                            "$audioCount",
                            formatSize(audioCache),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/** tree uri 里能给人看的那段路径（`primary:Music/YZLiYin` → `Music/YZLiYin`）。 */
private fun cacheDirLabel(uri: String): String =
    Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.ifBlank { uri } ?: uri

/** 容量单位：由小到大，和输入框里手写 `500mb` 时认的后缀一一对应。 */
private val CACHE_UNITS = listOf(
    "KB" to (1L shl 10),
    "MB" to (1L shl 20),
    "GB" to (1L shl 30),
    "TB" to (1L shl 40),
)

/** 从已存的字节数反推该显示成哪一档单位（给弹窗回显用）。 */
private fun unitScaleOf(bytes: Long): Long =
    CACHE_UNITS.lastOrNull { bytes >= it.second && bytes % it.second == 0L }?.second
        ?: (1L shl 10)

private fun unitNameOf(bytes: Long): String =
    CACHE_UNITS.lastOrNull { bytes >= it.second && bytes % it.second == 0L }?.first
        ?: "KB"

/**
 * 自定义缓存上限弹窗：数字随便输，单位既能点 chip 选、也能连着数字一起写（如 `500mb`），
 * 默认停在 0KB —— 0 表示不自定义、沿用默认上限。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CacheLimitDialog(
    initialBytes: Long,
    defaultLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black
    var digits by remember {
        mutableStateOf(if (initialBytes > 0) "${initialBytes / unitScaleOf(initialBytes)}" else "0")
    }
    var unit by remember {
        mutableStateOf(if (initialBytes > 0) unitNameOf(initialBytes) else "KB")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_cache_limit_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.set_cache_limit_hint, defaultLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = digits,
                    onValueChange = { raw ->
                        // 两种写法都收：只写数字（单位用下面的 chip 选），或连单位一起写
                        val text = raw.replace(" ", "").lowercase()
                        val suffix = CACHE_UNITS.firstOrNull { text.endsWith(it.first.lowercase()) }?.first
                        val number = if (suffix == null) text else text.dropLast(suffix.length)
                        digits = number.filter { it.isDigit() }.take(6)
                        if (suffix != null && text.length > number.length) unit = suffix
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text(unit) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CACHE_UNITS.forEach { (name, _) ->
                        FilterChip(
                            selected = unit == name,
                            onClick = { unit = name },
                            label = { Text(name, color = textColor) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val scale = CACHE_UNITS.first { it.first == unit }.second
                    val count = digits.toLongOrNull() ?: 0L
                    // 溢出按「无穷大」处理，总好过算成负数让淘汰逻辑反着来
                    val bytes = runCatching { Math.multiplyExact(count, scale) }
                        .getOrDefault(Long.MAX_VALUE)
                    onConfirm(bytes.coerceAtLeast(0L))
                },
            ) { Text(stringResource(R.string.set_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_cancel)) }
        },
    )
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
                Text(
                    stringResource(R.string.set_lt_room),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                )
                Text(
                    if (!ltSession.roomId.isNullOrBlank()) {
                        stringResource(
                            R.string.set_lt_room_info,
                            ltSession.roomId.orEmpty(),
                            if (ltSession.role == "controller") {
                                stringResource(R.string.set_lt_role_host)
                            } else {
                                stringResource(R.string.set_lt_role_listener)
                            },
                        )
                    } else {
                        stringResource(R.string.set_sec_listen_together_desc)
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

// ==================== Hook（Xposed 拓展） ====================

/**
 * Hook 节：把 Xposed 拓展的开关集中放这里。摆法参考 HyperIsland 的
 * 「设置 → Hook拓展 → 系统设置」：上面一张「系统设置」卡
 * （入口开关 / 归入同一栏目 / 入口位置 / 图标样式），下面一张「Xposed」卡
 * （模块作用域的推荐应用）。
 */
@Composable
private fun HookSection() {
    val store = AppGraph.store
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black

    var settingsEntry by remember { mutableStateOf(store.hookSettingsEntry) }
    var sameGroup by remember { mutableStateOf(store.hookSettingsSameGroup) }
    var position by remember { mutableStateOf(store.hookSettingsPosition) }
    var iconStyle by remember { mutableStateOf(store.hookSettingsIconStyle) }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(
                stringResource(R.string.set_hook_system_settings_title),
                stringResource(R.string.set_hook_system_settings_desc),
            )
            SwitchRow(
                title = R.string.set_hook_settings_entry,
                desc = R.string.set_hook_settings_entry_summary,
                checked = settingsEntry,
                textColor = textColor,
            ) {
                settingsEntry = it
                store.hookSettingsEntry = it
            }
            if (settingsEntry) {
                SwitchRow(
                    title = R.string.set_hook_settings_entry_same_group,
                    desc = R.string.set_hook_settings_entry_same_group_summary,
                    checked = sameGroup,
                    textColor = textColor,
                ) {
                    sameGroup = it
                    store.hookSettingsSameGroup = it
                }
                HookChipGroup(
                    title = R.string.set_hook_settings_entry_position,
                    options = listOf(
                        ChipOption(SessionStore.HOOK_POSITION_TOP, R.string.set_hook_position_top),
                        ChipOption(SessionStore.HOOK_POSITION_MIDDLE, R.string.set_hook_position_middle),
                        ChipOption(SessionStore.HOOK_POSITION_BOTTOM, R.string.set_hook_position_bottom),
                    ),
                    selected = position,
                    textColor = textColor,
                ) {
                    position = it
                    store.hookSettingsPosition = it
                }
                HookChipGroup(
                    title = R.string.set_hook_icon_style,
                    options = listOf(
                        ChipOption(SessionStore.HOOK_ICON_DEFAULT, R.string.set_hook_icon_default),
                        ChipOption(SessionStore.HOOK_ICON_OUTLINE, R.string.set_hook_icon_outline),
                    ),
                    selected = iconStyle,
                    textColor = textColor,
                ) {
                    iconStyle = it
                    store.hookSettingsIconStyle = it
                }
            }
        }
    }

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            SectionIntro(
                stringResource(R.string.set_hook_xposed_title),
                stringResource(R.string.set_hook_xposed_desc),
            )
            HookAppRow(
                packageName = PKG_SETTINGS,
                fallbackTitle = R.string.set_hook_settings_app_name,
                fallbackIcon = Icons.Outlined.Extension,
                summary = stringResource(R.string.set_hook_recommended_app, PKG_SETTINGS),
                textColor = textColor,
            )
        }
    }
}

/** Hook 节里的单选 chip 组：标题 + 一排 FilterChip（id 落盘、资源 id 上屏）。 */
@Composable
private fun HookChipGroup(
    @StringRes title: Int,
    options: List<ChipOption<Int>>,
    selected: String,
    textColor: Color,
    onSelect: (String) -> Unit,
) {
    Text(
        stringResource(title),
        Modifier.padding(start = 18.dp, top = 8.dp, end = 18.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = textColor,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            FilterChip(
                selected = selected == o.id,
                onClick = { if (selected != o.id) onSelect(o.id) },
                label = { Text(stringResource(o.label), color = textColor) },
            )
        }
    }
}

/**
 * Xposed 作用域应用行：装了就显示应用图标 + 应用名，没装就退回自带图标 + 应用名。
 * 图标异步加载（走 IO），摆法参考 HyperIsland 的 HookAppRow。
 */
@Composable
private fun HookAppRow(
    packageName: String,
    @StringRes fallbackTitle: Int,
    fallbackIcon: ImageVector,
    summary: String,
    textColor: Color,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                drawableToBitmap(context.packageManager.getApplicationIcon(packageName), APP_ICON_SIZE)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val appLabel = remember(packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.width(42.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = icon
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            } else {
                Icon(
                    fallbackIcon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                )
            }
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                appLabel ?: stringResource(fallbackTitle),
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Drawable → Bitmap，画应用图标用；统一裁成 [size] 见方。 */
private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap
}

/** Hook 节里 Xposed 作用域的推荐应用包名：系统设置。 */
private const val PKG_SETTINGS = "com.android.settings"

/** 应用图标的统一分辨率。 */
private const val APP_ICON_SIZE = 144

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val textColor = if (AppTheme.isDark()) Color.White else Color.Black

    // 版本更新内容：每次进「关于」都自动弹一次，不给「不再提示」
    var showChangelog by remember { mutableStateOf(true) }
    if (showChangelog) ChangelogDialog(onDismiss = { showChangelog = false })

    // 版本号末尾的 beta 之类标记单独排成斜体，正文部分保持常规字重
    val (versionBase, versionTag) = splitVersionTag(BuildConfig.VERSION_NAME)

    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            InfoRow(
                R.string.set_about_app,
                "音之理音 $versionBase",
                textColor,
                italicTail = versionTag,
            )
            InfoRow(R.string.set_about_package, "top.aryun.yzliyin", textColor)
            InfoRow(
                R.string.set_about_sources_label,
                stringResource(R.string.set_about_sources),
                textColor,
            )
            InfoRow(R.string.set_about_stack, "Jetpack Compose · Material 3 · Media3", textColor)
            InfoRow(
                R.string.set_about_license,
                stringResource(R.string.set_about_license_value),
                textColor,
            )
            GroupDivider()
            // 自动弹过一次之后，这里随时能再看一遍这次改了什么
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showChangelog = true }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.set_changelog_title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f),
                )
            }
        }
    }

    // 三张外链卡片：点击交给系统浏览器打开
    SettingsGroupCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            LinkCard(
                icon = Icons.Outlined.Language,
                title = stringResource(R.string.set_about_site),
                desc = "aryun.top",
                url = SITE_URL,
                context = context,
            )
            GroupDivider()
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

/** 版本号末尾的渠道标记（beta 之类）：`2.1.01022beta` → (`2.1.01022`, `beta`)。 */
private fun splitVersionTag(version: String): Pair<String, String?> {
    val at = version.indexOfFirst { it.isLetter() }
    return if (at > 0) version.substring(0, at) to version.substring(at) else version to null
}

/** 版本更新内容弹窗：进「关于」就弹一次，让每次更新的内容都能被看到。 */
@Composable
private fun ChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_changelog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.set_changelog_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.set_changelog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_changelog_ok)) }
        },
    )
}

/** 关于页外链卡片的目标地址：官方站点 / GitHub 主页 / B 站视频。 */
private const val SITE_URL = "https://aryun.top/"
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
    if (!ok) Toast.makeText(context, context.getString(R.string.set_no_browser_app), Toast.LENGTH_SHORT).show()
}

// ==================== 通用小部件 ====================

@Composable
private fun SwitchRow(
    @StringRes title: Int,
    @StringRes desc: Int,
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
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium, color = textColor)
            Text(
                stringResource(desc),
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
    @StringRes title: Int,
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
            stringResource(title),
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
                    label = { Text(stringResource(q.label), color = textColor) },
                )
            }
        }
    }
}

/** 倍数 → 百分比标签，如 1f → "100%"。 */
@Composable
private fun scalePercent(scale: Float): String =
    stringResource(R.string.set_percent, "${(scale * 100).toInt()}")

/**
 * 刷新间隔秒数 → 展示文案：999 这种取整，1 秒以上保留一位小数，1 秒以下保留两位（0.01 秒）。
 */
@Composable
private fun secLabel(sec: Float): String = when {
    sec >= 100f -> stringResource(R.string.set_unit_seconds, "${sec.toInt()}")
    sec >= 1f -> {
        val v = (sec * 10).toInt() / 10f
        if (v % 1f == 0f) {
            stringResource(R.string.set_unit_seconds, "${v.toInt()}")
        } else {
            stringResource(R.string.set_unit_seconds, "%.1f".format(v))
        }
    }
    else -> stringResource(R.string.set_unit_seconds, "%.2f".format(sec))
}

/**
 * 秒数 ↔ 滑条位置（0..1）。0.01 ~ 999 跨了近 5 个数量级，用对数映射，
 * 否则滑条绝大部分区间都挤在几百秒那头，够不着 0.01 秒。
 */
private fun secToSliderPos(sec: Float): Float {
    val min = SessionStore.NET_REFRESH_MIN
    val max = SessionStore.NET_REFRESH_MAX
    return (ln(sec.coerceIn(min, max) / min) / ln(max / min)).coerceIn(0f, 1f)
}

private fun sliderPosToSec(pos: Float): Float {
    val min = SessionStore.NET_REFRESH_MIN
    val max = SessionStore.NET_REFRESH_MAX
    return (min * (max / min).pow(pos.coerceIn(0f, 1f))).coerceIn(min, max)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

@Composable
private fun InfoRow(
    @StringRes k: Int,
    v: String,
    color: Color,
    italicTail: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Text(
            stringResource(k),
            Modifier.width(76.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color.copy(alpha = 0.75f),
        )
        Text(
            buildAnnotatedString {
                append(v)
                if (!italicTail.isNullOrEmpty()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italicTail) }
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
