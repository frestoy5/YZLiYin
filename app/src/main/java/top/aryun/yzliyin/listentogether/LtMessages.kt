package top.aryun.yzliyin.listentogether

/**
 * 一起听模块的**协议/状态用中文串**。
 *
 * ⚠ 这些常量刻意保持中文，**不要**改成字符串资源：
 *  1. `listen_together_error_reconnecting` / `_rejoining` 会被写进会话状态（`lastError`）
 *     并**原样回传**，`listenTogetherDisplayMessage` 靠 `normalized == LtMessages...` 匹配，
 *     换成资源会让匹配直接失效；
 *  2. `ListenTogetherControlBlockPolicy` 也把它们当错误值返回，同样参与比较。
 *
 * 面向用户的**展示**由 `listenTogetherDisplayMessage` 映射到 `R.string`（`ltm_*`）。
 * 命名与 NeriPlayer 的 string 资源 key 保持一致，便于与服务端/上游代码对照。
 * 含 %1$d 等占位符的常量由
 * [top.aryun.yzliyin.listentogether.validation.ListenTogetherValidationError] 格式化。
 *
 * 其中**校验类**常量（`join_secret_*` / `nickname_*` / `room_id_*` / `user_uuid_*` /
 * `current_track_*`）不参与任何比较或回传，仅作为
 * [top.aryun.yzliyin.listentogether.validation.ListenTogetherValidationError.messageFormat]
 * 的中文兜底；展示优先取对应的 `ltm_validate_*` 字符串资源（随界面语言中/日/英）。
 */
object LtMessages {
    const val listen_together_error_controller_offline = "房主当前离线，请等待重连后再试"
    const val listen_together_error_controller_offline_link = "房主已离线，无法获取播放链接"
    const val listen_together_error_member_control_disabled = "房主已关闭听众操作权限"
    const val listen_together_error_current_track_missing = "当前没有正在播放的歌曲，无法创建一起听"
    const val listen_together_error_current_track_not_shareable =
        "无法分享的歌曲不能创建一起听，请切换到在线歌曲"
    const val listen_together_error_current_track_unavailable = "当前歌曲不在播放队列中，无法创建一起听"
    const val listen_together_error_join_secret_required = "邀请密钥不能为空"
    const val listen_together_error_join_secret_length = "邀请密钥不能超过 %1\$d 个字符"
    const val listen_together_error_nickname_length = "当前昵称长度需要为 %1\$d-%2\$d 位"
    const val listen_together_error_nickname_chars = "当前昵称仅支持汉字、英文字母和数字"
    const val listen_together_error_room_id_length = "房间 ID 需要为 %1\$d 位"
    const val listen_together_error_room_id_chars = "房间 ID 仅支持大写字母和数字"
    const val listen_together_error_user_uuid_required = "用户 UUID 不能为空"
    const val listen_together_error_user_uuid_invalid = "用户 UUID 格式无效"
    const val listen_together_error_reconnecting = "一起听连接暂时中断，正在重连"
    const val listen_together_error_rejoining = "一起听连接已失效，正在重新加入房间"

    /** 房间公告/关闭原因展示（机器串由 listenTogetherDisplayMessage 映射后使用）。 */
    const val listen_together_notice_controller_left = "房主已离开，房间已结束"
    const val listen_together_notice_room_closed = "房主长时间未返回，房间已自动关闭"
    const val listen_together_notice_controller_reconnected = "房主已重连，同步已恢复"
}
