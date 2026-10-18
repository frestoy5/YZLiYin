package top.aryun.yzliyin.listentogether.mapping

import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherChannels

/**
 * 稳定曲目键。格式与 NeriPlayer 完全一致：
 *  - netease:  `netease:<audioId>`
 *  - bilibili: `bilibili:<audioId>:<subAudioId>`（audioId=bvid, subAudioId=cid）
 *  - youtubeMusic / 其它: `channel:audio[:sub]`
 *
 * 网易云与哔哩哔哩的 audioId/subAudioId 取值与 NeriPlayer 相同，
 * 因此同房间内不同客户端对同一首歌会得到同一个 stableKey。
 */
fun buildStableTrackKey(
    channelId: String,
    audioId: String,
    subAudioId: String? = null,
    playlistContextId: String? = null
): String {
    return when (channelId) {
        ListenTogetherChannels.BILIBILI -> {
            listOf(channelId, audioId, subAudioId).filterNot { it.isNullOrBlank() }.joinToString(":")
        }

        ListenTogetherChannels.YOUTUBE_MUSIC -> {
            listOf(channelId, audioId, playlistContextId).filterNot { it.isNullOrBlank() }.joinToString(":")
        }

        else -> "$channelId:$audioId"
    }
}

/** 本机曲目所属渠道。 */
fun Song.resolvedChannelId(): String? {
    return when (source) {
        Source.NETEASE -> ListenTogetherChannels.NETEASE
        Source.KUGOU -> ListenTogetherChannels.KUGOU
        Source.BILIBILI -> ListenTogetherChannels.BILIBILI
    }
}

/**
 * 渠道内的曲目身份 / 取链键。
 *  - 酷狗：`playId`（FileHash，取链用）优先，缺失时退回 `id`
 *  - 哔哩哔哩：`id`/`playId` 都是 bvid
 *  - 网易云：数字 id
 */
fun Song.resolvedAudioId(): String? {
    return when (source) {
        Source.KUGOU -> playId.ifEmpty { id }.takeIf { it.isNotBlank() }
        Source.BILIBILI -> playId.ifEmpty { id }.takeIf { it.isNotBlank() }
        Source.NETEASE -> id.takeIf { it.isNotBlank() }
    }
}

/** 哔哩哔哩的 cid / 酷狗的 album_audio_id；其余渠道没有子 id。 */
fun Song.resolvedSubAudioId(): String? {
    return when (source) {
        Source.BILIBILI -> playSubId.takeIf { it.isNotBlank() }
        Source.KUGOU -> playSubId.takeIf { it.isNotBlank() }
        Source.NETEASE -> null
    }
}

/** 音之理音没有 YouTube 渠道，播放列表上下文恒为空（保留函数以对齐上游调用面）。 */
fun Song.resolvedPlaylistContextId(): String? = null
