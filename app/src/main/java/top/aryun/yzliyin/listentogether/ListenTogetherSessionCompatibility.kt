package top.aryun.yzliyin.listentogether

import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState
import top.aryun.yzliyin.listentogether.session.resolveListenTogetherJoinAutoPauseCause as resolveSessionJoinAutoPauseCause

internal fun resolveListenTogetherJoinAutoPauseCause(
    autoPauseOnJoin: Boolean,
    role: String?,
    state: ListenTogetherRoomState
): String? {
    return resolveSessionJoinAutoPauseCause(
        autoPauseOnJoin = autoPauseOnJoin,
        role = role,
        state = state
    )
}
