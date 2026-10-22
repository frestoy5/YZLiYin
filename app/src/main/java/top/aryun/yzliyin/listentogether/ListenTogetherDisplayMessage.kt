package top.aryun.yzliyin.listentogether

import top.aryun.yzliyin.R
import top.aryun.yzliyin.util.AppLocale

/**
 * 把会话/房间状态里的机器串（roomNotice / lastError）映射成面向用户的文案（随界面语言切换）。
 *
 * ⚠ **左侧的匹配必须继续用中文**：服务端与会话会把 [LtMessages] 的常量原样回传，
 * `normalized == LtMessages...` 靠的就是那份字节，改成资源会直接让匹配失效。
 * 所以本函数只把「匹配上之后显示什么」换成资源，匹配条件一律不动。
 * 输出只流向 ListenTogether 房间页的 `Text()`、不回写会话状态，因此本地化不会反噬匹配。
 */
fun listenTogetherDisplayMessage(raw: String?): String? {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isEmpty()) return null
    val lowered = normalized.lowercase()
    return when {
        normalized.startsWith("controller_offline:") -> {
            val minutes = normalized.substringAfter(':')
                .toLongOrNull()
                ?.coerceAtLeast(0L)
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: 10
            AppLocale.string(R.string.ltm_display_host_offline, minutes)
        }

        normalized.startsWith("member_joined:") ->
            AppLocale.string(R.string.ltm_display_member_joined, normalized.substringAfter(':'))

        normalized.startsWith("member_left:") ->
            AppLocale.string(R.string.ltm_display_member_left, normalized.substringAfter(':'))

        normalized == "controller_reconnected" ->
            AppLocale.string(R.string.ltm_notice_controller_reconnected)

        normalized.equals("controller_left", ignoreCase = true) ->
            AppLocale.string(R.string.ltm_notice_controller_left)

        normalized == "controller_timeout" || normalized == "room_closed" || "room closed" in lowered ->
            AppLocale.string(R.string.ltm_notice_room_closed)

        "unauthorized" in lowered || "http=401" in lowered || "(401)" in lowered ->
            AppLocale.string(R.string.ltm_display_auth_failed)

        "room not initialized" in lowered || "not found in do" in lowered ->
            AppLocale.string(R.string.ltm_display_room_not_found)

        "controller offline" in lowered ->
            AppLocale.string(R.string.ltm_err_controller_offline)

        "member control disabled" in lowered ->
            AppLocale.string(R.string.ltm_err_member_control_disabled)

        // ⚠ 左侧拿中文常量做 == 是**故意**的：会话把它们原样回传，字节必须对得上；
        //   只有右侧「匹配上之后显示什么」换成资源。
        normalized == LtMessages.listen_together_error_reconnecting ||
            ("listen together" in lowered && "reconnect" in lowered) ->
            AppLocale.string(R.string.ltm_err_reconnecting)

        normalized == LtMessages.listen_together_error_rejoining ||
            ("rejoin" in lowered && "room" in lowered) ->
            AppLocale.string(R.string.ltm_err_rejoining)

        else -> normalized
    }
}
