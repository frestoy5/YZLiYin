package top.aryun.yzliyin.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.util.readBytesLimited
import java.io.IOException
import java.util.concurrent.TimeUnit

class BiliException(message: String) : IOException(message)

data class BiliVideoPage(val cid: Long, val page: Int, val part: String, val durationSec: Int)

data class BiliVideoInfo(
    val bvid: String,
    val aid: Long,
    val title: String,
    val cover: String,
    val durationSec: Int,
    val ownerName: String,
    val ownerMid: Long,
)

data class BiliFavFolder(
    val mediaId: Long,
    val fid: Long,
    val mid: Long,
    val title: String,
    val cover: String,
    val intro: String,
    val count: Int,
    /** attr 的最低位为 1 表示这是账号的默认收藏夹。 */
    val attr: Int,
) {
    val isDefault: Boolean get() = attr and 1 != 0
}

data class BiliFavItem(
    val type: Int,
    val id: Long,
    val bvid: String,
    val title: String,
    val cover: String,
    val durationSec: Int,
    val upperName: String,
)

data class BiliFavPage(
    val folder: BiliFavFolder?,
    val items: List<BiliFavItem>,
    val hasMore: Boolean,
)

data class BiliQrSession(val key: String, val url: String)

data class BiliQrCheck(val code: Int, val message: String, val cookies: Map<String, String>)

data class BiliProfile(val name: String, val avatar: String, val mid: Long)

/**
 * B 站接口传输层。
 *
 * 收藏夹、视频信息、搜索走 Wbi 加签；取流走 `/x/player/wbi/playurl`，
 * 请求都需要 `Referer` 与浏览器 UA，未登录时用匿名指纹接口换 buvid。
 * 播放链接本身也要求同样的 Referer/UA，见 `PlaybackService` 的数据源头注入。
 */
class BiliClient {

