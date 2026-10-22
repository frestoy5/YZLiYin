package top.aryun.yzliyin.core.lyric

import top.aryun.yzliyin.core.model.LyricLine
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.player.SourceFallback
import top.aryun.yzliyin.util.NPLogger
import kotlin.math.abs

/** 「匹配歌词」可选的平台，与跨源取词支持的源一致（B 站 / YouTube 没有歌词接口）。 */
internal enum class LyricMatchSource(val source: Source) {
    NETEASE(Source.NETEASE),
    KUGOU(Source.KUGOU),
}

/**
 * 置信度：只用来给用户一个「要不要核对一下」的直观提示，
 * 分档见 [LyricMatch.confidenceFor]。
 */
internal enum class LyricConfidence { HIGH, MEDIUM, LOW }

/**
 * 一条候选歌词：某个平台按关键字检索到、有可能属于这首歌的歌词。
 *
 * [lines] 已经是解析好、带译文的歌词行，选中即可直接落盘（见 `LyricEdits`）；
 * [score] / [durationDeltaMs] / [confidence] 只负责展示，告诉用户这条靠不靠谱。
 */
internal data class LyricCandidate(
    val source: LyricMatchSource,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lines: List<LyricLine>,
    val score: Int,
    /** 与原曲的时长差；任一方没有时长时为 null（不显示差值）。 */
    val durationDeltaMs: Long?,
    val confidence: LyricConfidence,
) {
    /** 有译文的行数，用来在列表里提示「这条带翻译」。 */
    val translatedLines: Int get() = lines.count { !it.translation.isNullOrBlank() }

    /** 同一条歌词的稳定标识，结果去重用。 */
    val identity: String get() = "${source.name.lowercase()}:$id"
}

/**
 * 歌词匹配：像 NeriPlayer 的「匹配歌词」那样，把关键字丢给各平台搜出若干候选，
 * 按「歌名 + 歌手 + 时长」打分排序后交给用户挑选。
 *
 * 与 [top.aryun.yzliyin.player.CrossSourceMatch.lyrics] 的**自动**取词不同，
 * 这里不挑第一条就用，而是把候选全摆出来：自动匹配挑错版本（现场版 / 翻唱 / 加长版）
 * 时，用户可以自己点一条对的。
 *
 * 打分复用取音源那套 [SourceFallback.score]，保证「什么算同一首歌」全 app 一致。
 */
internal object LyricMatch {

    private const val TAG = "LyricMatch"

    /** 返回给用户的候选上限。 */
    private const val MAX_RESULTS = 20

    /** 每个平台最多取前几条候选去下歌词（下载是逐条串行的，多了太慢）。 */
    private const val MAX_LOOKUPS_PER_SOURCE = 4

    /** 每个平台每条查询最多收几首候选曲。 */
    private const val MAX_SEARCH_PER_QUERY = 8

    /** 高 / 中置信度的分数线。 */
    private const val HIGH_SCORE = 80
    private const val MEDIUM_SCORE = 62

    /** 时长差超过这个数一律降为低置信度：版本基本对不上了。 */
    private const val LOW_CONFIDENCE_DELTA_MS = 15_000L

    /**
     * 按 [keyword] 在 [sources] 里检索并下载歌词，返回**已按匹配分排序**的候选。
     *
     * 单个平台失败只记日志、跳过该平台，不影响其余平台的结果。
     */
    suspend fun search(
        song: Song,
        keyword: String,
        sources: List<LyricMatchSource>,
    ): List<LyricCandidate> {
        val queries = buildQueries(song, keyword)
        if (queries.isEmpty() || sources.isEmpty()) return emptyList()

        val candidates = sources.flatMap { source ->
            runCatching { collectCandidates(source, queries, song) }
                .onFailure { NPLogger.w(TAG, "检索失败 $source：${it.message}") }
                .getOrNull()
                .orEmpty()
        }

        return candidates
            .distinctBy { it.identity }
            .sortedWith(
                compareByDescending<LyricCandidate> { it.score }
                    .thenBy { it.durationDeltaMs ?: Long.MAX_VALUE },
            )
            .take(MAX_RESULTS)
    }

