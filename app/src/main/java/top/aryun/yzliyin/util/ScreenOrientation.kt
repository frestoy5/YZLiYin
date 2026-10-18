package top.aryun.yzliyin.util

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * 屏幕方向：设置 → 主题 里的「竖屏 / 横屏 / 自动」。
 *
 * auto 用 [ActivityInfo.SCREEN_ORIENTATION_SENSOR] 直接跟随方向传感器
 * （不管系统有没有打开自动旋转，也就是「自动获取手机的方向」）；
 * portrait / landscape 强制锁定。改完立即生效，Activity 带了
 * `configChanges="orientation|screenSize"`，旋转不重建页面、播放不中断。
 */
fun applyScreenOrientation(activity: Activity, mode: String) {
    activity.requestedOrientation = when (mode) {
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }
}
