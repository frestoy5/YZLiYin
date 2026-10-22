package top.aryun.yzliyin.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import top.aryun.yzliyin.ui.theme.AppTheme
import kotlin.math.roundToInt

/**
 * 当前窗口宽度（dp，**未**套「界面大小」缩放，与直接读
 * `LocalConfiguration.screenWidthDp` 的口径一致）。
 *
 * 用 View 布局测量而不是 Configuration，是因为宽屏判定（左侧导航、播放页两栏）
 * 依赖的是**真实窗口宽度**，而 Configuration 有一条容易断的刷新链路：
 * 语言重包（`createConfigurationContext`）若把尺寸字段带成 override，旋转后
 * `screenWidthDp` 就永久冻在启动那一刻——手机上宽度本来就小看不出来，
 * 平板会直接停在启动时的形态，宽屏样式再也回不来。
 *
 * 与之相对，旋转 / 分屏一定会触发 View 按新窗口重新布局，
 * [android.view.View.addOnLayoutChangeListener] 拿到的永远是当下真实宽度；
 * 根 Density 已乘过界面大小，换算回未缩放 dp 后口径与旧读法完全一致，
 * 调用方原有的 `/ uiScale` 逻辑无需改动。
 *
 * 首帧布局前 `view.width` 还是 0，先用 Configuration 值兜底——那一帧之后
 * 监听器就会给出真实值；兜底值取自当下配置，与旧行为等价，不更糟。
 */
@Composable
fun rememberWindowWidthDp(): Int {
    val view = LocalView.current
    val density = LocalDensity.current.density
    val fallbackPx = (LocalConfiguration.current.screenWidthDp * density).roundToInt()
    var widthPx by remember(view) { mutableStateOf(view.width.takeIf { it > 0 } ?: fallbackPx) }

    DisposableEffect(view) {
        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0 && v.width != widthPx) widthPx = v.width
        }
        view.addOnLayoutChangeListener(listener)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }

    return (widthPx / density * AppTheme.uiScale).roundToInt()
}
