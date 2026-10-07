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

private val NON_TEXT = Regex("[^\\p{L}\\p{N}]+")
private val WHITESPACE = Regex("\\s+")

/**
 * 跨源找音源：当前源取不到播放链接时，按 **网易云 → 酷狗 → 哔哩哔哩** 的固定顺序，
 * 从当前源之后接着找同一首歌（网易云 → 酷狗 → B站；酷狗 → B站 → 网易云；以此类推）。
 *
 * 打分规则移植自 NeriPlayer 的 `PlayerManagerNeteaseAutoSourceSwitch`：
 * 标题命中 55（否则按词重合 35/18）、歌手命中 25、时长接近 30/22/12（候选超过原曲两倍扣 15）。
 */
internal object SourceFallback {

    /** 已命中的替补曲目，避免每次取链重试都重新搜索。 */
    private val matched = ConcurrentHashMap<String, Song>()

    /** 当前源之后接着找的源，顺序取自 [Source] 的声明序。 */
    fun order(source: Source): List<Source> {
        val all = Source.entries
        val start = all.indexOf(source).coerceAtLeast(0)
        return (1 until all.size).map { all[(start + it) % all.size] }
    }

    /** 在 [source] 里找同一首歌并解析出可播放链接；找不到返回 null。 */
    suspend fun find(original: Song, source: Source): ResolveResult? {
        matched[original.key]
            ?.takeIf { it.source == source }
            ?.let { return PlaybackResolver.resolveOwn(it).takeIf { result -> result.ok } }

        val queries = buildQueries(original)
        if (queries.isEmpty()) return null

        for (query in queries) {
            val candidates = runCatching { search(source, query) }
                .getOrDefault(emptyList())
                .map { it to score(original, it) }
                .filter { it.second >= MIN_ACCEPT_SCORE }
                .sortedByDescending { it.second }
                .take(SEARCH_LIMIT)

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

    private suspend fun search(source: Source, query: String): List<Song> = when (source) {
        Source.NETEASE -> NeteaseApi.searchSongs(query).items
        Source.KUGOU -> KugouApi.searchSongs(query).items
        Source.BILIBILI -> BiliApi.searchSongs(query).items
    }

    /** 依次用「歌名 歌手」「歌手 歌名」「歌名」三种查法。 */
    private fun buildQueries(song: Song): List<String> {
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
    internal fun score(original: Song, candidate: Song): Int {
        val normalizedTitle = normalizeText(candidate.name)
        val normalizedArtist = normalizeText(candidate.artist)
        val titleHit = containsText(normalizedTitle, original.name)
        val artistHit = containsText(normalizedTitle, original.artist) ||
            containsText(normalizedArtist, original.artist)

        var score = if (titleHit) 55 else tokenOverlapScore(normalizedTitle, original.name)
        if (original.artist.isNotBlank() && artistHit) score += 25
        score += durationScore(original.durationMs, (candidate.durationMs / 1000L).toInt())
        return score
    }

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

    private fun durationScore(originalMs: Long, candidateSec: Int): Int {
        if (originalMs <= 0L || candidateSec <= 0) return 0
        val candidateMs = candidateSec * 1000L
        val diff = (candidateMs - originalMs).absoluteValue
        return when {
            diff <= 8_000L -> 30
            diff <= 20_000L -> 22
            diff <= 45_000L -> 12
            candidateMs > originalMs * 2 -> -15
            else -> 0
        }
    }
}
