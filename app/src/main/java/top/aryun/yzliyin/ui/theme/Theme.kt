package top.aryun.yzliyin.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import top.aryun.yzliyin.core.AppGraph

/**
 * 外观与尺寸：跟随系统 / 强制白天 / 强制黑夜，以及界面大小、字体大小倍数。
 *
 * 设置页写入后立即生效并持久化到 [AppGraph.store]，主题色板、窗口底色、
 * 状态栏图标与根 Density 统一从这里取值，避免各处各自判断导致不一致。
 */
object AppTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    /** 当前模式（Compose 可观察）。AppGraph 在 Application.onCreate 中先于界面初始化。 */
    var mode: String by mutableStateOf(AppGraph.store.themeMode)
        private set

    /**
     * 界面大小倍数（dp 尺寸整体缩放），持久化到 [AppGraph.store]。
     * 改动后整棵 Compose 树按新 Density 重排，无需重启。
     */
    var uiScale: Float by mutableStateOf(AppGraph.store.uiScale)
        private set

    /** 字体大小倍数（sp → px 的换算缩放），只影响字号。 */
    var fontScale: Float by mutableStateOf(AppGraph.store.fontScale)
        private set

    fun applyMode(value: String) {
        mode = value
        AppGraph.store.themeMode = value
    }

    fun applyUiScale(value: Float) {
        uiScale = value
        AppGraph.store.uiScale = value
    }

    fun applyFontScale(value: Float) {
        fontScale = value
        AppGraph.store.fontScale = value
    }

    /** 是否为深色：手动选择优先，跟随系统时取手机的深色开关。 */
    @Composable
    fun isDark(): Boolean = when (mode) {
        LIGHT -> false
        DARK -> true
        else -> isSystemInDarkTheme()
    }
}

private val DarkColors = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlue.copy(alpha = 0.18f),
    onPrimaryContainer = BrandBlueLight,
    secondary = BrandViolet,
    onSecondary = Color.White,
    secondaryContainer = BrandViolet.copy(alpha = 0.18f),
    onSecondaryContainer = Color(0xFFCFC4FF),
    tertiary = BrandAqua,
    onTertiary = Color(0xFF04231F),
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnBackground,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightOnSurfaceVariant,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    outline = NightOutline,
    outlineVariant = NightOutline,
    error = BrandRose,
    onError = Color.White,
)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandBlue.copy(alpha = 0.12f),
    onPrimaryContainer = Color(0xFF0B2B7A),
    secondary = BrandViolet,
    onSecondary = Color.White,
    secondaryContainer = BrandViolet.copy(alpha = 0.12f),
    onSecondaryContainer = Color(0xFF2A1B6B),
    tertiary = BrandAqua,
    background = DayBackground,
    onBackground = DayOnBackground,
    surface = DaySurface,
    onSurface = DayOnBackground,
    surfaceVariant = DaySurfaceHigh,
    onSurfaceVariant = DayOnSurfaceVariant,
    surfaceContainer = DaySurface,
    surfaceContainerHigh = DaySurfaceHigh,
    outline = DayOutline,
    outlineVariant = DayOutline,
    error = BrandRose,
    onError = Color.White,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun YZLiYinTheme(
    darkTheme: Boolean = AppTheme.isDark(),
    content: @Composable () -> Unit,
) {
    // Material You：Android 12+ 跟随壁纸动态取色，低版本回退到标准 M3 色板
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    // 设置 → 主题：界面大小改 dp 换算（所有控件一起放大缩小），
    // 字体大小改 sp 换算（在系统字号的基础上再乘一个倍数，只动文字）。
    // 两项都在根上换 Density，各页面无需感知，改完立即重排。
    val baseDensity = LocalDensity.current
    val appDensity = Density(
        density = baseDensity.density * AppTheme.uiScale,
        fontScale = baseDensity.fontScale * AppTheme.fontScale,
    )

    // 状态栏/导航栏图标与窗口底色跟随主题。
    // manifest 声明了 uiMode 配置变更由 Activity 自行处理（不重建），XML 主题资源不会重新解析，
    // 因此这里必须按当前色板同步，否则关掉系统深色后窗口底色与图标仍停留在深色。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.setBackgroundDrawable(ColorDrawable(colorScheme.background.toArgb()))
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalDensity provides appDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
