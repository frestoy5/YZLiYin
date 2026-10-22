package top.aryun.yzliyin.player

import android.content.Context
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source

/**
 * 把一首歌下载到本地播放缓存。歌单详情页的长按多选、曲库「最近」的长按单曲都走这里。
 *
 * 与播放同一条解析路径（含跨源找音源），保证下回来的就是能播的；
 * 返回 null 表示成功，否则是给用户看的失败原因。
 * 异常一律就地吞掉——单曲失败只算这一首失败，绝不把一批下载连同 App 一起带走。
 */
suspend fun downloadSong(context: Context, song: Song): String? = runCatching {
    // 本机音乐已经在手机里，没有「下载到本地」这一说；再登记一次会让它在本地页列两遍
    if (song.source == Source.LOCAL) return@runCatching null
    val resolved = PlaybackResolver.resolve(song)
    if (!resolved.ok) {
        return@runCatching resolved.reason.ifBlank { context.getString(R.string.pld_dl_no_link) }
    }
    // downloaded = true：手动下载要一直留着，不被缓存上限淘汰掉
    val ok = AudioCache.download(song.key, resolved.url, downloaded = true)
    // 登记到曲库「本地」页；文件被淘汰后该页会按实际缓存复核
    if (ok) AppGraph.store.pushDownloadedSong(song)
    if (ok) null else context.getString(R.string.pld_dl_write_fail)
}.getOrDefault(context.getString(R.string.pld_dl_unknown))
