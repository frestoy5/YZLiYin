package top.aryun.yzliyin.player

import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.ResolveResult
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi

/**
 * 把曲目解析成可直接播放的 URL。
 * 失败时 [ResolveResult.url] 为空，[ResolveResult.reason] 给出面向用户的原因。
 *
 * 当前源失败后，若设置里开着「自动找音源」，会按 **网易云 → 酷狗 → 哔哩哔哩**
 * 的顺序去其它源找同一首歌（见 [SourceFallback]）；全部失败才报原始原因。
 */
object PlaybackResolver {

    /** 只用曲目自己的源取链，不做替补。 */
    suspend fun resolveOwn(song: Song): ResolveResult = when (song.source) {
        Source.NETEASE -> NeteaseApi.songUrl(song.id)
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

    suspend fun resolve(song: Song): ResolveResult {
        val own = resolveOwn(song)
        if (own.ok) return own
        if (!AppGraph.store.autoSourceFallback) return own

        for (source in SourceFallback.order(song.source)) {
            val found = SourceFallback.find(song, source)
            if (found != null) return found
        }
        return own
    }
}
