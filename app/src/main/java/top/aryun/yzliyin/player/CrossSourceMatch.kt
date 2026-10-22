package top.aryun.yzliyin.player

import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.data.SessionStore
import top.aryun.yzliyin.core.model.FollowMeta
import top.aryun.yzliyin.core.model.LyricLine
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.core.net.YouTubeApi
import top.aryun.yzliyin.util.NPLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 跨源取元数据：把「跟随源」的封面 / 歌手 / 歌词挂到当前正在播的这首歌上。
 *
 * 匹配复用 [SourceFallback] 那套打分（歌名 + 歌手 + 时长），并额外要求**标题真正命中**；
 * 匹配不上就回落当前源，绝不因为跨源没找到就显示张冠李戴的封面或歌词。
 *
 * 歌词取用顺序（见 [lyrics]）：跟随源 → 当前源 → 另一个有歌词接口的源。
 */
internal object CrossSourceMatch {

    private const val TAG = "CrossSourceMatch"

    /**
     * 匹配分下限。[SourceFallback.score] 里标题命中 55 + 歌手 25 = 80 是正常命中，
     * 歌手字段为空或时长对不上时仍能到 70；再低就容易匹配到翻唱、现场这些同名版本。
     */
    private const val MIN_MATCH_SCORE = 70

    /** 结果缓存上限（防长期播放无限增长）。 */
    private const val MATCH_CACHE_LIMIT = 200

    /** 有歌词接口的源（B 站没有独立歌词接口）。 */
    private val LYRIC_SOURCES = listOf(Source.NETEASE, Source.KUGOU)

    /** `曲目 key + 目标源` → 匹配结果；只缓存命中，没命中的下次换歌再试。 */
    private val matchCache = ConcurrentHashMap<String, Song>()

    /** 设置里选的跟随源；关闭（或没选）返回 null。 */
    fun followSource(): Source? {
        val id = AppGraph.store.metadataFollow
        if (id == SessionStore.FOLLOW_OFF) return null
        return Source.fromId(id)
    }

    /**
     * 在 [target] 源里按歌名 + 歌手 + 时长找同一首歌；找不到返回 null。
     * 结果按 `曲目 key + 目标源` 缓存。
     */
    suspend fun match(original: Song, target: Source): Song? {
        if (target == original.source) return original
        val cacheKey = "${original.key}|${target.id}"
        matchCache[cacheKey]?.let { return it }

        for (query in SourceFallback.buildQueries(original)) {
            val candidates = runCatching { search(target, query) }
                .onFailure { NPLogger.w(TAG, "检索失败 $target：${it.message}") }
                .getOrNull()
                .orEmpty()
            val best = candidates
                .filter { titleMatches(original, it) && durationPlausible(original, it) }
                .map { it to SourceFallback.score(original, it, target) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= MIN_MATCH_SCORE }
                ?.first
                ?: continue
            if (matchCache.size >= MATCH_CACHE_LIMIT) matchCache.clear()
            matchCache[cacheKey] = best
            NPLogger.i(TAG, "匹配命中 $target：${best.name} - ${best.artist}")
            return best
        }
        return null
    }

    /**
     * 标题算不算命中：正向（候选标题里含原曲名）与反向（原曲名里含候选标题）都认。
     * B 站视频标题常带「【MV】」「歌词版」这类前后缀，只认正向会漏掉绝大多数 B 站歌曲。
     */
    private fun titleMatches(original: Song, candidate: Song): Boolean =
        SourceFallback.titleHit(original, candidate) || SourceFallback.titleHit(candidate, original)

    /**
     * 时长合理性：候选比原曲短一半以上基本不是同一首（合集、串烧会踩中
     * 「原曲名包含候选标题」这条反向命中）；B 站 MV / 现场普遍更长，更长一律放行。
     */
    private fun durationPlausible(original: Song, candidate: Song): Boolean {
        val own = original.durationMs
        val other = candidate.durationMs
        if (own <= 0L || other <= 0L) return true
        return other >= own / 2
    }

    /**
     * 取当前曲目的歌词，按优先级逐级尝试，全空返回空列表：
     *  1. 跟随源（设置里选了、且与当前源不同）
     *  2. 当前源
     *  3. 另一个有歌词接口的源（网易云 ↔ 酷狗；B 站没有歌词接口）
     *
     * 用户手工编辑过的歌词在播放页就短路了，走不到这里。
     */
    suspend fun lyrics(song: Song): List<LyricLine> {
        val follow = followSource()
        val order = buildList {
            if (follow != null && follow != song.source) add(follow)
            add(song.source)
            LYRIC_SOURCES.forEach { if (it != follow && it != song.source) add(it) }
        }.filter { it in LYRIC_SOURCES }

        for (source in order) {
            val lines = runCatching { lyricFrom(source, song) }
                .onFailure { NPLogger.w(TAG, "取词失败 $source：${it.message}") }
                .getOrNull()
                .orEmpty()
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    /**
     * 跟随源开着时，后台把这首歌在跟随源里匹配到的封面 / 歌手落库；
     * 展示侧在播放状态刷新时读 [SessionStore.applyFollowMeta]，正在播的界面立刻生效。
     */
    suspend fun refreshFollow(song: Song) {
        val target = followSource() ?: return
        // 跟的就是当前源：封面 / 歌手本来就是它给的，不必再匹配一轮
        if (target == song.source) return
        // 这一首已经匹配过（跟随源切换时旧记录会被自动忽略，这里自然会重取）
        if (AppGraph.store.followMeta(song.key) != null) return
        val matched = match(song, target) ?: return
        AppGraph.store.saveFollowMeta(
            song.key,
            FollowMeta(cover = matched.cover, artist = matched.artist, source = target.id),
        )
    }

    private suspend fun lyricFrom(source: Source, song: Song): List<LyricLine> = when (source) {
        // 酷狗取词是「歌名 + 歌手 + 时长」检索：本源不是酷狗时别把 bvid / 网易 id 当 hash 传过去
        Source.KUGOU -> KugouApi.lyric(
            if (song.source == Source.KUGOU) song else song.copy(playId = "", id = ""),
        )

        // 网易云要歌曲 id：非网易云的曲子先去网易云搜一首匹配的，再用它的 id 取词
        Source.NETEASE -> {
            val id = if (song.source == Source.NETEASE) {
                song.id
            } else {
                match(song, Source.NETEASE)?.id.orEmpty()
            }
            if (id.isEmpty()) emptyList() else NeteaseApi.lyric(id)
        }

        Source.BILIBILI -> emptyList()

        // YouTube 无歌词直连接口；跨源歌词本就优先走网易云 / 酷狗（见 LYRIC_SOURCES）
        Source.YOUTUBE -> emptyList()

        Source.LOCAL -> emptyList()
    }

    /**
     * 在指定源搜歌：四个源统一分发，跨源匹配内部用，播放页「更换音频」也复用它。
     * 读取类接口失败会抛出，调用方自行兜底。
     */
    suspend fun search(source: Source, query: String): List<Song> = when (source) {
        Source.NETEASE -> NeteaseApi.searchSongs(query).items
        Source.KUGOU -> KugouApi.searchSongs(query).items
        Source.BILIBILI -> BiliApi.searchSongs(query).items
        Source.YOUTUBE -> YouTubeApi.searchSongs(query).items
        Source.LOCAL -> emptyList()
    }
}
