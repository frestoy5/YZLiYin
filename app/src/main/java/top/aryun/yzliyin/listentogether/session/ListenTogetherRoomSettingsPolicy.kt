package top.aryun.yzliyin.listentogether.session

import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomSettings

internal fun ListenTogetherRoomSettings?.normalized(): ListenTogetherRoomSettings {
    return this ?: ListenTogetherRoomSettings()
}