    /** 关键字优先，其次「歌名 歌手」「歌名」，用户把关键字清空也能按元数据搜。 */
    private fun buildQueries(song: Song, keyword: String): List<String> = listOf(
        keyword.trim(),
        "${song.name.trim()} ${song.artist.trim()}".trim(),
        song.name.trim(),
    ).filter { it.isNotBlank() }.distinct()

    private suspend fun collectCandidates(
        source: LyricMatchSource,
        queries: List<String>,
        song: Song,
    ): List<LyricCandidate> {
        val found = LinkedHashMap<String, Song>()
        for (query in queries) {
            val items = runCatching {
                when (source) {
                    LyricMatchSource.NETEASE -> NeteaseApi.searchSongs(query, pageSize = 20).items
                    LyricMatchSource.KUGOU -> KugouApi.searchSongs(query, pageSize = 20).items
                }
            }.onFailure {
                NPLogger.w(TAG, "搜索失败 $source / $query：${it.message}")
            }.getOrNull().orEmpty()
            items.take(MAX_SEARCH_PER_QUERY).forEach { found.putIfAbsent(it.key, it) }
        }

        return found.values
            .filter { durationPlausible(song, it) }
            .map { it to SourceFallback.score(song, it, source.source) }
            .sortedWith(
                compareByDescending<Pair<Song, Int>> { it.second }
                    .thenBy { absDurationDelta(song, it.first) ?: Long.MAX_VALUE },
            )
            .take(MAX_LOOKUPS_PER_SOURCE)
            .mapNotNull { (candidate, score) -> fetchCandidate(source, song, candidate, score) }
    }

    /** 下载并解析一条候选的歌词；没词（或接口失败）就丢掉，不摆给用户看。 */
    private suspend fun fetchCandidate(
        source: LyricMatchSource,
        song: Song,
        candidate: Song,
        score: Int,
    ): LyricCandidate? {
        val lines = runCatching {
            when (source) {
                // 酷狗按 hash + 时长检索歌词，本源不是酷狗时只用酷狗自己的搜索结果
                LyricMatchSource.KUGOU -> KugouApi.lyric(candidate)
                LyricMatchSource.NETEASE -> NeteaseApi.lyric(candidate.id)
            }
        }.onFailure {
            NPLogger.w(TAG, "取词失败 $source ${candidate.name}：${it.message}")
        }.getOrNull().orEmpty()
        if (lines.isEmpty()) return null

        val deltaMs = absDurationDelta(song, candidate)
        return LyricCandidate(
            source = source,
            id = candidate.id,
            title = candidate.name,
            artist = candidate.artist,
            album = candidate.albumName,
            durationMs = candidate.durationMs,
            lines = lines,
            score = score,
            durationDeltaMs = deltaMs,
            confidence = confidenceFor(score, deltaMs),
        )
    }

    /**
     * 时长合不合理：候选比原曲短一半以上基本不是同一首（合集、串烧会踩中
     * 「原曲名包含候选标题」这条命中），更长一律放行（现场版 / MV 普遍更长）。
     * 与跨源取音源、跨源取歌词用的是同一条规则。
     */
    private fun durationPlausible(original: Song, candidate: Song): Boolean {
        val own = original.durationMs
        val other = candidate.durationMs
        if (own <= 0L || other <= 0L) return true
        return other >= own / 2
    }

    /** 与原曲的时长差；任一方没有时长返回 null（列表里就不显示差值）。 */
    private fun absDurationDelta(original: Song, candidate: Song): Long? {
        if (original.durationMs <= 0L || candidate.durationMs <= 0L) return null
        return abs(original.durationMs - candidate.durationMs)
    }

    private fun confidenceFor(score: Int, deltaMs: Long?): LyricConfidence = when {
        deltaMs != null && deltaMs > LOW_CONFIDENCE_DELTA_MS -> LyricConfidence.LOW
        score >= HIGH_SCORE -> LyricConfidence.HIGH
        score >= MEDIUM_SCORE -> LyricConfidence.MEDIUM
        else -> LyricConfidence.LOW
    }
}
