package top.aryun.yzliyin.listentogether

/**
 * 把会话/房间状态里的机器串（roomNotice / lastError）映射成面向用户的中文文案。
 *
 * 上游用 R.string/plurals 实现同样的映射；音之理音不做多语言，文案集中在 [LtMessages]。
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
            "房主已离线，房间将在约 $minutes 分钟后自动关闭"
        }

        normalized.startsWith("member_joined:") ->
            "${normalized.substringAfter(':')} 加入了房间"

        normalized.startsWith("member_left:") ->
            "${normalized.substringAfter(':')} 离开了房间"

        normalized == "controller_reconnected" ->
            LtMessages.listen_together_notice_controller_reconnected

        normalized.equals("controller_left", ignoreCase = true) ->
            LtMessages.listen_together_notice_controller_left

        normalized == "controller_timeout" || normalized == "room_closed" || "room closed" in lowered ->
            LtMessages.listen_together_notice_room_closed

        "unauthorized" in lowered || "http=401" in lowered || "(401)" in lowered ->
            "房间鉴权失败，请让房主重新邀请"

        "room not initialized" in lowered || "not found in do" in lowered ->
            "房间不存在或已结束"

        "controller offline" in lowered ->
            LtMessages.listen_together_error_controller_offline

        "member control disabled" in lowered ->
            LtMessages.listen_together_error_member_control_disabled

        normalized == LtMessages.listen_together_error_reconnecting ||
            ("listen together" in lowered && "reconnect" in lowered) ->
            LtMessages.listen_together_error_reconnecting

        normalized == LtMessages.listen_together_error_rejoining ||
            ("rejoin" in lowered && "room" in lowered) ->
            LtMessages.listen_together_error_rejoining

        else -> normalized
    }
}
