package top.aryun.yzliyin.youtube.auth.web

import top.aryun.yzliyin.youtube.auth.YouTubeAuthBundle
import top.aryun.yzliyin.youtube.auth.YouTubeAuthState
import top.aryun.yzliyin.youtube.auth.evaluateYouTubeAuthHealth

private const val SYNTHETIC_SAVED_AT = 1L
private const val SYNTHETIC_CHECKED_AT = 2L

// 浏览器登录成功页并不稳定，这里统一改为基于真正可用的鉴权信号判定
internal fun shouldAutoCompleteYouTubeWebLogin(
    currentAuth: YouTubeAuthBundle,
    pageConfirmedSession: Boolean,
): Boolean {
    val normalizedCurrent = currentAuth.normalized(
        savedAt = currentAuth.savedAt.takeIf { it > 0L } ?: SYNTHETIC_SAVED_AT,
    )
    if (!normalizedCurrent.isUsable()) {
        return false
    }

    val currentHealth = evaluateYouTubeAuthHealth(
        bundle = normalizedCurrent,
        now = SYNTHETIC_CHECKED_AT,
    )
    if (currentHealth.state == YouTubeAuthState.Missing || currentHealth.activeCookieKeys.isEmpty()) {
        return false
    }

    return pageConfirmedSession
}
