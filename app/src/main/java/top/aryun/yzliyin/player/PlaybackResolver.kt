package top.aryun.yzliyin.player

import top.aryun.yzliyin.core.model.ResolveResult
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi

/**
 * 把曲目解析成可直接播放的 URL。
 * 失败时 [ResolveResult.url] 为空，[ResolveResult.reason] 给出面向用户的原因。
 */
object PlaybackResolver {

    suspend fun resolve(song: Song): ResolveResult = when (song.source) {
        Source.NETEASE -> {
            val url = NeteaseApi.songUrl(song.id)
            ResolveResult(
                url = url,
                reason = if (song.vip) "该歌曲需要网易云会员，当前账号无权限"
                else "获取播放链接失败，请稍后重试",
            )
        }
        Source.KUGOU -> KugouApi.songUrl(
            hash = song.playId.ifEmpty { song.id },
            albumAudioId = song.playSubId,
        )
        Source.BILIBILI -> ResolveResult(
            url = BiliApi.audioUrl(
                bvid = song.playId.ifEmpty { song.id },
                cid = song.playSubId,
            ),
            reason = "获取 B 站音频流失败（可能是充电专属、地区限制或需要登录）",
        )
    }
}
