package top.aryun.yzliyin.core.net

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.aryun.yzliyin.util.JsonUtil
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.util.readBytesLimited
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 酷狗接口响应。[status] 为 HTTP 状态码，业务失败（status=0 或 error_code≠0）映射为 502。 */
class KugouResponse(
    val status: Int,
    val body: JSONObject,
    val rawBytes: ByteArray? = null,
)

/**
 * 酷狗接口传输层。
 *
 * 协议（签名、参数、端点）对齐 `top.ghhccghk.multiplatform.kugouapi`（MIT）的实现，
 * 用 OkHttp 自行实现而不引入该 SDK，避免其传递依赖与环境冲突。
 *
 * 鉴权靠签名而非 Cookie，因此这里不像 [NeteaseClient] 那样维护 CookieJar，
 * 只保存设备标识与登录凭证（`guid`/`mid`/`dfid`/`token`/`userid` 等）。
 */
class KugouClient(private val context: Context) {

    private companion object {
        const val DEFAULT_BASE = "https://gateway.kugou.com"
        const val LOGIN_BASE = "https://login-user.kugou.com"
        const val REGISTER_BASE = "https://userservice.kugou.com"
        const val MAX_RESPONSE_BYTES = 8L * 1024L * 1024L
        const val TAG = "KugouClient"
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 设备标识与登录凭证，按源持久化（见 SessionStore.cookieMap）。 */
    private val jar = linkedMapOf<String, String>()

    @Volatile
    private var registered = false

    init {
        ensureIdentity()
    }

    // ---------- 凭证 ----------

    fun seed(cookies: Map<String, String>) {
        jar.clear()
        jar.putAll(cookies)
        ensureIdentity()
        registered = jar["dfid"].orEmpty().isNotEmpty() && jar["dfid"] != "-"
    }

    fun cookies(): Map<String, String> = LinkedHashMap(jar)

    fun isLoggedIn(): Boolean =
        !jar["token"].isNullOrBlank() && !jar["userid"].isNullOrBlank() && jar["userid"] != "0"

    /** 写入登录凭证（扫码确认后调用）。 */
    fun saveLogin(token: String, userid: String) {
        jar["token"] = token
        jar["userid"] = userid
    }

    // ---------- 设备标识 ----------

    /**
     * guid 由稳定的设备指纹派生，保证重装/清数据后仍是同一台设备，
     * 避免每次冷启动都重新注册设备。mid = 十进制大整数形式的 MD5(guid)。
     */
    private fun ensureIdentity() {
        if (jar["KUGOU_API_GUID"].isNullOrBlank()) {
            val androidId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty()
            val seed = "$androidId:${Build.BRAND}:${Build.DEVICE}:${Build.MODEL}:${Build.FINGERPRINT}"
            val guid = KugouCrypto.md5(seed)
            jar["KUGOU_API_GUID"] = guid
            jar["KUGOU_API_MID"] = KugouCrypto.midFromGuid(guid)
        }
        if (jar["KUGOU_API_DEV"].isNullOrBlank()) {
            jar["KUGOU_API_DEV"] = randomString(10).uppercase()
        }
        if (jar["KUGOU_API_MAC"].isNullOrBlank()) {
            jar["KUGOU_API_MAC"] = "02:00:00:00:00:00"
        }
        if (jar["dfid"].isNullOrBlank()) {
            jar["dfid"] = "-"
        }
    }

    private fun randomString(len: Int): String =
        buildString { repeat(len) { append(Random.nextInt(10, 36).toString(36)) } }

    /** 首次使用（dfid 为空）时注册设备，成功后 dfid 落地。 */
    suspend fun ensureRegistered() {
        if (registered) return
        registered = true
        runCatching { registerDev() }
            .onFailure { NPLogger.w(TAG, "设备注册失败：${it.message}") }
    }

    private suspend fun registerDev() {
        val guid = jar["KUGOU_API_GUID"].orEmpty()
        val userid = jar["userid"].orEmpty().ifBlank { "0" }
        val token = jar["token"].orEmpty()

        val device = loadDeviceInfo()
        val payload = linkedMapOf<String, Any?>(
            "availableRamSize" to device.availableRam,
            "availableRomSize" to device.availableRom,
            "availableSDSize" to device.availableSd,
            "basebandVer" to "",
            "batteryLevel" to device.batteryLevel,
            "batteryStatus" to device.batteryStatus,
            "brand" to Build.BRAND,
            "buildSerial" to Build.DEVICE,
            "device" to Build.DEVICE,
            "imei" to guid,
            "imsi" to "",
            "manufacturer" to Build.MANUFACTURER,
            "uuid" to guid,
            "accelerometer" to false, "accelerometerValue" to "",
            "gravity" to false, "gravityValue" to "",
            "gyroscope" to false, "gyroscopeValue" to "",
            "light" to false, "lightValue" to "",
            "magnetic" to false, "magneticValue" to "",
            "orientation" to false, "orientationValue" to "",
            "pressure" to false, "pressureValue" to "",
            "step_counter" to false, "step_counterValue" to "",
            "temperature" to false, "temperatureValue" to "",
        )

        val seed = randomString(6).lowercase()
        val md5Seed = KugouCrypto.md5(seed)
        val aesKey = md5Seed.substring(0, 16)
        val aesIv = md5Seed.substring(16, 32)
        val encrypted = KugouCrypto.aesEncryptBase64(JsonUtil.toJson(payload), aesKey, aesIv)

        val rsaPlain = JsonUtil.toJson(
            linkedMapOf("aes" to seed, "uid" to userid.toLongOrNull().orZero(), "token" to token)
        )

        val response = execute(
            path = "/risk/v2/r_register_dev",
            params = mapOf("part" to 1, "platid" to 1, "p" to KugouCrypto.rsaEncryptPkcs1(rsaPlain.toByteArray())),
            data = encrypted,
            method = "POST",
            baseUrl = REGISTER_BASE,
            rawResponse = true,
        )
        if (response.status != 200) {
            throw IOException("设备注册失败：HTTP ${response.status}")
        }
        val raw = response.rawBytes ?: throw IOException("设备注册响应为空")
        val decrypted = runCatching {
            KugouCrypto.aesDecryptBase64(Base64.encodeToString(raw, Base64.NO_WRAP), aesKey, aesIv)
        }.getOrElse { throw IOException("设备注册响应解密失败：${it.message}") }
        val dfid = JSONObject(decrypted).optJSONObject("data")?.optString("dfid").orEmpty()
        if (dfid.isBlank()) throw IOException("设备注册未返回 dfid")
        jar["dfid"] = dfid
        NPLogger.d(TAG, "设备注册成功")
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private data class DeviceInfo(
        val availableRam: Long,
        val availableRom: Long,
        val availableSd: Long,
        val batteryLevel: Int,
        val batteryStatus: Int,
    )

    private fun loadDeviceInfo(): DeviceInfo {
        val memInfo = ActivityManager.MemoryInfo()
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memInfo)
        }
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return DeviceInfo(
            availableRam = memInfo.availMem,
            availableRom = runCatching {
                StatFs(Environment.getDataDirectory().path)
                    .let { it.availableBlocksLong * it.blockSizeLong }
            }.getOrDefault(0L),
            availableSd = runCatching {
                StatFs(Environment.getExternalStorageDirectory().path)
                    .let { it.availableBlocksLong * it.blockSizeLong }
            }.getOrDefault(0L),
            batteryLevel = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100,
            batteryStatus = if (battery?.isCharging == true) 2 else 3,
        )
    }

