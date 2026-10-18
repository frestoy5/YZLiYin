package top.aryun.yzliyin.player

import top.aryun.yzliyin.core.model.ResolveResult
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.KugouApi
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.util.NPLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

private const val TAG = "SourceFallback"

/** 每个源最多考察的候选数。 */
private const val SEARCH_LIMIT = 6

/** 及格分：标题命中(55) + 歌手命中(25) 或 时长接近(30) 才够。 */
private const val MIN_ACCEPT_SCORE = 70

/**
 * 哔哩哔哩的及格分。
 *
 * B 站候选天生拿不到另外两源的两个加分项：`artist` 位是 UP 主名而不是原唱歌手，
 * 视频时长还含片头片尾（现场版/歌词版更长），于是「标题命中 55 + 歌手 25」永远凑不齐，
 * 旧的 70 分线会把几乎整个 B 站结果集判死——这正是「酷狗无版权却不跳到 B 站」的原因。
 * 因此 B 站只要标题命中（55）即算候选，并在 [SourceFallback.titleHit] 里强制要求是标题命中、
 * 不是词重合的模糊分，避免常见标题被误匹配。
 */
private const val MIN_ACCEPT_SCORE_BILI = 55

private val NON_TEXT = Regex("[^\\p{L}\\p{N}]+")
private val WHITESPACE = Regex("\\s+")

/**
 * 跨源找音源：当前源取不到播放链接时，按 **网易云 → 酷狗 → 哔哩哔哩** 的固定顺序，
 * 从当前源之后接着找同一首歌（网易云 → 酷狗 → B站；酷狗 → B站 → 网易云；以此类推）。
 *
 * 打分规则移植自 NeriPlayer 的 `PlayerManagerNeteaseAutoSourceSwitch`：
 * 标题命中 55（否则按词重合 35/18）、歌手命中 25、时长接近 30/22/12（候选超过原曲两倍扣 15）。
 * 及格线按源放宽：B 站的歌手位是 UP 主名、视频时长含片头片尾，
 * 用网易云/酷狗那条 70 分线会全军覆没，故降到 [MIN_ACCEPT_SCORE_BILI] 且只认标题命中。
 */
internal object SourceFallback {

    /** 已命中的替补曲目，避免每次取链重试都重新搜索。 */
    private val matched = ConcurrentHashMap<String, Song>()

    /**
     * 已证实放不出播放链接的源（曲目 key → 该曲目下失效的源）。
     *
     * 酷狗无版权时常常返回一条取得到、但一播就 403 的链接：只看 URL 是否为空的话，
     * [PlaybackResolver] 会认为取链成功直接返回它，兜底永远走不到；播放器报错后
     * 重取又拿到同一条坏链接，于是无限打转。这里把「已证实坏掉的源」记下来，
     * 重取时直接跳过，才真的会去别的源找。
     */
    private val deadSources = ConcurrentHashMap<String, MutableSet<Source>>()

    fun markDead(songKey: String, source: Source) {
        deadSources.getOrPut(songKey) { ConcurrentHashMap.newKeySet() }.add(source)
        if (deadSources.size > 64) deadSources.keys.take(16).forEach { deadSources.remove(it) }
    }

    fun isDead(songKey: String, source: Source): Boolean =
        deadSources[songKey]?.contains(source) == true

    /** 取到能放的链接后清掉判定：一次网络抖动不该永久拉黑某个源。 */
    fun clearDead(songKey: String) {
        deadSources.remove(songKey)
    }

    /** 当前源之后接着找的源，顺序取自 [Source] 的声明序。 */
    fun order(source: Source): List<Source> {
        val all = Source.entries
        val start = all.indexOf(source).coerceAtLeast(0)
        return (1 until all.size).map { all[(start + it) % all.size] }
    }

