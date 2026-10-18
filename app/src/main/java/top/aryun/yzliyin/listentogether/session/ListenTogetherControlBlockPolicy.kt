package top.aryun.yzliyin.listentogether.session

import top.aryun.yzliyin.listentogether.LtMessages
import top.aryun.yzliyin.listentogether.control.controlledPlaybackCommandTypes
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomStatuses

internal fun resolveListenTogetherControlBlockReason(
    sessionRole: String?,
    roomState: ListenTogetherRoomState?,
    commandType: String
): String? {
    if (
        roomState?.roomStatus == ListenTogetherRoomStatuses.CONTROLLER_OFFLINE &&
        sessionRole != "controller"
    ) {
        return if (roomState.settings.normalized().shareAudioLinks) {
            LtMessages.listen_together_error_controller_offline_link
        } else {
            LtMessages.listen_together_error_controller_offline
        }
    }
    if (
        sessionRole == "listener" &&
        roomState?.settings.normalized().allowMemberControl == false &&
        commandType in controlledPlaybackCommandTypes
    ) {
        return LtMessages.listen_together_error_member_control_disabled
    }
    return null
}

/**
 * 控制端(权威方)对转发来的成员控制请求做服务端侧鉴权
 * 关闭 allowMemberControl 后, 只有控制端自身发起的转发请求可通过
 * 防止改造的监听端或旧自建 Worker 绕过本机 UI 校验越权控制房间
 */
internal fun shouldRejectForwardedListenTogetherMemberControl(
    requesterUuid: String?,
    controllerUserUuid: String?,
    allowMemberControl: Boolean
): Boolean {
    if (allowMemberControl) return false
    val isRequesterController = !requesterUuid.isNullOrBlank() &&
        requesterUuid == controllerUserUuid
    return !isRequesterController
}
