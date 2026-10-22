package top.aryun.yzliyin.youtube.auth

// TODO(第 2 阶段收尾): fork 的解析器是 core.api.youtube.YouTubeBootstrapHtmlSource（约 900 行，
// 已随第 2 阶段移植到 top.aryun.yzliyin.youtube.api.YouTubeBootstrapHtmlSource）。
// 第 1 阶段要求 auth 层不依赖 api 层，这里先放最小桩：只按 ytcfg / JS 标量做宽松提取，
// 覆盖 YouTubeWebLoginVerifier 用到的 4 个字段。全链路接通后把
// YouTubeWebLoginVerifier 的引用改成 api 层实现并删除本文件。

/**
 * 统一处理 YouTube / YouTube Music 首页 bootstrap HTML：
 * 按字段读取 ytcfg 与页面 JS 标量（最小桩实现，字段语义与 fork 版一致）。
 */
internal class YouTubeBootstrapHtmlSource(html: String) {
    private val rawHtml = html

    fun optionalString(primaryField: String, vararg fallbackFields: String): String =
        findValue(primaryField, fallbackFields, wanted = ValueType.STRING)

    fun optionalBoolean(primaryField: String, vararg fallbackFields: String): String =
        findValue(primaryField, fallbackFields, wanted = ValueType.BOOLEAN)

    private enum class ValueType { STRING, BOOLEAN }

    private fun findValue(
        primaryField: String,
        fallbackFields: Array<out String>,
        wanted: ValueType
    ): String {
        (sequenceOf(primaryField) + fallbackFields.asSequence()).forEach { field ->
            readScalar(field)?.let { value ->
                val kind = when {
                    value == "true" || value == "false" -> ValueType.BOOLEAN
                    else -> ValueType.STRING
                }
                if (kind == wanted) return value
            }
        }
        return ""
    }

    /** 在 ytcfg.set({...}) 与页面头尾窗口里找 `field: value`，只取相邻的 JS 标量。 */
    private fun readScalar(field: String): String? {
        var searchStart = 0
        while (searchStart < rawHtml.length) {
            val fieldIndex = rawHtml.indexOf(field, searchStart)
            if (fieldIndex < 0) return null
            val after = rawHtml.getOrNull(fieldIndex + field.length)
            if (after != null && !isIdentifierBoundary(after)) {
                searchStart = fieldIndex + field.length
                continue
            }
            var cursor = fieldIndex + field.length
            // 字段名允许带引号（"LOGGED_IN": / 'LOGGED_IN' :）
            if (rawHtml.getOrNull(cursor) == '"' || rawHtml.getOrNull(cursor) == '\'') cursor++
            while (cursor < rawHtml.length && rawHtml[cursor].isWhitespace()) cursor++
            if (rawHtml.getOrNull(cursor) != ':') {
                searchStart = fieldIndex + field.length
                continue
            }
            cursor++
            while (cursor < rawHtml.length && rawHtml[cursor].isWhitespace()) cursor++
            val value = readValueAt(cursor) ?: run {
                searchStart = fieldIndex + field.length
                continue
            }
            return value
        }
        return null
    }

    private fun readValueAt(start: Int): String? {
        if (start >= rawHtml.length) return null
        val first = rawHtml[start]
        if (first == '"' || first == '\'') {
            val sb = StringBuilder()
            var i = start + 1
            while (i < rawHtml.length) {
                val ch = rawHtml[i]
                when {
                    ch == '\\' && i + 1 < rawHtml.length -> {
                        sb.append(rawHtml[i + 1])
                        i += 2
                    }
                    ch == first -> return sb.toString()
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
            }
            return null
        }
        val digits = StringBuilder()
        var i = start
        while (i < rawHtml.length && rawHtml[i].isDigit()) {
            digits.append(rawHtml[i])
            i++
        }
        if (digits.isNotEmpty()) return digits.toString()
        for (literal in listOf("true", "false")) {
            if (rawHtml.startsWith(literal, start)) return literal
        }
        return null
    }

    private fun isIdentifierBoundary(ch: Char): Boolean =
        !(ch.isLetterOrDigit() || ch == '_' || ch == '$')
}
