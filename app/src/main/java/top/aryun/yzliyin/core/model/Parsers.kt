package top.aryun.yzliyin.core.model

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
}
