package top.aryun.yzliyin.core.net

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * 酷狗接口的签名与加密。
 *
 * 协议细节对齐 `top.ghhccghk.multiplatform.kugouapi`（MIT）的实现：
 * 签名 = `MD5(salt + 按键名升序拼接的 key=value + dataStr + salt)`，
 * 其中 dataStr 为 POST 请求体的紧凑 JSON（GET 为空串）。
 * 拼接用的 value 归一化规则：null → ""、Boolean → "1"/"0"、其余 toString()。
 */
internal object KugouCrypto {

    const val ANDROID_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    const val WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    const val SIGN_KEY_SALT = "185672dd44712f60bb1736df5a377e82"

    const val APP_ID = 3116
    const val CLIENT_VERSION = 11436
    const val SRC_APP_ID = 2919
    const val USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"

    private const val PUBLIC_KEY_PEM =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB"

    fun md5(data: String): String =
        MessageDigest.getInstance("MD5").digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 协议里的 value 归一化。 */
    fun normalize(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> if (value) "1" else "0"
        else -> value.toString()
    }

    private fun sortedPairs(params: Map<String, Any?>): String =
        params.entries.sortedBy { it.key }.joinToString("") { (k, v) -> "$k=${normalize(v)}" }

    fun signAndroid(params: Map<String, Any?>, dataStr: String = ""): String =
        md5("$ANDROID_SALT${sortedPairs(params)}$dataStr$ANDROID_SALT")

    fun signWeb(params: Map<String, Any?>): String =
        md5("$WEB_SALT${sortedPairs(params)}$WEB_SALT")

    /** 取链用的 `key` 参数：MD5(hash + salt + appid + mid + userid)。 */
    fun signKey(hash: String, mid: String, userid: String): String =
        md5("${hash.lowercase()}$SIGN_KEY_SALT$APP_ID$mid$userid")

    fun aesEncryptBase64(plaintext: String, key: String, iv: String): String =
        Base64.encodeToString(aesRaw(plaintext.toByteArray(Charsets.UTF_8), key, iv, true), Base64.NO_WRAP)

    fun aesDecryptBase64(cipherBase64: String, key: String, iv: String): String =
        String(aesRaw(Base64.decode(cipherBase64, Base64.NO_WRAP), key, iv, false), Charsets.UTF_8)

    private fun aesRaw(input: ByteArray, key: String, iv: String, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        return cipher.doFinal(input)
    }

    /** RSA/PKCS1 加密，返回十六进制密文（设备注册的 `p` 参数）。 */
    fun rsaEncryptPkcs1(data: ByteArray): String {
        val keyBytes = Base64.decode(PUBLIC_KEY_PEM, Base64.DEFAULT)
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pub)
        return cipher.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    /** mid = 十进制大整数形式的 MD5(guid)。 */
    fun midFromGuid(guid: String): String = BigInteger(md5(guid), 16).toString()
}
