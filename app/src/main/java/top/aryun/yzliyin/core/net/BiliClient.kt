package top.aryun.yzliyin.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import top.aryun.yzliyin.core.model.Comment
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.util.readBytesLimited
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * B 站接口异常。
 *
 * [isBan] 标记这是不是风控拉黑（HTTP 412 / code -412 "request was banned"）：
 * 这类错误换套匿名指纹后重试一次通常能恢复，与普通的参数/权限错误区分开。
 */
class BiliException(message: String, val isBan: Boolean = false) : IOException(message)

data class BiliVideoPage(val cid: Long, val page: Int, val part: String, val durationSec: Int)

data class BiliVideoInfo(
    val bvid: String,
    val aid: Long,
    val title: String,
    val cover: String,
    val durationSec: Int,
    val ownerName: String,
    val ownerMid: Long,
    /** 动态 id（`x/web-interface/view` 的 `data.dynamic`），一键四连「转发到动态」要用。 */
    val dynamicId: Long = 0L,
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
    /** UP 主 mid：点歌手名进「UP 主主页」用，0 表示这条没带（老缓存）。 */
    val upperMid: Long = 0L,
)

data class BiliFavPage(
    val folder: BiliFavFolder?,
    val items: List<BiliFavItem>,
    val hasMore: Boolean,
)

/**
 * UP 主空间投稿一页。
 * [nextCursor] 是下一页的游标，取上一页最后一条的 aid；[hasMore] 为 false 时忽略。
 */
data class BiliUpPage(
    val items: List<BiliFavItem>,
    val hasMore: Boolean,
    val nextCursor: String = "",
)

/**
 * UP 主资料卡（`x/web-interface/card` + `x/relation/stat`）。
 * 关注 / 粉丝来自 relation/stat，获赞来自 card 接口；ipLocation 为空表示接口没给。
 */
data class BiliUpCard(
    val mid: Long,
    val name: String,
    val face: String,
    val sign: String,
    val following: Long,
    val follower: Long,
    /** 获赞总数。 */
    val likeNum: Long,
    /** 是否大会员（vip.type > 0）。 */
    val vip: Boolean,
    val ipLocation: String,
)

/**
 * 一条动态（`x/polymer/web-dynamic/v1/feed/space`）。
 * 视频动态带 bvid/aid，可点进页内播放；纯文字、转发等只有 [text]。
 */
data class BiliDynamicItem(
    val id: String,
    /** 动态正文（纯文字或视频简介）。 */
    val text: String,
    val cover: String,
    val bvid: String,
    val aid: Long,
    val title: String,
    /** 转发来源描述（如「转发了 @某某 的视频」），无则空。 */
    val extra: String,
    val timeText: String,
    /** 是否视频动态（可播放）。 */
    val isVideo: Boolean,
)

/** 一页动态。[offset] 是下一页游标，[hasMore] 为 false 时没有更多。 */
data class BiliDynamicPage(
    val items: List<BiliDynamicItem>,
    val offset: String,
    val hasMore: Boolean,
)

/**
 * 一键四连的一步结果。[name] 是步骤名，[message] 是失败原因（成功为空）。
 * 四步互相独立，一步失败不影响后面的步骤执行。
 */
data class BiliQuadStep(
    val name: String,
    val ok: Boolean,
    val message: String = "",
)

/**
 * 应用内观看投稿用的播放直链：DASH 画面轨 + 声音轨。
 * 没有 DASH 时回退 durl 的整条 MP4，此时 [audioUrl] 为空（画面自带音轨）。
 */
