package top.aryun.yzliyin.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

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
