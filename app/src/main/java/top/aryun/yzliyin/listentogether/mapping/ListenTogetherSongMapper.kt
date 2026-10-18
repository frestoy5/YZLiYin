package top.aryun.yzliyin.listentogether.mapping

import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherTrack

/**
 * 本机曲目 → 协议曲目。
 *
 * 只承载身份与展示字段；可播放直链由 [withStreamUrls] 在发布/上抛路径上按需注入。
 */
fun Song.toListenTogetherTrackOrNull(): ListenTogetherTrack? {
    val channel = resolvedChannelId() ?: return null
    val audio = resolvedAudioId() ?: return null
    val subAudio = resolvedSubAudioId()
    return ListenTogetherTrack(
        stableKey = buildStableTrackKey(channel, audio, subAudio),
        channelId = channel,
        audioId = audio,
        subAudioId = subAudio,
        playlistContextId = null,
        mediaUri = null,
        streamUrl = null,
        streamUrls = emptyList(),
        name = name,
        artist = artist,
        album = albumName.takeIf { it.isNotBlank() },
        durationMs = durationMs,
        coverUrl = cover.takeIf { it.isNotBlank() }
    )
}
