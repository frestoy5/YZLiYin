package top.aryun.yzliyin.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import top.aryun.yzliyin.core.model.Source

/**
 * 应用会话与设置的持久化存储。
 *
 * 保存内容包括：各数据源的登录 Cookie 与昵称、外观模式（跟随系统/白天/黑夜）、
 * 播放行为偏好（音质、自动连播、歌词滚动、音效）等。
 * 使用 [SharedPreferences]，避免引入额外依赖。
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("yzliyin_session", Context.MODE_PRIVATE)

    // ---------- 各源登录态 ----------
    // 每个源一组 <source.id>_cookies / _name / _avatar，值语义见 Source。

    /** 指定源的登录 Cookie（JSON 对象字符串）。 */
    private fun cookiesRaw(source: Source): String =
        prefs.getString("${source.id}_cookies", "") ?: ""

    fun cookieMap(source: Source): Map<String, String> = runCatching {
        val root = JSONObject(cookiesRaw(source).ifEmpty { "{}" })
        val out = LinkedHashMap<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = root.optString(k, "")
        }
        out
    }.getOrDefault(emptyMap())

    fun saveCookies(source: Source, cookies: Map<String, String>) {
        val json = JSONObject()
        cookies.forEach { (k, v) -> if (k.isNotBlank()) json.put(k, v) }
        prefs.edit { putString("${source.id}_cookies", json.toString()) }
    }

    fun displayName(source: Source): String =
        prefs.getString("${source.id}_name", "") ?: ""

    fun saveDisplayName(source: Source, name: String) =
        prefs.edit { putString("${source.id}_name", name) }

    fun avatar(source: Source): String =
        prefs.getString("${source.id}_avatar", "") ?: ""

    fun saveAvatar(source: Source, url: String) =
        prefs.edit { putString("${source.id}_avatar", url) }

    /** 清空该源的登录态（保留设备标识类 Cookie，避免下次重新注册设备）。 */
    fun clearSession(source: Source) {
        val keep = cookieMap(source).filterKeys { it !in LOGIN_COOKIE_KEYS }
        if (keep.isEmpty()) {
            prefs.edit {
                remove("${source.id}_cookies")
                remove("${source.id}_name")
                remove("${source.id}_avatar")
            }
        } else {
            saveCookies(source, keep)
            prefs.edit {
                remove("${source.id}_name")
                remove("${source.id}_avatar")
            }
        }
    }

    fun isLoggedIn(source: Source): Boolean = when (source) {
        Source.NETEASE -> !cookieMap(source)["MUSIC_U"].isNullOrBlank()
        Source.KUGOU -> {
            val c = cookieMap(source)
            !c["token"].isNullOrBlank() && c["userid"] != null && c["userid"] != "0"
        }
        Source.BILIBILI -> !cookieMap(source)["SESSDATA"].isNullOrBlank()
    }

    // ---------- 网易云（以下为通用实现的特例，pref key 与历史版本一致） ----------
    var neteaseName: String
        get() = displayName(Source.NETEASE)
        set(value) = saveDisplayName(Source.NETEASE, value)

    var neteaseAvatar: String
        get() = avatar(Source.NETEASE)
        set(value) = saveAvatar(Source.NETEASE, value)

    /** 网易云会员（黑胶 VIP）。 */
    var neteaseVip: Boolean
        get() = prefs.getBoolean(KEY_NETEASE_VIP, false)
        set(value) = prefs.edit { putBoolean(KEY_NETEASE_VIP, value) }

    /** 网易云登录 Cookie（MUSIC_U/__csrf 等），JSON 字符串。 */
    var neteaseCookies: String
        get() = cookiesRaw(Source.NETEASE)
        set(value) = prefs.edit { putString("netease_cookies", value) }

    fun neteaseCookieMap(): Map<String, String> = cookieMap(Source.NETEASE)

    fun saveNeteaseCookies(cookies: Map<String, String>) =
        saveCookies(Source.NETEASE, cookies)

    fun clearNeteaseCookies() {
        neteaseCookies = ""
        neteaseName = ""
        neteaseAvatar = ""
        neteaseVip = false
    }

    val isNeteaseLoggedIn: Boolean
        get() = isLoggedIn(Source.NETEASE)

    /** 当前登录的网易云 userId（缓存，未登录为 0）。 */
    var neteaseUserId: Long
        get() = prefs.getLong(KEY_NETEASE_UID, 0L)
        set(value) = prefs.edit { putLong(KEY_NETEASE_UID, value) }

    /** 已缓存的最近播放曲目 id，离线时兜底展示。 */
    var recentSongIds: String
        get() = prefs.getString(KEY_RECENT_SONG_IDS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_RECENT_SONG_IDS, value) }

    // ---------- 外观 ----------
    /** 外观模式：system（跟随系统深色）/ light（白天）/ dark（黑夜）。 */
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value) }

    // ---------- 播放行为 ----------
    /** 播放音质：standard / higher / exhigh（默认）/ lossless / hires。 */
    var playQuality: String
        get() = prefs.getString(KEY_PLAY_QUALITY, "exhigh") ?: "exhigh"
        set(value) = prefs.edit { putString(KEY_PLAY_QUALITY, value) }

    /** 列表播完是否自动连播下一首。 */
    var autoPlayNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PLAY_NEXT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PLAY_NEXT, value) }

    /** 播放页歌词是否自动滚动到当前行。 */
    var lyricAutoScroll: Boolean
        get() = prefs.getBoolean(KEY_LYRIC_AUTO_SCROLL, true)
        set(value) = prefs.edit { putBoolean(KEY_LYRIC_AUTO_SCROLL, value) }

    /** 低频增强音效开关。 */
    var audioEffect: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_EFFECT, false)
        set(value) = prefs.edit { putBoolean(KEY_AUDIO_EFFECT, value) }

    companion object {
        /** 登出时清除的凭证类 Cookie；设备标识类（guid/mid/dfid 等）保留。 */
        private val LOGIN_COOKIE_KEYS = setOf(
            "MUSIC_U", "__csrf", "__remember_me",
            "token", "userid", "vip_token", "vip_type",
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5",
        )

        private const val KEY_NETEASE_VIP = "netease_vip"
        private const val KEY_NETEASE_UID = "netease_uid"
        private const val KEY_RECENT_SONG_IDS = "recent_song_ids"

        private const val KEY_THEME_MODE = "theme_mode"

        private const val KEY_PLAY_QUALITY = "play_quality"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_LYRIC_AUTO_SCROLL = "lyric_auto_scroll"
        private const val KEY_AUDIO_EFFECT = "audio_effect"
    }
}
