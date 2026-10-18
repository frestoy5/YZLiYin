package top.aryun.yzliyin.listentogether.session

import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState

internal fun shouldApplyListenTogetherClosedRoomPause(
    state: ListenTogetherRoomState
): Boolean {
    return state.playback.state == "paused"
}
