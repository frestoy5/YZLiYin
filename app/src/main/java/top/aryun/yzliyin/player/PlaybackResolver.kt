package top.aryun.yzliyin.player

import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.ResolveResult
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.core.net.YouTubeApi
import top.aryun.yzliyin.util.AppLocale
import top.aryun.yzliyin.util.isDirectUrl

/**
 * 把曲目解析成可直接播放的 URL。
 * 失败时 [ResolveResult.url] 为空，[ResolveResult.reason] 给出面向用户的原因。
 *
 * 当前源失败后，若设置里开着「自动找音源」，会按 **网易云 → 酷狗 → 哔哩哔哩**
 * 的顺序去其它源找同一首歌（见 [SourceFallback]）；全部失败才报原始原因。
 *
 * 「失败」不只指取链拿不到 URL：取得到但一播就报错的链接（酷狗无版权即如此）
 * 会被 [SourceFallback.markDead] 记下来，重取时同样算失败，兜底才会真正接管。
 */
object PlaybackResolver {

    /** 只用曲目自己的源取链，不做替补；已缓存的曲目直接读本地文件。 */
    suspend fun resolveOwn(song: Song): ResolveResult {
        AudioCache.cachedSource(song.key)?.let { cached ->
            return ResolveResult(
                url = cached,
                source = song.source,
                fromCache = true,
            )
        }
        return when (song.source) {
            // 会员曲 + 当前账号不是会员：网易云只会下发试听片段，
            // 直接判为不可用，省一次请求也避免只播几十秒被当成成功。
            Source.NETEASE -> if (song.vip && !AppGraph.store.neteaseVip) {
                ResolveResult("", AppLocale.string(R.string.ply_err_netease_vip))
            } else {
                NeteaseApi.songUrl(song.id).withSource(Source.NETEASE)
            }

            Source.KUGOU -> KugouApi.songUrl(
                hash = song.playId.ifEmpty { song.id },
                albumAudioId = song.playSubId,
            ).withSource(Source.KUGOU)

            Source.BILIBILI -> ResolveResult(
                url = BiliApi.audioUrl(
                    bvid = song.playId.ifEmpty { song.id },
                    cid = song.playSubId,
                ),
                reason = AppLocale.string(R.string.ply_err_bili_stream),
                source = Source.BILIBILI,
            )

            // id 即 YouTube 的 videoId；登录与取流由移植过来的 Innertube 栈处理
            Source.YOUTUBE -> ResolveResult(
                url = YouTubeApi.songUrl(song.id),
                reason = AppLocale.string(R.string.ply_err_youtube_stream),
                source = Source.YOUTUBE,
            )

            // 本机音乐本该在上面 cachedSource 那一步就命中；走到这儿说明文件已经不在了
            Source.LOCAL -> ResolveResult(
                url = "",
                reason = AppLocale.string(R.string.src_local_gone),
                source = Source.LOCAL,
            )
        }.validated()
    }

    /** 标注这条链接实际出自哪个客户端（失败结果不标，避免界面显示误导）。 */
    private fun ResolveResult.withSource(source: Source): ResolveResult =
        if (ok) copy(source = source) else this

    /**
     * 出口统一校验：只认本机缓存的 `file:` 与 http(s) 直链。
     * 无版权时各源可能返回 `"null"` 这类占位值，漏过校验会一路传到下载器把 App 闪退。
     */
    private fun ResolveResult.validated(): ResolveResult =
        if (ok && !url.startsWith("file:") && !isDirectUrl(url)) {
            ResolveResult("", AppLocale.string(R.string.ply_err_url_invalid))
        } else this

    suspend fun resolve(song: Song): ResolveResult {
        // 本源上次已证实放不出（如酷狗无版权那条坏链接）时直接跳过，省一次取链
        val own = if (SourceFallback.isDead(song.key, song.source)) null else resolveOwn(song)
        if (own?.ok == true) return own

        if (AppGraph.store.autoSourceFallback) {
            for (source in SourceFallback.order(song.source)) {
                if (SourceFallback.isDead(song.key, source)) continue
                val found = SourceFallback.find(song, source)
                if (found != null) return found
            }
        }

        // 全部落空：本源那条链接既然已证实是坏的，就按失败上报，
        // 免得再喂给播放器一轮 onPlayerError、又把兜底绕开。
        return own ?: ResolveResult(
            url = "",
            reason = AppLocale.string(R.string.ply_err_all_sources_failed),
            source = song.source,
        )
    }
}
