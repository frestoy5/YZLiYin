package top.aryun.yzliyin.player

import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi

/**
 * 把曲目解析成可直接播放的 URL。返回空串表示不可播放，
 * 此时由 [unavailableReason] 给出面向用户的原因。
 */
object PlaybackResolver {

    suspend fun url(song: Song): String = when (song.source) {
        Source.NETEASE -> NeteaseApi.songUrl(song.id)
        Source.KUGOU -> KugouApi.songUrl(
            hash = song.playId.ifEmpty { song.id },
            albumAudioId = song.playSubId,
        )
        Source.BILIBILI -> BiliApi.audioUrl(
            bvid = song.playId.ifEmpty { song.id },
            cid = song.playSubId,
        )
    }

    fun unavailableReason(song: Song): String = when (song.source) {
        Source.NETEASE ->
            if (song.vip) "该歌曲需要网易云会员，当前账号无权限"
            else "获取播放链接失败，请稍后重试"
        Source.KUGOU -> "获取酷狗播放链接失败（可能无版权或需要会员）"
        Source.BILIBILI -> "获取 B 站音频流失败（可能是充电专属或地区限制）"
    }
}
