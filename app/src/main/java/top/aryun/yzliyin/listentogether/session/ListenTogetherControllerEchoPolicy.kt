package top.aryun.yzliyin.listentogether.session

import top.aryun.yzliyin.listentogether.protocol.ListenTogetherCause
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState

internal fun shouldDropListenTogetherControllerLocalEcho(
    state: ListenTogetherRoomState,
    cause: ListenTogetherCause?,
    latestVersion: Long,
    currentUserId: String?,
    lastControllerLocalControlAtElapsedMs: Long,
    nowElapsedMs: Long,
    controllerLocalControlCooldownMs: Long
): Boolean {
    if (cause?.type == "TRACK_FINISHED") return false
    return currentUserId == (state.controllerUserUuid ?: state.controllerUserId) &&
        cause?.userUuid == currentUserId &&
        nowElapsedMs - lastControllerLocalControlAtElapsedMs < controllerLocalControlCooldownMs &&
        state.version <= latestVersion + 1
}
