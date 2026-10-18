package top.aryun.yzliyin.core.net

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

/**
 * 酷狗 KRC 歌词解包，只为拿**接口自带的中文译文**。
 *
 * `lyrics.kugou.com/download?fmt=krc` 返回的是 base64 包着的二进制：4 字节文件头 + 异或加密 +
 * zlib 压缩。明文 KRC 里除了逐字时间轴，还有一行 `[language:...]` 标签，其值是 base64 的
 * JSON，`content[]` 中 `type == 1` 的那一项就是逐行中文译文，行序与 KRC 正文行一一对应。
 *
 * 本实现移植自 NeriPlayer 的 `KugouLyricsClient`（`decryptKugouKrcPayload` /
 * `extractKugouKrcTranslatedLyrics`），只做译文抽取，不涉及逐字时间轴的 YRC 转换。
 */
object KugouKrc {

    private const val HEADER_BYTES = 4

    /** 酷狗 KRC 固定的 16 字节异或密钥。 */
    private val KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xCE.toByte(), 0xD2.toByte(), 0x6e, 0x69,
    )

    private val TAG_REGEX = Regex("""^\[(\w+):([^\]]*)]$""")
    private val LINE_REGEX = Regex("""^\[(\d+),\s*(\d+)](.*)$""")

    /**
     * 从 `download?fmt=krc` 返回的 base64 内容里解出译文 LRC。
     * 解不出（没译文 / 解密失败 / 不是 KRC）返回 null。
     */
    fun translationLrc(base64Content: String): String? = runCatching {
        val encrypted = Base64.decode(base64Content, Base64.DEFAULT)
        val krc = decrypt(encrypted) ?: return null

        val timedLines = krc.lineSequence()
            .mapNotNull { raw ->
                val m = LINE_REGEX.find(raw.trim()) ?: return@mapNotNull null
                m.groupValues[1].toLongOrNull()?.let { m.groupValues[2].toLongOrNull() to it }
            }
            .map { (duration, start) -> start to duration }
            .toList()
        if (timedLines.isEmpty()) return null

        val languageTag = krc.lineSequence()
            .mapNotNull { raw ->
                val m = TAG_REGEX.matchEntire(raw.trim()) ?: return@mapNotNull null
                m.takeIf { it.groupValues[1] == "language" }?.groupValues?.get(2)
            }
            .firstOrNull() ?: return null

        val content = JSONObject(base64Utf8(languageTag) ?: return null)
            .optJSONArray("content") ?: return null

        // type == 1 才是译文（0 一般是音译）
        val lyricContent = (0 until content.length()).firstNotNullOfOrNull { i ->
            val lang = content.optJSONObject(i) ?: return@firstNotNullOfOrNull null
            if (lang.optInt("type", -1) != 1) null else lang.optJSONArray("lyricContent")
        } ?: return null

        buildString {
            timedLines.forEachIndexed { index, (start, _) ->
                val text = translationText(lyricContent.opt(index)).ifBlank { return@forEachIndexed }
                append('[')
                append("%02d:%02d.%02d".format(start / 60_000, start % 60_000 / 1_000, start % 1_000 / 10))
                append(']')
                append(text)
                append('\n')
            }
        }.trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 去掉 4 字节文件头，逐字节异或，再 zlib 解压。 */
    private fun decrypt(bytes: ByteArray): String? {
        if (bytes.size <= HEADER_BYTES) return null
        val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        val decrypted = ByteArray(payload.size) { i ->
            (payload[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        return runCatching {
            InflaterInputStream(ByteArrayInputStream(decrypted)).use { it.readBytes().toString(Charsets.UTF_8) }
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 译文条目既可能是纯字符串，也可能是字符串数组（多段合并）。 */
    private fun translationText(value: Any?): String = when (value) {
        is JSONArray -> (0 until value.length())
            .mapNotNull { value.optString(it).trim().takeIf(String::isNotEmpty) }
            .joinToString(" ")

        is String -> value.trim()
        else -> ""
    }

    private fun base64Utf8(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()
}
