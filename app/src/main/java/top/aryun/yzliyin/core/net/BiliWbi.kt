package top.aryun.yzliyin.core.net

import java.security.MessageDigest

/**
 * B 站 WBI 加签。
 *
 * mixinKey = 从 `img_url` / `sub_url` 的文件名里取出 32 位 key 拼接后，
 * 按 [MIXIN_INDEX] 重排取前 32 位；
 * `w_rid` = MD5(按 key 升序的 `k=v` 拼接串 + mixinKey)，参数值先过滤 `!'()*`。
 */
internal object BiliWbi {

    private val MIXIN_INDEX = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 62, 6, 63, 57, 20, 34, 52, 59, 11, 36, 44,
    )

    /** 由 nav 接口返回的两个图片地址推导 mixinKey。 */
    fun mixinKeyFrom(imgUrl: String, subUrl: String): String {
        if (imgUrl.isBlank() || subUrl.isBlank()) return ""
        val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
        val subKey = subUrl.substringAfterLast('/').substringBefore('.')
        val raw = imgKey + subKey
        val mixed = StringBuilder()
        MIXIN_INDEX.forEach { index -> if (index < raw.length) mixed.append(raw[index]) }
        return if (mixed.length >= 32) mixed.substring(0, 32) else mixed.toString()
    }

    /**
     * 给参数加 `wts` 与 `w_rid`，返回可直接放进 query 的完整参数表（含签名）。
     */
    fun sign(params: Map<String, String>, mixinKey: String): Map<String, String> {
        val filtered = LinkedHashMap<String, String>()
        params.forEach { (k, v) -> filtered[k] = v.replace(Regex("""[!'()*]"""), "") }
        filtered["wts"] = (System.currentTimeMillis() / 1000L).toString()

        val sorted = filtered.toSortedMap()
        val query = sorted.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val wRid = md5(query + mixinKey)

        val out = LinkedHashMap(sorted)
        out["w_rid"] = wRid
        return out
    }

    fun md5(data: String): String =
        MessageDigest.getInstance("MD5").digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun hmacSha256Hex(key: String, message: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
