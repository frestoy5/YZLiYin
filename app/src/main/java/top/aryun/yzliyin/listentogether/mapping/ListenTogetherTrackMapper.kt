package top.aryun.yzliyin.listentogether.mapping

import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherChannels
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherTrack

/**
 * 协议曲目 → 本机曲目。
 *
 * 未知渠道一律按网易云处理（audioId 即数字 id），与上游保持一致；
 * 即便无法本地取链，只要房间共享了直链仍可正常收听。
 */
fun ListenTogetherTrack.toSong(): Song {
    return when (channelId.lowercase()) {
        ListenTogetherChannels.KUGOU -> Song(
            id = audioId,
            name = name,
            artist = artist,
            albumName = album.orEmpty(),
            durationMs = durationMs,
            cover = coverUrl.orEmpty(),
            source = Source.KUGOU,
            playId = audioId,
            playSubId = subAudioId.orEmpty()
        )

        ListenTogetherChannels.BILIBILI -> Song(
            id = audioId,
            name = name,
            artist = artist,
            albumName = album.orEmpty(),
            durationMs = durationMs,
            cover = coverUrl.orEmpty(),
            source = Source.BILIBILI,
            playId = audioId,
            playSubId = subAudioId.orEmpty()
        )

        else -> Song(
            id = audioId,
            name = name,
            artist = artist,
            albumName = album.orEmpty(),
            durationMs = durationMs,
            cover = coverUrl.orEmpty(),
            source = Source.NETEASE
        )
    }
}

fun ListenTogetherTrack.withStreamUrl(streamUrl: String?): ListenTogetherTrack {
    return withStreamUrls(listOfNotNull(streamUrl))
}

fun ListenTogetherTrack.withStreamUrls(streamUrls: List<String>?): ListenTogetherTrack {
    val trustedStreamUrls = trustedListenTogetherStreamUrls(
        channelId = channelId,
        streamUrls = streamUrls,
        legacyStreamUrl = null
    )
    val primaryStreamUrl = trustedStreamUrls.firstOrNull()
    if (primaryStreamUrl == streamUrl && trustedStreamUrls == this.streamUrls) return this
    return copy(
        streamUrl = primaryStreamUrl,
        streamUrls = trustedStreamUrls
    )
}
