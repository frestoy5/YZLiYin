package top.aryun.yzliyin.listentogether.session

import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState

internal fun latestListenTogetherAcceptedRoomVersion(
    lastAppliedRoomVersion: Long,
    currentState: ListenTogetherRoomState?
): Long {
    return maxOf(lastAppliedRoomVersion, currentState?.version ?: -1L)
}
