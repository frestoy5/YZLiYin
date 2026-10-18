package top.aryun.yzliyin.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 是否是一条真正能用的 http/https 直链。
 *
 * 各源在**无版权 / 无权限**时经常把 `url` 字段下发成 JSON `null`，
 * 而 `optString()` 会把 JSON null 变成字符串 `"null"`（长度 4，非空），
 * 于是被 `isNotBlank()` 判成有效链接一路往下传：
 * 播放端只是报播放失败，下载端 `Request.Builder().url("null")` 会直接抛
 * `IllegalArgumentException` 把 App 闪退。所有取链出口统一用这个判断把关。
 */
fun isDirectUrl(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("http://") || trimmed.startsWith("https://")
}

/** 读取流内容但限制最大字节数，防止异常响应撑爆内存。 */
fun InputStream.readBytesLimited(
    maxBytes: Long,
    bufferSize: Int = 8 * 1024
): ByteArray {
    require(maxBytes >= 0L) { "maxBytes must be non-negative" }
    ByteArrayOutputStream().use { out ->
        val buf = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val n = this.read(buf)
            if (n == -1) break
            total += n
            require(total <= maxBytes) { "stream exceeds limit: $total > $maxBytes" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