    private companion object {
        const val TAG = "BiliClient"
        const val BASE_PLAY_URL = "https://api.bilibili.com/x/player/wbi/playurl"
        const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
        const val FINGERPRINT_URL = "https://api.bilibili.com/x/frontend/finger/spi"
        const val WEB_TICKET_URL =
            "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket"
        const val VIEW_URL = "https://api.bilibili.com/x/web-interface/wbi/view"
        const val SEARCH_TYPE_URL = "https://api.bilibili.com/x/web-interface/wbi/search/type"
        const val PAGELIST_URL = "https://api.bilibili.com/x/player/pagelist"
        const val FAV_CREATED_LIST_ALL = "https://api.bilibili.com/x/v3/fav/folder/created/list-all"
        const val FAV_CREATED_LIST = "https://api.bilibili.com/x/v3/fav/folder/created/list"
        const val FAV_COLLECTED_LIST = "https://api.bilibili.com/x/v3/fav/folder/collected/list"
        const val FAV_FOLDER_INFO = "https://api.bilibili.com/x/v3/fav/folder/info"
        const val FAV_RESOURCE_LIST = "https://api.bilibili.com/x/v3/fav/resource/list"
        const val QR_GENERATE_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        const val QR_POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"

        const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val FINGERPRINT_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 " +
                "Mobile/15E148 Safari/604.1 Edg/114.0.0.0"
        const val WEB_TICKET_UA =
            "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0"

        const val REFERER = "https://www.bilibili.com"
        const val QR_REFERER = "https://passport.bilibili.com/login"

        const val DASH_DOLBY = 1 shl 8
        const val WBI_CACHE_MS = 10 * 60 * 1000L
        const val ANON_CACHE_MS = 60 * 60 * 1000L
        const val MAX_RESPONSE_BYTES = 8L * 1024L * 1024L
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jar = linkedMapOf<String, String>()
    private val jarLock = Any()

    private val mixinLock = Mutex()

    @Volatile
    private var cachedMixinKey: String? = null

    @Volatile
    private var mixinCachedAt = 0L

    @Volatile
    private var anonCookies: Map<String, String>? = null

    @Volatile
    private var anonCachedAt = 0L

    // ---------- 凭证 ----------

    fun seed(cookies: Map<String, String>) {
        synchronized(jarLock) {
            jar.clear()
            jar.putAll(cookies)
        }
        anonCookies = null
    }

    fun cookies(): Map<String, String> = synchronized(jarLock) { LinkedHashMap(jar) }

    fun isLoggedIn(): Boolean = !cookies()["SESSDATA"].isNullOrBlank()

    /** 当前登录用户的 mid（未登录时 0）。 */
    fun userId(): Long = cookies()["DedeUserID"].orEmpty().toLongOrNull() ?: 0L

    fun saveLogin(cookies: Map<String, String>) {
        synchronized(jarLock) {
            cookies.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) jar[k] = v }
        }
    }

    fun clearLogin() {
        synchronized(jarLock) {
            jar.keys.removeAll(
                setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
            )
        }
    }

    private fun mergeSetCookies(headers: List<String>) {
        if (headers.isEmpty()) return
        synchronized(jarLock) {
            headers.forEach { header ->
                val pair = header.split(';').firstOrNull().orEmpty()
                val idx = pair.indexOf('=')
                if (idx <= 0) return@forEach
                val name = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                if (name.isBlank()) return@forEach
                val expired = value.isBlank() || header.split(';').any {
                    it.trim().equals("Max-Age=0", ignoreCase = true)
                }
                if (expired) jar.remove(name) else jar[name] = value
            }
        }
    }

    // ---------- 请求 ----------

    /**
     * 组装 Cookie 头。未登录且没有 buvid 时先换一次匿名指纹并缓存，
     * 否则取流接口容易被风控拦下。
     */
    private suspend fun cookieHeader(): String {
        val stored = cookies()
        if (!stored["SESSDATA"].isNullOrBlank() || !stored["buvid3"].isNullOrBlank()) {
            return stored.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        val now = System.currentTimeMillis()
        val cached = anonCookies
        val anon = if (cached != null && now - anonCachedAt < ANON_CACHE_MS) {
            cached
        } else {
            fetchAnonCookies().also {
                anonCookies = it
                anonCachedAt = now
            }
        }
        return (stored + anon).entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private suspend fun fetchAnonCookies(): Map<String, String> {
        val text = http.newCall(
            Request.Builder()
                .url(FINGERPRINT_URL.toHttpUrl())
                .header("User-Agent", FINGERPRINT_UA)
                .get()
                .build(),
        ).execute().use { response ->
            mergeSetCookies(response.headers("Set-Cookie"))
            String(response.body?.byteStream()?.readBytesLimited(MAX_RESPONSE_BYTES) ?: ByteArray(0))
        }
        val data = runCatching { JSONObject(text) }.getOrNull()?.optJSONObject("data") ?: JSONObject()
        val map = linkedMapOf<String, String>()
        fun put(key: String, vararg names: String) {
            names.forEach { name ->
                val value = data.optString(name)
                if (value.isNotBlank()) {
                    map[key] = value
                    return
                }
            }
        }
        put("buvid3", "b_3", "buvid3")
        put("buvid4", "b_4", "buvid4")
        put("buvid_fp", "buvid_fp")
        put("buvid_fp_plain", "buvid_fp_plain")
        put("b_lsid", "b_lsid")
        return map
    }

    private suspend fun executeJson(
        url: HttpUrl,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = WEB_UA,
        referer: String = REFERER,
    ): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
        cookieHeader().takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        headers.forEach { (k, v) -> builder.header(k, v) }

        http.newCall(builder.get().build()).execute().use { response ->
            mergeSetCookies(response.headers("Set-Cookie"))
            val bytes = response.body?.byteStream()?.readBytesLimited(MAX_RESPONSE_BYTES) ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            if (!response.isSuccessful) throw BiliException("HTTP ${response.code}: ${text.take(200)}")
            if (text.isBlank()) throw BiliException("B 站返回空响应")
            return runCatching { JSONObject(text) }
                .getOrElse { throw BiliException("B 站响应不是合法 JSON") }
        }
    }

    private suspend fun getJson(url: String, params: Map<String, String>): JSONObject {
        val builder = url.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return executeJson(builder.build())
    }

    private suspend fun getJsonWbi(url: String, params: Map<String, String>): JSONObject {
        val signed = BiliWbi.sign(params, mixinKey())
        return getJson(url, signed)
    }

    private fun JSONObject.requireData(endpoint: String): JSONObject {
        val code = optInt("code", -1)
        if (code != 0) {
            val message = optString("message").ifBlank { optString("msg") }
            throw BiliException("$endpoint 失败：code=$code, message=$message")
        }
        return optJSONObject("data") ?: JSONObject()
    }

    // ---------- Wbi mixinKey ----------

    private suspend fun mixinKey(): String {
        val now = System.currentTimeMillis()
        cachedMixinKey?.let { if (now - mixinCachedAt < WBI_CACHE_MS) return it }
        return mixinLock.withLock {
            val again = System.currentTimeMillis()
            cachedMixinKey?.let { if (again - mixinCachedAt < WBI_CACHE_MS) return it }
            val key = runCatching { fetchMixinKeyFromNav() }
                .getOrElse { error ->
                    NPLogger.w(TAG, "nav 取 mixinKey 失败，回退 WebTicket：${error.message}")
                    fetchMixinKeyFromTicket()
                }
            cachedMixinKey = key
            mixinCachedAt = again
            key
        }
    }

    private suspend fun fetchMixinKeyFromNav(): String {
        val json = executeJson(NAV_URL.toHttpUrl())
        val wbi = json.optJSONObject("data")?.optJSONObject("wbi_img") ?: JSONObject()
        val key = BiliWbi.mixinKeyFrom(imgUrl = wbi.optString("img_url"), subUrl = wbi.optString("sub_url"))
        if (key.isBlank()) throw BiliException("nav 未返回有效的 wbi_img")
        return key
    }

    private suspend fun fetchMixinKeyFromTicket(): String {
        val ts = System.currentTimeMillis() / 1000L
        val builder = WEB_TICKET_URL.toHttpUrl().newBuilder()
            .addQueryParameter("key_id", "ec02")
            .addQueryParameter("hexsign", BiliWbi.hmacSha256Hex("XgwSnGZ1p", "ts$ts"))
            .addQueryParameter("context[ts]", ts.toString())
        cookies()["bili_jct"]?.takeIf { it.isNotBlank() }
            ?.let { builder.addQueryParameter("csrf", it) }

        val builderReq = Request.Builder()
            .url(builder.build())
            .header("User-Agent", WEB_TICKET_UA)
            .header("Referer", REFERER)
            .get()
        http.newCall(builderReq.build()).execute().use { response ->
            mergeSetCookies(response.headers("Set-Cookie"))
            val text = String(response.body?.byteStream()?.readBytesLimited(MAX_RESPONSE_BYTES) ?: ByteArray(0))
            val nav = JSONObject(text).optJSONObject("data")?.optJSONObject("nav") ?: JSONObject()
            val key = BiliWbi.mixinKeyFrom(imgUrl = nav.optString("img"), subUrl = nav.optString("sub"))
            if (key.isBlank()) throw BiliException("WebTicket 未返回有效的 nav")
            return key
        }
    }

    // ---------- 登录态 ----------

    /** 校验登录是否有效（未登录返回 false）。 */
    suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) return@withContext false
        runCatching {
            val data = executeJson(NAV_URL.toHttpUrl()).requireData("nav")
            data.optBoolean("isLogin", false) && data.optLong("mid", 0L) > 0L
        }.getOrDefault(false)
    }

    /** 当前账号的昵称与头像（`nav` 接口顺带返回，未登录返回 null）。 */
    suspend fun userProfile(): BiliProfile? = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) return@withContext null
        runCatching {
            val data = executeJson(NAV_URL.toHttpUrl()).optJSONObject("data")
                ?: return@runCatching null
            if (!data.optBoolean("isLogin", false)) return@runCatching null
            BiliProfile(
                name = data.optString("uname"),
                avatar = httpsUrl(data.optString("face")),
                mid = data.optLong("mid"),
            )
        }.getOrElse {
            NPLogger.w(TAG, "获取用户信息失败：${it.message}")
            null
        }
    }

    suspend fun qrGenerate(): BiliQrSession = withContext(Dispatchers.IO) {
        val data = executeJson(QR_GENERATE_URL.toHttpUrl(), referer = QR_REFERER).requireData("qrcode/generate")
        val key = data.optString("qrcode_key").trim()
        val url = data.optString("url").trim()
        if (key.isBlank() || url.isBlank()) throw BiliException("B 站未返回二维码")
        BiliQrSession(key, url)
    }

    /**
     * 轮询扫码状态。data.code：0 成功、86101 未扫码、86090 已扫码待确认、86038 已过期。
     * 成功后 Set-Cookie 里的 SESSDATA 等已并入本地。
     */
    suspend fun qrPoll(key: String): BiliQrCheck = withContext(Dispatchers.IO) {
        val url = QR_POLL_URL.toHttpUrl().newBuilder().addQueryParameter("qrcode_key", key).build()
        val json = executeJson(url, referer = QR_REFERER)
        val data = json.optJSONObject("data") ?: JSONObject()
        val code = data.optInt("code", json.optInt("code", -1))
        BiliQrCheck(
            code = code,
            message = data.optString("message").ifBlank { json.optString("message") },
            cookies = if (code == 0) cookies() else emptyMap(),
        )
    }

    // ---------- 视频 ----------

    suspend fun videoPages(bvid: String): List<BiliVideoPage> = withContext(Dispatchers.IO) {
        val arr = getJson(PAGELIST_URL, mapOf("bvid" to bvid)).optJSONArray("data") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            BiliVideoPage(
                cid = o.optLong("cid"),
                page = o.optInt("page"),
                part = o.optString("part"),
                durationSec = o.optInt("duration"),
            )
        }
    }

    suspend fun videoInfo(bvid: String): BiliVideoInfo = withContext(Dispatchers.IO) {
        val data = getJsonWbi(VIEW_URL, mapOf("bvid" to bvid)).requireData("view")
        val owner = data.optJSONObject("owner") ?: JSONObject()
        BiliVideoInfo(
            bvid = data.optString("bvid", bvid),
            aid = data.optLong("aid"),
            title = data.optString("title"),
            cover = httpsUrl(data.optString("pic")),
            durationSec = data.optInt("duration"),
            ownerName = owner.optString("name"),
            ownerMid = owner.optLong("mid"),
        )
    }

    suspend fun searchVideos(keyword: String, page: Int, pageSize: Int = 30): Pair<List<BiliFavItem>, Boolean> =
        withContext(Dispatchers.IO) {
            val data = getJsonWbi(
                SEARCH_TYPE_URL,
                mapOf(
                    "search_type" to "video",
                    "keyword" to keyword,
                    "order" to "totalrank",
                    "duration" to "0",
                    "tids" to "0",
                    "page" to page.toString(),
                ),
            ).requireData("search/type")
            val arr = data.optJSONArray("result") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optString("type") != "video") return@mapNotNull null
                val bvid = o.optString("bvid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                BiliFavItem(
                    type = 2,
                    id = o.optLong("aid"),
                    bvid = bvid,
                    title = stripHtml(o.optString("title")),
                    cover = httpsUrl(o.optString("pic")),
                    durationSec = parseDuration(o.optString("duration")),
                    upperName = o.optString("author"),
                )
            }
            val numPages = data.optInt("numPages", 1)
            items to (page < numPages)
        }

    /**
     * 取音频直链：优先 DASH 的 `dash.audio`（按带宽取最高），
     * 无 DASH 时回退 `durl` 的渐进式 MP4。
     */
    suspend fun audioUrl(bvid: String, cid: String): String = withContext(Dispatchers.IO) {
        val resolvedCid = cid.ifBlank { videoPages(bvid).firstOrNull()?.cid?.toString().orEmpty() }
        if (resolvedCid.isBlank()) return@withContext ""
        val data = getJsonWbi(
            BASE_PLAY_URL,
            mapOf(
                "bvid" to bvid,
                "cid" to resolvedCid,
                "fnval" to ((1 shl 4) or DASH_DOLBY).toString(),
                "fnver" to "0",
                "fourk" to "0",
                "otype" to "json",
                "platform" to "pc",
            ),
        ).requireData("playurl")

        val dashAudio = data.optJSONObject("dash")?.optJSONArray("audio")
        val best = (0 until (dashAudio?.length() ?: 0))
            .mapNotNull { dashAudio?.optJSONObject(it) }
            .filter { it.optString("baseUrl", it.optString("base_url")).isNotBlank() }
            .maxByOrNull { it.optLong("bandwidth", 0L) }
        best?.let { return@withContext it.optString("baseUrl", it.optString("base_url")) }

        data.optJSONArray("durl")?.optJSONObject(0)?.optString("url").orEmpty()
    }

    // ---------- 收藏夹 ----------

    /** 该账号创建的收藏夹；`attr & 1` 的那个即默认收藏夹（B 站没有官方「我喜欢」列表）。 */
    suspend fun createdFavFolders(mid: Long): List<BiliFavFolder> = withContext(Dispatchers.IO) {
        val data = getJson(
            FAV_CREATED_LIST_ALL,
            mapOf("up_mid" to mid.toString(), "web_location" to "333.1387"),
        ).requireData("fav/created/list-all")
        val folders = parseFolderList(data.optJSONArray("list"))
        val expected = data.optInt("count", folders.size)
        if (folders.size >= expected) return@withContext folders

        // list-all 偶尔只返回部分，回退分页接口补齐
        val out = folders.toMutableList()
        var page = 1
        while (out.size < expected) {
            val paged = runCatching {
                getJson(
                    FAV_CREATED_LIST,
                    mapOf(
                        "up_mid" to mid.toString(),
                        "pn" to page.toString(),
                        "ps" to "20",
                        "web_location" to "333.1387",
                    ),
                ).requireData("fav/created/list")
            }.getOrNull() ?: break
            val batch = parseFolderList(paged.optJSONArray("list"))
            if (batch.isEmpty()) break
            out += batch
            page += 1
        }
        out.distinctBy { it.mediaId }
    }

    suspend fun collectedFavFolders(mid: Long): List<BiliFavFolder> = withContext(Dispatchers.IO) {
        val out = mutableListOf<BiliFavFolder>()
        var page = 1
        var expected = Int.MAX_VALUE
        while (out.size < expected) {
            val data = runCatching {
                getJson(
                    FAV_COLLECTED_LIST,
                    mapOf(
                        "up_mid" to mid.toString(),
                        "pn" to page.toString(),
                        "ps" to "20",
                        "platform" to "web",
                    ),
                ).requireData("fav/collected/list")
            }.getOrNull() ?: break
            val batch = parseFolderList(data.optJSONArray("list"))
            expected = data.optInt("count", 0).takeIf { it > 0 } ?: out.size + batch.size
            if (batch.isEmpty()) break
            out += batch
            page += 1
        }
        out.distinctBy { it.mediaId }
    }

    private fun parseFolderList(arr: JSONArray?): List<BiliFavFolder> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            BiliFavFolder(
                mediaId = o.optLong("id"),
                fid = o.optLong("fid"),
                mid = o.optLong("mid"),
                title = o.optString("title"),
                cover = httpsUrl(o.optString("cover")),
                intro = o.optString("intro"),
                count = o.optInt("media_count"),
                attr = o.optInt("attr"),
            )
        }
    }

    suspend fun favFolder(mediaId: Long): BiliFavFolder? = withContext(Dispatchers.IO) {
        runCatching {
            val data = getJson(FAV_FOLDER_INFO, mapOf("media_id" to mediaId.toString()))
                .requireData("fav/folder/info")
            val cnt = data.optJSONObject("cnt_info")
            BiliFavFolder(
                mediaId = data.optLong("id"),
                fid = data.optLong("fid"),
                mid = data.optLong("mid"),
                title = data.optString("title"),
                cover = httpsUrl(data.optString("cover")),
                intro = data.optString("intro"),
                count = data.optInt("media_count"),
                attr = data.optInt("attr"),
            )
        }.getOrNull()
    }

    /** 收藏夹内容。音视频混合时只保留视频（`type == 2`），音频条目 B 站接口不给可播流。 */
    suspend fun favFolderContents(mediaId: Long, page: Int, pageSize: Int): BiliFavPage =
        withContext(Dispatchers.IO) {
            val data = getJson(
                FAV_RESOURCE_LIST,
                mapOf(
                    "media_id" to mediaId.toString(),
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                    "order" to "mtime",
                    "platform" to "web",
                ),
            ).requireData("fav/resource/list")

            val info = data.optJSONObject("info")
            val folder = info?.let {
                BiliFavFolder(
                    mediaId = it.optLong("id"),
                    fid = it.optLong("fid"),
                    mid = it.optLong("mid"),
                    title = it.optString("title"),
                    cover = httpsUrl(it.optString("cover")),
                    intro = it.optString("intro"),
                    count = it.optInt("media_count"),
                    attr = it.optInt("attr"),
                )
            }
            val arr = data.optJSONArray("medias") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                if (m.optInt("type") != 2) return@mapNotNull null
                val bvid = m.optString("bvid").ifBlank { m.optString("bv_id") }
                if (bvid.isBlank()) return@mapNotNull null
                BiliFavItem(
                    type = 2,
                    id = m.optLong("id"),
                    bvid = bvid,
                    title = m.optString("title"),
                    cover = httpsUrl(m.optString("cover")),
                    durationSec = m.optInt("duration"),
                    upperName = m.optJSONObject("upper")?.optString("name").orEmpty(),
                )
            }
            BiliFavPage(folder, items, data.optBoolean("has_more", false))
        }

    // ---------- 工具 ----------

    private fun httpsUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<.*?>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")

    private fun parseDuration(text: String): Int {
        if (text.isBlank()) return 0
        return text.split(':').mapNotNull { it.toIntOrNull() }
            .fold(0) { acc, part -> acc * 60 + part }
    }
}