    /** 在 [source] 里找同一首歌并解析出可播放链接；找不到返回 null。 */
    suspend fun find(original: Song, source: Source): ResolveResult? {
        // 记住的替补先试一次；它取链失败时不能直接返回 null，
        // 否则同一首歌后面再也不会重新搜索（换条直链或重搜一次还有救）。
        matched[original.key]
            ?.takeIf { it.source == source }
            ?.let { PlaybackResolver.resolveOwn(it).takeIf { result -> result.ok } }
            ?.let { return it }

        val queries = buildQueries(original)
        if (queries.isEmpty()) return null

        val minScore = minScore(source)

        for (query in queries) {
            val searched = runCatching { search(source, query) }.getOrDefault(emptyList())
            val scored = searched.map { it to score(original, it, source) }
            val candidates = scored
                .filter { (candidate, candidateScore) ->
                    candidateScore >= minScore && (source != Source.BILIBILI || titleHit(original, candidate))
                }
                .sortedByDescending { it.second }
                .take(SEARCH_LIMIT)

            if (candidates.isEmpty() && searched.isNotEmpty()) {
                NPLogger.d(
                    TAG,
                    "替补候选全部落选：${original.name}（已试 ${source.label}，" +
                        "共 ${searched.size} 条，最高 ${scored.maxOfOrNull { it.second } ?: 0} 分 / 及格 $minScore）",
                )
            }

            for ((candidate, candidateScore) in candidates) {
                val result = PlaybackResolver.resolveOwn(candidate)
                if (result.ok) {
                    NPLogger.d(
                        TAG,
                        "替补命中：${original.name} → ${source.label} / ${candidate.name}（$candidateScore 分）",
                    )
                    if (matched.size > 32) matched.clear()
                    matched[original.key] = candidate
                    return result
                }
            }
        }
        NPLogger.d(TAG, "替补未找到：${original.name} - ${original.artist}（已试 ${source.label}）")
        return null
    }

    /** 各源的及格分：B 站元数据天然对不齐，线放低（见 [MIN_ACCEPT_SCORE_BILI]）。 */
    private fun minScore(source: Source): Int =
        if (source == Source.BILIBILI) MIN_ACCEPT_SCORE_BILI else MIN_ACCEPT_SCORE

    private suspend fun search(source: Source, query: String): List<Song> = when (source) {
        Source.NETEASE -> NeteaseApi.searchSongs(query).items
        Source.KUGOU -> KugouApi.searchSongs(query).items
        Source.BILIBILI -> BiliApi.searchSongs(query).items
    }

    /** 依次用「歌名 歌手」「歌手 歌名」「歌名」三种查法。 */
    /** 生成跨源检索用的关键词（跨源取歌词/元数据也复用它，见 [CrossSourceMatch]）。 */
    fun buildQueries(song: Song): List<String> {
        val title = song.name.trim()
        val artist = song.artist.trim()
        return listOf(
            "$title $artist",
            "$artist $title",
            title,
        ).map { WHITESPACE.replace(it.trim(), " ") }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** 候选与原曲的匹配分。 */
    internal fun score(original: Song, candidate: Song, source: Source): Int {
        val normalizedTitle = normalizeText(candidate.name)
        val normalizedArtist = normalizeText(candidate.artist)
        val titleHit = containsText(normalizedTitle, original.name)
        val artistHit = containsText(normalizedTitle, original.artist) ||
            containsText(normalizedArtist, original.artist)

        var score = if (titleHit) 55 else tokenOverlapScore(normalizedTitle, original.name)
        if (original.artist.isNotBlank() && artistHit) score += 25
        score += durationScore(original.durationMs, (candidate.durationMs / 1000L).toInt(), source)
        return score
    }

    /** 标题是否真正命中（不含词重合那档模糊分）；跨源元数据匹配同样要求这一条。 */
    fun titleHit(original: Song, candidate: Song): Boolean =
        containsText(normalizeText(candidate.name), original.name)

    private fun normalizeText(value: String): String =
        WHITESPACE.replace(NON_TEXT.replace(value.lowercase(), " "), " ").trim()

    private fun compact(value: String): String = normalizeText(value).replace(" ", "")

    private fun containsText(normalizedText: String, rawNeedle: String): Boolean {
        val needle = compact(rawNeedle)
        if (needle.length < 2) return false
        return normalizedText.replace(" ", "").contains(needle)
    }

    private fun tokenOverlapScore(normalizedText: String, rawNeedle: String): Int {
        val tokens = normalizeText(rawNeedle).split(' ').filter { it.length >= 2 }
        if (tokens.isEmpty()) return 0
        val hits = tokens.count { normalizedText.contains(it) }
        return when {
            hits == tokens.size -> 35
            hits > 0 -> 18
            else -> 0
        }
    }

    private fun durationScore(originalMs: Long, candidateSec: Int, source: Source): Int {
        if (originalMs <= 0L || candidateSec <= 0) return 0
        val candidateMs = candidateSec * 1000L
        val diff = (candidateMs - originalMs).absoluteValue
        return when {
            diff <= 8_000L -> 30
            diff <= 20_000L -> 22
            diff <= 45_000L -> 12
            // B 站的 MV / 现场 / 歌词版视频普遍比原曲长一大截，长不代表不是这首歌，不扣分
            candidateMs > originalMs * 2 && source != Source.BILIBILI -> -15
            else -> 0
        }
    }
}
