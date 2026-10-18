package top.aryun.yzliyin.core.model

import java.util.Locale
import kotlin.math.abs

/** 纯文本解析工具（歌词）。 */
object Parsers {

    /** LRC 文本 → 按时间排序的歌词行。 */
    fun parseLrc(content: String): List<LyricLine> {
        if (content.isEmpty()) return emptyList()
        val out = ArrayList<LyricLine>()
        val re = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]\s*([^\[]*)""")
        for (line in content.split("\n")) {
            val m = re.find(line) ?: continue
            val min = m.groupValues[1].toLongOrNull() ?: continue
            val sec = m.groupValues[2].toLongOrNull() ?: continue
            val frac = m.groupValues[3]
            val fracMs = when (frac.length) {
                0 -> 0L
                1 -> (frac.toLongOrNull() ?: 0L) * 100
                2 -> (frac.toLongOrNull() ?: 0L) * 10
                else -> (frac.take(3).toLongOrNull() ?: 0L)
            }
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) continue
            out.add(LyricLine(min * 60000 + sec * 1000 + fracMs, text))
        }
        return out.sortedBy { it.timeMs }
    }

    /**
     * 歌词行 → LRC 文本（编辑器的初始内容）。
     *
     * 译文另起一行、与原文共用同一时间戳，读回时由 [parseEditedLrc] 按同样约定还原成译文。
     */
    fun toLrc(lines: List<LyricLine>): String = lines.joinToString("\n") { line ->
        val tag = "[${formatLrcTime(line.timeMs)}]"
        // 译文可能本身带换行，逐行带上时间戳写出去，读回时才能原样还原
        val transLines = line.translation?.split('\n').orEmpty().filter { it.isNotBlank() }
        buildString {
            append(tag).append(line.text)
            transLines.forEach { append('\n').append(tag).append(it) }
        }
    }

    /**
     * 解析**用户编辑过**的 LRC：同一时间戳的第二行按译文并进上一行。
     *
     * [toLrc] 写出的就是这种形式，保证编辑器里看到什么、保存后播出来就是什么。
     * 空行与无时间戳的行直接跳过，每行只认第一个时间戳（与 [parseLrc] 一致）。
     */
    fun parseEditedLrc(content: String): List<LyricLine> {
        if (content.isEmpty()) return emptyList()
        val re = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]\s*([^\[]*)""")
        val out = ArrayList<LyricLine>()
        for (raw in content.split("\n")) {
            val m = re.find(raw) ?: continue
            val min = m.groupValues[1].toLongOrNull() ?: continue
            val sec = m.groupValues[2].toLongOrNull() ?: continue
            val frac = m.groupValues[3]
            val timeMs = min * 60000 + sec * 1000 + when (frac.length) {
                0 -> 0L
                1 -> (frac.toLongOrNull() ?: 0L) * 100
                2 -> (frac.toLongOrNull() ?: 0L) * 10
                else -> (frac.take(3).toLongOrNull() ?: 0L)
            }
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.timeMs == timeMs) {
                out[out.lastIndex] = last.copy(
                    translation = last.translation?.let { "$it\n$text" } ?: text,
                )
            } else {
                out.add(LyricLine(timeMs, text))
            }
        }
        // sortedBy 稳定排序，同一时间戳的原文/译文顺序不被打乱
        return out.sortedBy { it.timeMs }
    }

    /** 时间 → `mm:ss.xx`（百分秒，与 [parseLrc] 的精度一致）。 */
    private fun formatLrcTime(timeMs: Long): String {
        val total = timeMs.coerceAtLeast(0L)
        return "%02d:%02d.%02d".format(
            Locale.ROOT,
            total / 60000,
            (total % 60000) / 1000,
            (total % 1000) / 10,
        )
    }

    /**
     * 把译文 LRC 并入原文，填到 [LyricLine.translation]。
     *
     * 只给**非中文**的原文行配译文（用户看的是原文，中文行下面再挂一句中文反而重复），
     * 也顺手过滤掉与原文雷同的行。时间戳先精确匹配，再放宽到 [TOLERANCE_MS] 内取最近一条。
     */
    fun mergeTranslation(
        original: List<LyricLine>,
        translated: List<LyricLine>,
    ): List<LyricLine> {
        if (original.isEmpty() || translated.isEmpty()) return original
        val times = translated.map { it.timeMs }
        return original.map { line ->
            val exact = translated.firstOrNull { it.timeMs == line.timeMs }?.text
            val near = exact ?: run {
                val idx = times.indexOfFirst { abs(it - line.timeMs) <= TOLERANCE_MS }
                if (idx >= 0) translated[idx].text else null
            }
            line.copy(
                translation = near
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != line.text && !isMostlyChinese(line.text) },
            )
        }
    }

    /**
     * 是否以中文为主（CJK 字符占到字母类字符的多数）。
     * 用于跳过「作词/作曲」这类本来就是中文的行。
     */
    fun isMostlyChinese(text: String): Boolean {
        var han = 0
        var latin = 0
        for (ch in text) {
            when {
                ch.isCJK() -> han++
                ch.isLetter() -> latin++
            }
        }
        if (han == 0) return false
        return han * 2 >= han + latin
    }

    private fun Char.isCJK(): Boolean = this in '\u4E00'..'\u9FFF' ||
        this in '\u3400'..'\u4DBF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\uAC00'..'\uD7AF'

    /** 译文时间戳与原文的允许偏差。 */
    private const val TOLERANCE_MS = 700L
}
