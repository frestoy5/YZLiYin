package top.aryun.yzliyin.youtube.auth

// 移植自 fork data.auth.common/RawCookieTextParser.kt + RawHeaderTextParser.kt：
// 导入 YouTube 凭据时解析「整段 Cookie 串」与「HTTP 头粘贴文本」。
// fork 里是 data.auth.common 公共包（多平台登录共用），本项目只有 YouTube 用到，
// 因此就近放进 youtube.auth；实现逐字保留。

private val rawCookieDelimiterRegex = Regex("[;\\r\\n]+")

fun parseRawCookieText(raw: String): LinkedHashMap<String, String> {
    val cookies = linkedMapOf<String, String>()
    raw.split(rawCookieDelimiterRegex)
        .asSequence()
        .map(String::trim)
        .filter { it.isNotBlank() }
        .forEach { segment ->
            val delimiterIndex = segment.indexOf('=')
            if (delimiterIndex <= 0) {
                return@forEach
            }
            val key = segment.substring(0, delimiterIndex).trim()
            val value = segment.substring(delimiterIndex + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                cookies[key] = value
            }
        }
    return cookies
}

fun parseRawHeaderText(raw: String): LinkedHashMap<String, String> {
    val headers = linkedMapOf<String, String>()
    raw.lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith(':') }
        .forEach { line ->
            val delimiterIndex = line.indexOf(':')
            if (delimiterIndex <= 0) {
                return@forEach
            }
            val key = line.substring(0, delimiterIndex).trim().lowercase()
            val value = line.substring(delimiterIndex + 1).trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                headers[key] = value
            }
        }
    return headers
}