data class BiliVideoStreams(
    val videoUrl: String,
    val videoMime: String,
    val audioUrl: String,
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
        const val SPACE_ARCHIVE_CURSOR_URL = "https://app.bilibili.com/x/v2/space/archive/cursor"
        const val PAGELIST_URL = "https://api.bilibili.com/x/player/pagelist"
        const val FAV_CREATED_LIST_ALL = "https://api.bilibili.com/x/v3/fav/folder/created/list-all"
        const val FAV_CREATED_LIST = "https://api.bilibili.com/x/v3/fav/folder/created/list"
        const val FAV_COLLECTED_LIST = "https://api.bilibili.com/x/v3/fav/folder/collected/list"
        const val FAV_FOLDER_INFO = "https://api.bilibili.com/x/v3/fav/folder/info"
        const val FAV_RESOURCE_LIST = "https://api.bilibili.com/x/v3/fav/resource/list"
        const val QR_GENERATE_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        const val QR_POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"

        const val CARD_URL = "https://api.bilibili.com/x/web-interface/card"
        const val RELATION_STAT_URL = "https://api.bilibili.com/x/relation/stat"
        const val DYNAMIC_FEED_SPACE_URL =
            "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space"
        const val DYNAMIC_FEED_CREATE_URL = "https://api.bilibili.com/x/dynamic/feed/create"
        const val REPLY_URL = "https://api.bilibili.com/x/v2/reply"
        const val REPLY_ADD_URL = "https://api.bilibili.com/x/v2/reply/add"
        const val REPLY_ACTION_URL = "https://api.bilibili.com/x/v2/reply/action"
        const val ARCHIVE_LIKE_URL = "https://api.bilibili.com/x/web-interface/archive/like"
        const val COIN_ADD_URL = "https://api.bilibili.com/x/web-interface/coin/add"
        const val FAV_RESOURCE_DEAL_URL = "https://api.bilibili.com/x/v3/fav/resource/deal"

        const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val WEB_TICKET_UA =
            "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0"

        /** App 端接口（`app.bilibili.com`）的 UA、加签密钥与统计参数，与 PiliPlus 一致。 */
        const val APP_UA =
            "Mozilla/5.0 BiliDroid/8.43.0 (bbcallen@gmail.com) os/android model/android " +
                "mobi_app/android build/8430300 channel/master innerVer/8430300 osVer/15 network/2"
        const val APP_KEY = "dfca71928277209b"
        const val APP_SEC = "b5475a8825547a4fc26c7d518eaaa02e"
        const val APP_STATISTICS = """{"appId":1,"platform":3,"version":"8.43.0","abtest":""}"""
        const val APP_VERSION = "8.43.0"
        const val APP_BUILD = "8430300"

        const val REFERER = "https://www.bilibili.com"
        const val QR_REFERER = "https://passport.bilibili.com/login"

        const val DASH_DOLBY = 1 shl 8
        const val WBI_CACHE_MS = 10 * 60 * 1000L
        const val ANON_CACHE_MS = 60 * 60 * 1000L
        const val MAX_RESPONSE_BYTES = 8L * 1024L * 1024L

        /** 风控拉黑（-412）后重试前的等待时长，给风控一点冷却时间。 */
        const val BAN_RETRY_DELAY_MS = 1500L

        /** 首页首跳：真实浏览器加载 B 站第一件事就是 GET 首页拿 Set-Cookie。 */
        const val HOME_URL = "https://www.bilibili.com/"

        /**
         * 相邻两次 API 请求的最小间隔（毫秒）+ 随机抖动上限。
         * B 站 412 是按 IP 的速率限制，同一帧并发好几个请求最容易触发；
         * 真实用户浏览是逐个串行的，这里复刻那种节奏。
         */
        const val MIN_REQUEST_GAP_MS = 250L
        const val REQUEST_JITTER_MS = 200L

        /**
         * 匿名设备指纹 cookie 键。登出（[clearLogin]）或被 -412 拉黑时要一并作废：
         * 这些值由 B 站按会话签发，过期/作废后继续携带会被判成异常客户端。
         */
        val FINGERPRINT_KEYS = setOf(
            "buvid3", "buvid4", "buvid_fp", "buvid_fp_plain", "b_lsid", "_uuid", "uuid_tt_10",
        )
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

    /**
     * 节流锁：同时只能有一个请求在等待放行，避免并发协程把间隔"叠"到同一时刻。
     * 配合 [lastRequestAt] 保证相邻两次请求至少隔 [MIN_REQUEST_GAP_MS] + 抖动。
     */
    private val throttleLock = Mutex()

    @Volatile
    private var lastRequestAt = 0L

    /**
     * 请求节流：强制相邻两次 API 请求间隔 [MIN_REQUEST_GAP_MS] + 一段随机抖动。
     *
     * B 站 412 是**按 IP** 的速率限制（yt-dlp #5083），同一帧并发 card/stat/arc/dynamics
     * 几个请求最容易被判定成脚本。真实用户浏览是逐个串行且间隔不齐的，
     * 这里复刻那种节奏：既限最小间隔，又加抖动让间隔看起来不机械。
     */
    private suspend fun throttle() {
        throttleLock.withLock {
            val now = System.currentTimeMillis()
            val jitter = java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(0L, REQUEST_JITTER_MS + 1)
            val wait = (lastRequestAt + MIN_REQUEST_GAP_MS + jitter) - now
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

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
            // 指纹与登录态是一套签发的，登出后 B 站通常已作废旧指纹；
            // 留着继续用会被判异常客户端（-412），一并清掉让下次重新换一套。
            jar.keys.removeAll(FINGERPRINT_KEYS)
        }
        anonCookies = null
        anonCachedAt = 0L
    }

    /**
     * 作废当前匿名指纹并清空缓存：被风控（-412）拉黑后调用，
     * 让下一次 [cookieHeader] 重新去换一套新指纹，而不是继续带"烧掉"的旧值。
     */
    private fun invalidateFingerprint() {
        anonCookies = null
        anonCachedAt = 0L
        synchronized(jarLock) { jar.keys.removeAll(FINGERPRINT_KEYS) }
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
     * 组装 Cookie 头。
     *
     * `buvid3` 是 B 站风控的设备指纹：空间投稿（`x/space/wbi/arc/search`）等接口缺它就判成
     * 异常客户端、返回 -403。登录态只存了 SESSDATA/bili_jct/DedeUserID，指纹接口的 buvid3
     * 在 JSON body 里返回、不进 jar，所以**登录用户反而常年缺 buvid3**。这里只要 buvid3 缺失
     * 就换一次匿名指纹补齐，登录 cookie 优先级更高，不会被匿名值覆盖。
     */
    private suspend fun cookieHeader(): String {
        val stored = cookies()
        if (!stored["buvid3"].isNullOrBlank()) {
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
        // 匿名指纹在前、登录态在后：同名 key 以登录值为准
        return (anon + stored).entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 真实浏览器首跳：GET B 站首页，从 `Set-Cookie` 里拿 `buvid3` / `b_lsid` / `_uuid` 等。
     *
     * 这是第三方客户端与"直接调指纹 SPI 接口"的核心差别：真实用户打开 B 站时浏览器
     * 第一个请求就是首页，服务端据此下发一整套互相咬合的指纹 cookie。匿名 cookie
     * 过期极快（yt-dlp #17605），所以每次都重新首跳拿最新的，而不是复用旧值。
     */
    private suspend fun fetchHomeCookies() {
        runCatching {
            http.newCall(
                Request.Builder()
                    .url(HOME_URL.toHttpUrl())
                    .header("User-Agent", WEB_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                    .get()
                    .build(),
            ).execute().use { response ->
                mergeSetCookies(response.headers("Set-Cookie"))
                // 只需要 Set-Cookie，body 读一点就够（不读完也得让连接收尾）
                response.body?.byteStream()?.readBytesLimited(64L * 1024L)
            }
        }.onFailure {
            NPLogger.w(TAG, "首页首跳取 cookie 失败（忽略，后续用 SPI 兜底）：${it.message}")
        }
    }

    /**
     * 取一套匿名设备指纹：先首页首跳拿 `Set-Cookie`，再用指纹 SPI 接口补齐
     * `buvid4` / `buvid_fp` 这些首页不给的字段。两步都失败时返回空表。
     */
    private suspend fun fetchAnonCookies(): Map<String, String> {
        fetchHomeCookies()

        val text = http.newCall(
            Request.Builder()
                .url(FINGERPRINT_URL.toHttpUrl())
                // 与首页首跳、以及所有 API 调用统一用桌面 WEB_UA：
                // 指纹 cookie（b_lsid/_uuid/buvid3）必须来自同一个 UA 的会话，
                // 混用桌面/手机 UA 会让这套值互相咬不上，反而像脚本。
                .header("User-Agent", WEB_UA)
                .header("Referer", REFERER)
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

        // SPI body 的字段（buvid3/buvid4/buvid_fp…）是当前会话的权威值，直接覆盖 jar 里
        // 首页首跳可能给的同名 cookie；首页独有的（b_lsid/_uuid/uuid_tt_10）已被
        // mergeSetCookies 并入 jar，这里不动它们。
        if (map.isNotEmpty()) {
            synchronized(jarLock) {
                map.forEach { (k, v) -> if (v.isNotBlank()) jar[k] = v }
            }
        }
        // 返回值与 jar 里的指纹保持一致，cookieHeader 拼接时才不会出现两套值
        return cookies().filterKeys { it in FINGERPRINT_KEYS }
    }

    private suspend fun executeJson(
        url: HttpUrl,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = WEB_UA,
        referer: String = REFERER,
    ): JSONObject {
        var attempt = 0
        while (true) {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", referer)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            cookieHeader().takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            headers.forEach { (k, v) -> builder.header(k, v) }

            throttle()

            val (text, httpCode) = http.newCall(builder.get().build()).execute().use { response ->
                mergeSetCookies(response.headers("Set-Cookie"))
                val bytes = response.body?.byteStream()?.readBytesLimited(MAX_RESPONSE_BYTES) ?: ByteArray(0)
                String(bytes, Charsets.UTF_8) to response.code
            }

            // 风控拉黑（-412）：作废当前指纹，稍等后换新指纹重试一次
            if (isBanned(httpCode, text)) {
                if (attempt == 0) {
                    attempt++
                    NPLogger.w(TAG, "请求被风控(-412)，刷新指纹后重试：${url.encodedPath}")
                    invalidateFingerprint()
                    delay(BAN_RETRY_DELAY_MS)
                    continue
                }
                throw BiliException("请求被风控拦截(-412)，请稍后再试", isBan = true)
            }

            if (httpCode !in 200..299) {
                throw BiliException("HTTP $httpCode: ${text.take(200)}")
            }
            if (text.isBlank()) throw BiliException("B 站返回空响应")
            return runCatching { JSONObject(text) }
                .getOrElse { throw BiliException("B 站响应不是合法 JSON") }
        }
    }

    /** 是否被风控拉黑：HTTP 412，或响应体 code 为 -412（message 含 request was banned）。 */
    private fun isBanned(httpCode: Int, text: String): Boolean {
        if (httpCode == 412) return true
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return false
        val code = runCatching { JSONObject(trimmed).optInt("code", 0) }.getOrDefault(0)
        return code == -412
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

    /**
     * App 端接口请求：`app.bilibili.com` 不吃网页那套 Wbi 风控，改用 `appkey` + `ts` + `sign`。
     *
     * 网页的 `x/space/wbi/arc/search` 对未登录 / 第三方客户端常年回 -352 风控，
     * 空间投稿列表因此整页空掉；App 端接口匿名就能正常翻页，所以这类接口走这里。
     */
    private suspend fun getJsonApp(url: String, params: Map<String, String>): JSONObject {
        val query = appQuery(appSign(params).toSortedMap())
        return executeJson(
            "$url?$query".toHttpUrl(),
            headers = mapOf("bili-http-engine" to "cronet"),
            userAgent = APP_UA,
            referer = "",
        )
    }

    /**
     * App 端加签：`sign` = MD5(按 key 升序的 `k=v` 串 + appsec)。
     * 与 PiliPlus 的 AppSign 对齐：空值只写 key 不写 `=`，键与值都按百分号编码。
     */
    private fun appSign(params: Map<String, String>): Map<String, String> {
        val signed = LinkedHashMap(params)
        signed["appkey"] = APP_KEY
        signed["ts"] = (System.currentTimeMillis() / 1000L).toString()
        signed["sign"] = BiliWbi.md5(appQuery(signed.toSortedMap()) + APP_SEC)
        return signed
    }

    /** 自己拼 query：值统一百分号编码，顺序由调用方给的 Map 决定（签名要求 key 升序）。 */
    private fun appQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            if (v.isEmpty()) appEncode(k) else "${appEncode(k)}=${appEncode(v)}"
        }

    private fun appEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * 发起 POST 表单请求（B 站写操作统一用这个）。
     *
     * 写操作要求带 csrf（= bili_jct cookie）且 form 里也要传同名字段；
     * 部分接口还要求 Wbi 加签，这里一律按 Wbi 组装（多加签名无副作用）。
     */
    private suspend fun postForm(url: String, params: Map<String, String>): JSONObject {
        var attempt = 0
        while (true) {
            val csrf = cookies()["bili_jct"].orEmpty()
            val signed = if (csrf.isBlank()) {
                params
            } else {
                BiliWbi.sign(params + ("csrf" to csrf), mixinKey())
            }
            val builder = Request.Builder()
                .url(url.toHttpUrl())
                .header("User-Agent", WEB_UA)
                .header("Referer", REFERER)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            cookieHeader().takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }

            val form = FormBody.Builder()
            signed.forEach { (k, v) -> form.add(k, v) }
            if (csrf.isBlank()) form.add("csrf", "")

            throttle()

            val (text, httpCode) = http.newCall(builder.post(form.build()).build()).execute().use { response ->
                mergeSetCookies(response.headers("Set-Cookie"))
                val bytes = response.body?.byteStream()?.readBytesLimited(MAX_RESPONSE_BYTES) ?: ByteArray(0)
                String(bytes, Charsets.UTF_8) to response.code
            }

            if (isBanned(httpCode, text)) {
                if (attempt == 0) {
                    attempt++
                    NPLogger.w(TAG, "写操作被风控(-412)，刷新指纹后重试：${url.toHttpUrl().encodedPath}")
                    invalidateFingerprint()
                    delay(BAN_RETRY_DELAY_MS)
                    continue
                }
                throw BiliException("操作被风控拦截(-412)，请稍后再试", isBan = true)
            }

            if (httpCode !in 200..299) {
                throw BiliException("HTTP $httpCode: ${text.take(200)}")
            }
            if (text.isBlank()) throw BiliException("B 站返回空响应")
            return runCatching { JSONObject(text) }
                .getOrElse { throw BiliException("B 站响应不是合法 JSON") }
        }
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
            dynamicId = data.optLong("dynamic"),
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
                    upperMid = o.optLong("mid"),
                )
            }
            val numPages = data.optInt("numPages", 1)
            items to (page < numPages)
        }

    /**
     * UP 主空间投稿（App 端 `x/v2/space/archive/cursor`）。
     *
     * 不走网页的 `x/space/wbi/arc/search`：那个接口对未登录 / 第三方客户端常年回
     * -352 风控，投稿列表会整页空掉。App 端接口匿名可用，翻页靠 [cursor]：
     * 传上一页最后一条的 aid（条目里的 `param`），首页传空串。
     * 返回的条目复用 [BiliFavItem]，`upper*` 一律填这位 UP 主自己。
     */
    suspend fun upVideos(mid: Long, cursor: String = "", pageSize: Int = 20): BiliUpPage =
        withContext(Dispatchers.IO) {
            val params = mutableMapOf(
                "vmid" to mid.toString(),
                "ps" to pageSize.toString(),
                "qn" to "80",
                "order" to "pubdate",
                "mobi_app" to "android",
                "platform" to "android",
                "channel" to "master",
                "build" to APP_BUILD,
                "version" to APP_VERSION,
                "c_locale" to "zh_CN",
                "s_locale" to "zh_CN",
                "statistics" to APP_STATISTICS,
            )
            if (cursor.isNotBlank()) params["aid"] = cursor
            val data = getJsonApp(SPACE_ARCHIVE_CURSOR_URL, params)
                .requireData("space/archive/cursor")

            val arr = data.optJSONArray("item") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val bvid = o.optString("bvid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                BiliFavItem(
                    type = 2,
                    id = o.optLong("param", o.optLong("aid")),
                    bvid = bvid,
                    title = o.optString("title"),
                    cover = httpsUrl(o.optString("cover")),
                    durationSec = o.optInt("duration").coerceAtLeast(0),
                    upperName = o.optString("author"),
                    upperMid = mid,
                )
            }
            val nextCursor = items.lastOrNull()?.id?.takeIf { it > 0L }?.toString().orEmpty()
            BiliUpPage(
                items = items,
                hasMore = data.optBoolean("has_next", false) && nextCursor.isNotBlank(),
                nextCursor = nextCursor,
            )
        }

    // ---------- UP 主资料卡 ----------

    /**
     * UP 主资料卡：关注 / 粉丝来自 `x/relation/stat`，获赞与大会员来自 `x/web-interface/card`。
     * 两个接口都不强制 Wbi；风控或未登录时由 [requireData] 抛错，调用方兜底。
     * ipLocation 只有 card 接口在部分账号上会给，拿不到时为空串。
     */
    suspend fun upCard(mid: Long): BiliUpCard = withContext(Dispatchers.IO) {
        val stat = getJson(RELATION_STAT_URL, mapOf("vmid" to mid.toString()))
            .requireData("relation/stat")
        val cardData = getJson(CARD_URL, mapOf("mid" to mid.toString(), "photo" to "false"))
            .optJSONObject("data") ?: JSONObject()
        val card = cardData.optJSONObject("card") ?: JSONObject()
        val vip = cardData.optJSONObject("vip") ?: JSONObject()
        // card 接口里昵称字段是 uname（个别版本也给 name），两个都兜一下
        val name = card.optString("uname").ifBlank { card.optString("name") }
        // 获赞在 data.like_num（个别版本是 likes），粉丝以 relation/stat 为准
        val likeNum = cardData.optLong("like_num", cardData.optLong("likes", 0L))
        // 大会员：vip.type > 0；个别账号 vip 在 card.VipInfo 里
        val vipType = vip.optInt("type", 0).takeIf { it > 0 }
            ?: card.optJSONObject("VipInfo")?.optInt("type", 0) ?: 0
        BiliUpCard(
            mid = mid,
            name = name,
            face = httpsUrl(card.optString("face")),
            sign = card.optString("sign"),
            following = stat.optLong("following"),
            follower = stat.optLong("follower"),
            likeNum = likeNum,
            vip = vipType > 0,
            ipLocation = cardData.optString("ip_location").ifBlank { card.optString("ip_location") },
        )
    }

    // ---------- 动态流 ----------

    /**
     * UP 主动态流（`x/polymer/web-dynamic/v1/feed/space`）。
     * [offset] 传上一页返回的游标，首页传空串。返回条目里视频动态带 bvid 可播放。
     */
    suspend fun upDynamics(mid: Long, offset: String = ""): BiliDynamicPage =
        withContext(Dispatchers.IO) {
            val params = mutableMapOf(
                "host_mid" to mid.toString(),
                "offset" to offset,
                "features" to "itemOpusStyle",
            )
            val data = getJsonWbi(DYNAMIC_FEED_SPACE_URL, params)
                .requireData("dynamic/feed/space")
            val arr = data.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i ->
                parseDynamicItem(arr.optJSONObject(i))
            }
            BiliDynamicPage(
                items = items,
                offset = data.optString("offset"),
                hasMore = data.optBoolean("has_more", false),
            )
        }

    /** 解析一条动态；非视频动态也会返回（只是 [BiliDynamicItem.isVideo] 为 false，不可播放）。 */
    private fun parseDynamicItem(o: JSONObject?): BiliDynamicItem? {
        val item = o ?: return null
        val id = item.optString("id_str").ifBlank { item.optString("id") }
        val modules = item.optJSONObject("modules") ?: JSONObject()
        val author = modules.optJSONObject("module_author") ?: JSONObject()
        val dynamic = modules.optJSONObject("module_dynamic") ?: JSONObject()
        val desc = dynamic.optJSONObject("desc") ?: JSONObject()
        val major = dynamic.optJSONObject("major") ?: JSONObject()
        val archive = major.optJSONObject("archive") ?: JSONObject()

        // 正文：纯文字 desc.text 优先，视频动态回退到标题 + 简介
        val text = desc.optString("text").ifBlank {
            major.optJSONObject("opus")
                ?.optJSONObject("summary")?.optString("text").orEmpty()
        }
        val bvid = archive.optString("bvid").ifBlank { archive.optString("bv_id") }
        val aid = archive.optLong("aid", archive.optLong("id", 0L))
        val title = archive.optString("title").ifBlank {
            major.optJSONObject("opus")?.optString("title").orEmpty()
        }
        val isVideo = item.optString("type").contains("AV") || bvid.isNotBlank()
        val extra = if (item.optString("type").contains("REPOST")) "转发" else ""
        return BiliDynamicItem(
            id = id,
            text = text,
            cover = httpsUrl(archive.optString("cover").ifBlank {
                major.optJSONObject("opus")?.optJSONArray("pics")
                    ?.optJSONObject(0)?.optString("url").orEmpty()
            }),
            bvid = bvid,
            aid = aid,
            title = title,
            extra = extra,
            timeText = author.optString("pub_time"),
            isVideo = isVideo && bvid.isNotBlank(),
        )
    }

    // ---------- 评论 ----------

    /**
     * 视频评论区一页（`x/v2/reply`，type=1 表示稿件）。
     * [root] > 0 时取该楼中楼的回复列表，否则取主楼。
     */
    suspend fun videoComments(
        aid: Long,
        page: Int = 1,
        pageSize: Int = 20,
        root: Long = 0L,
    ): Pair<List<Comment>, Boolean> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "type" to "1",
            "oid" to aid.toString(),
            "ps" to pageSize.toString(),
            "sort" to "0",
        )
        if (root > 0L) {
            params["root"] = root.toString()
            params["pn"] = page.toString()
        } else {
            params["pn"] = page.toString()
        }
        val data = getJson(REPLY_URL, params).requireData("reply")
        val arr = data.optJSONArray("replies")
            ?: data.optJSONArray("root_replies")
            ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i -> parseReply(arr.optJSONObject(i)) }
        val pageInfo = data.optJSONObject("page") ?: JSONObject()
        val count = pageInfo.optInt("count", 0)
        val hasMore = if (root > 0L) {
            data.optBoolean("has_more", false)
        } else {
            page * pageSize < count
        }
        items to hasMore
    }

    /** 解析一条评论；`replies` 是楼中楼预览，只取前几条塞进 [Comment.reply]。 */
    private fun parseReply(o: JSONObject?): Comment? {
        val r = o ?: return null
        val member = r.optJSONObject("member") ?: JSONObject()
        val content = r.optJSONObject("content") ?: JSONObject()
        val rpid = r.optLong("rpid")
        if (rpid <= 0L) return null
        val sub = r.optJSONArray("replies") ?: JSONArray()
        val subPreview = (0 until sub.length()).mapNotNull { i ->
            val s = sub.optJSONObject(i) ?: return@mapNotNull null
            val sm = s.optJSONObject("member") ?: JSONObject()
            val sc = s.optJSONObject("content") ?: JSONObject()
            "${sm.optString("uname")}：${sc.optString("message")}"
        }.joinToString("；")
        val control = r.optJSONObject("reply_control") ?: JSONObject()
        return Comment(
            id = rpid.toString(),
            user = member.optString("uname"),
            avatar = httpsUrl(member.optString("avatar")),
            content = content.optString("message"),
            time = control.optString("subs").ifBlank { formatEpochSeconds(r.optLong("ctime")) },
            likeCount = r.optInt("like"),
            isHot = r.optLong("like", 0L) >= 1000L,
            reply = subPreview,
            likeAction = r.optInt("like_action", 0),
            dislikeCount = r.optInt("hate", 0),
            replyCount = r.optInt("rcount", 0),
        )
    }

    /** 秒级时间戳 → 「MM-dd HH:mm」。跨年再带上年份，够评论区辨认先后。 */
    private fun formatEpochSeconds(seconds: Long): String {
        if (seconds <= 0L) return ""
        val fmt = java.text.SimpleDateFormat(
            if (System.currentTimeMillis() / 1000L - seconds > 365L * 24 * 3600) "yyyy-MM-dd HH:mm"
            else "MM-dd HH:mm",
            java.util.Locale.CHINA,
        )
        return runCatching { fmt.format(java.util.Date(seconds * 1000L)) }.getOrDefault("")
    }

    /**
     * 发表 / 回复评论（`x/v2/reply/add`）。
     * [root] 是楼中楼根评论 rpid，[parent] 是被回复的那条 rpid，直接发主楼时两者都传 0。
     */
    suspend fun sendReply(aid: Long, message: String, root: Long = 0L, parent: Long = 0L): Boolean =
        withContext(Dispatchers.IO) {
            val params = mutableMapOf(
                "type" to "1",
                "oid" to aid.toString(),
                "message" to message,
                "plat" to "1",
            )
            if (root > 0L) params["root"] = root.toString()
            if (parent > 0L) params["parent"] = parent.toString()
            val json = postForm(REPLY_ADD_URL, params)
            json.requireData("reply/add")
            true
        }

    /**
     * 评论点赞 / 点踩（`x/v2/reply/action`）。
     * [action]：1=点赞、0=取消赞、2=踩、-2=取消踩。
     */
    suspend fun replyAction(aid: Long, rpid: Long, action: Int): Boolean =
        withContext(Dispatchers.IO) {
            val json = postForm(
                REPLY_ACTION_URL,
                mapOf(
                    "type" to "1",
                    "oid" to aid.toString(),
                    "rpid" to rpid.toString(),
                    "action" to action.toString(),
                ),
            )
            json.requireData("reply/action")
            true
        }

    // ---------- 一键四连 ----------

    /** 稿件点赞（`x/web-interface/archive/like`）。 */
    suspend fun archiveLike(aid: Long): Boolean = withContext(Dispatchers.IO) {
        postForm(
            ARCHIVE_LIKE_URL,
            mapOf("aid" to aid.toString(), "like" to "1", "eab_x" to "2", "ramval" to "0"),
        ).requireData("archive/like")
        true
    }

    /** 投币（`x/web-interface/coin/add`），[multiply] 只能是 1 或 2（1 币 / 2 币）。 */
    suspend fun coinAdd(aid: Long, multiply: Int = 1): Boolean = withContext(Dispatchers.IO) {
        val mult = multiply.coerceIn(1, 2)
        postForm(
            COIN_ADD_URL,
            mapOf(
                "aid" to aid.toString(),
                "multiply" to mult.toString(),
                "select_like" to "0",
                "cross_domain" to "true",
            ),
        ).requireData("coin/add")
        true
    }

    /** 收藏到指定收藏夹（`x/v3/fav/resource/deal`），[mediaIds] 是收藏夹 media_id 列表。 */
    suspend fun favDeal(aid: Long, mediaIds: List<Long>): Boolean = withContext(Dispatchers.IO) {
        postForm(
            FAV_RESOURCE_DEAL_URL,
            mapOf(
                "rid" to aid.toString(),
                "type" to "2",
                "add_media_ids" to mediaIds.joinToString(","),
                "platform" to "web",
            ),
        ).requireData("fav/resource/deal")
        true
    }

    /**
     * 转发动态（`x/dynamic/feed/create`）。
     * [dynamicId] 是原视频的动态 id（`view` 接口的 `data.dynamic`）；拿不到就转不了，返回 false。
     */
    suspend fun repostDynamic(
        dynamicId: Long,
        upMid: Long,
        aid: Long,
        content: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (dynamicId <= 0L) return@withContext false
        val dynReq = JSONObject()
            .put("dyn_type", 8)
            .put("uid", upMid)
            .put("rid", aid)
            .put("src", dynamicId)
            .put("content", content)
            .put("extension", JSONObject().put("emoji_size", 0).toString())
        postForm(
            DYNAMIC_FEED_CREATE_URL,
            mapOf("dyn_req" to dynReq.toString()),
        ).requireData("dynamic/feed/create")
        true
    }

    /**
     * 取音频直链。[audioQualityId] > 0 时优先匹配该 DASH 音质（30216/30232/30280），
     * 匹配不到回退最高带宽；无 DASH 时回退 `durl` 的渐进式 MP4。
     */
    suspend fun audioUrl(bvid: String, cid: String, audioQualityId: Int = 0): String = withContext(Dispatchers.IO) {
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
        val items = (0 until (dashAudio?.length() ?: 0))
            .mapNotNull { dashAudio?.optJSONObject(it) }
            .filter { it.optString("baseUrl", it.optString("base_url")).isNotBlank() }
        val best = items.firstOrNull { audioQualityId > 0 && it.optInt("id", 0) == audioQualityId }
            ?: items.maxByOrNull { it.optLong("bandwidth", 0L) }
        best?.let { return@withContext it.optString("baseUrl", it.optString("base_url")) }

        data.optJSONArray("durl")?.optJSONObject(0)?.optString("url").orEmpty()
    }

    /**
     * 取整条视频的画面 + 声音直链（应用内观看投稿用）。
     * 画面取 DASH 里 id 最高的一档（未登录一般给到 360P / 480P，属正常），
     * 声音取最高带宽；完全没有 DASH 时回退 durl 的整条 MP4（自带音轨）。
     * 拿不到（大会员专属画质、地区限制等）返回 null。
     */
    suspend fun videoStreams(bvid: String, cid: String): BiliVideoStreams? = withContext(Dispatchers.IO) {
        val resolvedCid = cid.ifBlank { videoPages(bvid).firstOrNull()?.cid?.toString().orEmpty() }
        if (resolvedCid.isBlank()) return@withContext null
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

        val dash = data.optJSONObject("dash")
        val bestVideo = dash?.videoList("video")?.maxByOrNull { it.optInt("id", 0) }
        if (bestVideo != null) {
            val bestAudio = dash.videoList("audio").maxByOrNull { it.optLong("bandwidth", 0L) }
            return@withContext BiliVideoStreams(
                videoUrl = bestVideo.baseUrl(),
                videoMime = bestVideo.optString("mimeType", bestVideo.optString("mime_type")),
                audioUrl = bestAudio?.baseUrl().orEmpty(),
            )
        }

        data.optJSONArray("durl")?.optJSONObject(0)?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?.let { BiliVideoStreams(videoUrl = it, videoMime = "video/mp4", audioUrl = "") }
    }

    private fun JSONObject.videoList(key: String): List<JSONObject> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .filter { it.baseUrl().isNotBlank() }
    }

    private fun JSONObject.baseUrl(): String = optString("baseUrl", optString("base_url"))

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
                    upperMid = m.optJSONObject("upper")?.optLong("mid") ?: 0L,
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