    // ---------- 请求 ----------

    /**
     * 发起一次酷狗请求。
     *
     * @param data 请求体：`String` 为原始体（不参与 JSON 序列化），`JSONObject` 为 JSON 体；
     *             两者都会作为签名里的 dataStr（`String` 直接使用）。
     * @param web 使用 web 签名（扫码登录相关接口），默认 android 签名。
     * @param encryptKey 取链接口需要，追加 `key` 参数。
     */
    private suspend fun execute(
        path: String,
        params: Map<String, Any?>,
        data: Any? = null,
        method: String = "GET",
        baseUrl: String = DEFAULT_BASE,
        headers: Map<String, String> = emptyMap(),
        web: Boolean = false,
        encryptKey: Boolean = false,
        rawResponse: Boolean = false,
    ): KugouResponse = withContext(Dispatchers.IO) {
        // 首次请求前先注册设备拿到 dfid：dfid 参与每个请求的签名与请求头。
        ensureRegistered()
        val dfid = jar["dfid"].orEmpty()
        val mid = jar["KUGOU_API_MID"].orEmpty()
        val token = jar["token"].orEmpty()
        val userid = jar["userid"].orEmpty().ifBlank { "0" }
        val clientTime = System.currentTimeMillis() / 1000

        val all = linkedMapOf<String, Any?>()
        all["dfid"] = dfid
        all["mid"] = mid
        all["uuid"] = "-"
        all["appid"] = KugouCrypto.APP_ID
        all["clientver"] = KugouCrypto.CLIENT_VERSION
        all["clienttime"] = clientTime
        if (token.isNotEmpty()) all["token"] = token
        all["userid"] = userid
        all.putAll(params)

        // 签名里的 dataStr 就是请求体的紧凑 JSON；org.json 的转义与 kotlinx 一致，
        // 因此 JSONObject.toString() 与 SDK 端序列化结果逐字节相同。
        val dataStr = when (data) {
            null -> ""
            is String -> data
            is JSONObject -> data.toString()
            else -> error("不支持的请求体类型：${data::class.java.name}")
        }

        if (encryptKey && !all.containsKey("key")) {
            all["key"] = KugouCrypto.signKey(
                hash = all["hash"].toString(),
                mid = mid,
                userid = userid,
            )
        }
        if (!all.containsKey("signature")) {
            all["signature"] = if (web) KugouCrypto.signWeb(all)
            else KugouCrypto.signAndroid(all, dataStr)
        }

        val urlBuilder = (baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
            .toHttpUrl().newBuilder()
        all.forEach { (k, v) -> urlBuilder.addQueryParameter(k, KugouCrypto.normalize(v)) }
        val url = urlBuilder.build().toString()

        val body = when (data) {
            null -> null
            is String -> data.toRequestBody(null)
            else -> dataStr.toRequestBody("application/json; charset=utf-8".toMediaType())
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", KugouCrypto.USER_AGENT)
            .header("dfid", dfid)
            .header("clienttime", clientTime.toString())
            .header("mid", mid)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        val request = if (method == "POST") {
            requestBuilder.post(body ?: "".toRequestBody(null)).build()
        } else {
            requestBuilder.get().build()
        }

        okHttpClient.newCall(request).execute().use { response ->
            val bytes = response.body?.byteStream()?.readBytesLimited(MAX_RESPONSE_BYTES) ?: ByteArray(0)
            val httpStatus = response.code
            if (rawResponse) {
                return@use KugouResponse(httpStatus, JSONObject(), bytes)
            }
            val text = String(bytes, Charsets.UTF_8)
            val parsed = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            val businessStatus = if (parsed.has("status")) parsed.optInt("status") else null
            val errorCode = if (parsed.has("error_code")) parsed.optInt("error_code") else null
            val status = if (businessStatus == 0 || (errorCode != null && errorCode != 0)) {
                502
            } else {
                httpStatus
            }
            if (status != 200) {
                NPLogger.d(TAG, "$path -> status=$status（HTTP $httpStatus）")
            }
            KugouResponse(status, parsed)
        }
    }

    // ---------- 业务接口 ----------

    /** 搜索歌曲。返回 `body.data.lists` 与 `body.data.total`。 */
    suspend fun search(keyword: String, page: Int, pageSize: Int): KugouResponse = execute(
        path = "/v3/search/song",
        params = mapOf(
            "albumhide" to 0,
            "iscorrection" to 1,
            "keyword" to keyword,
            "nocollect" to 0,
            "page" to page,
            "pagesize" to pageSize,
            "platform" to "AndroidFilter",
        ),
        headers = mapOf("x-router" to "complexsearch.kugou.com", "kg-tid" to "1"),
    )

    /** 取播放链接，返回 `body.url[0]`（失败时可回退 `body.backupUrl[0]`）。 */
    suspend fun songUrl(hash: String, albumAudioId: String, quality: String): KugouResponse = execute(
        path = "/v5/url",
        params = mapOf(
            "album_id" to 0,
            "area_code" to 1,
            "hash" to hash.lowercase(),
            "ssa_flag" to "is_fromtrack",
            "version" to KugouCrypto.CLIENT_VERSION,
            "page_id" to 967177915,
            "quality" to quality,
            "album_audio_id" to albumAudioId,
            "behavior" to "play",
            "pid" to 411,
            "cmd" to 26,
            "pidversion" to 3001,
            "IsFreePart" to 0,
            "ppage_id" to "356753938,823673182,967485191",
            "cdnBackup" to 1,
            "module" to "",
            "kcard" to 0,
        ),
        headers = mapOf("x-router" to "trackercdn.kugou.com"),
        encryptKey = true,
    )

    /** 当前用户的歌单列表（含「我喜欢」系统歌单），返回 `body.data.info`。 */
    suspend fun userPlaylists(page: Int, pageSize: Int): KugouResponse {
        val userid = jar["userid"].orEmpty().ifBlank { "0" }
        val token = jar["token"].orEmpty()
        return execute(
            path = "/v7/get_all_list",
            params = mapOf("plat" to 1, "userid" to userid, "token" to token),
            data = JSONObject().apply {
                put("token", token)
                put("userid", userid)
                put("total_ver", 979)
                put("type", 2)
                put("page", page)
                put("pagesize", pageSize)
            },
            method = "POST",
            headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
        )
    }

    /** 公开歌单曲目（按 global_collection_id）。 */
    suspend fun playlistTracks(globalCollectionId: String, page: Int, pageSize: Int): KugouResponse = execute(
        path = "/pubsongs/v2/get_other_list_file_nofilt",
        params = mapOf(
            "area_code" to 1,
            "begin_idx" to (page - 1) * pageSize,
            "plat" to 1,
            "type" to 1,
            "mode" to 1,
            "personal_switch" to 1,
            "extend_fields" to "abtags,hot_cmt,popularization",
            "pagesize" to pageSize,
            "global_collection_id" to globalCollectionId,
        ),
    )

    /** 私有/自建歌单曲目（按 listid）。 */
    suspend fun playlistTracksSelf(listId: String, page: Int, pageSize: Int): KugouResponse = execute(
        path = "/v4/get_list_all_file",
        params = emptyMap(),
        data = JSONObject().apply {
            put("listid", listId)
            put("userid", jar["userid"].orEmpty().ifBlank { "0" })
            put("area_code", 1)
            put("show_relate_goods", 0)
            put("pagesize", pageSize)
            put("allplatform", 1)
            put("show_cover", 1)
            put("type", 0)
            put("token", jar["token"].orEmpty())
            put("page", page)
        },
        method = "POST",
        headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
    )

    // ---------- 扫码登录 ----------

    /** 申请二维码 key。 */
    suspend fun createQrKey(): KugouResponse = execute(
        path = "/v2/qrcode",
        params = mapOf(
            "appid" to 1001,
            "type" to 1,
            "plat" to 4,
            "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${KugouCrypto.APP_ID}&",
            "srcappid" to KugouCrypto.SRC_APP_ID,
        ),
        baseUrl = LOGIN_BASE,
        web = true,
    )

    /** 轮询二维码状态。data.token / data.userid 出现即登录成功。 */
    suspend fun checkQrCode(key: String): KugouResponse = execute(
        path = "/v2/get_userinfo_qrcode",
        params = mapOf(
            "plat" to 4,
            "appid" to KugouCrypto.APP_ID,
            "srcappid" to KugouCrypto.SRC_APP_ID,
            "qrcode" to key,
        ),
        baseUrl = LOGIN_BASE,
        web = true,
    )
}
