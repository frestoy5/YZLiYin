package top.aryun.yzliyin.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * 应用会话与设置的持久化存储。
 *
 * 保存内容包括：网易云登录 Cookie（账号信息）、播放行为偏好（音质、自动连播、
 * 歌词滚动、音效）等。数据源仅网易云音乐，不再保留音源切换与 API 地址配置。
 * 使用 [SharedPreferences]，避免引入额外依赖。
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("yzliyin_session", Context.MODE_PRIVATE)

    // ---------- 网易云登录态 ----------
    var neteaseName: String
        get() = prefs.getString(KEY_NETEASE_NAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_NETEASE_NAME, value) }

    var neteaseAvatar: String
        get() = prefs.getString(KEY_NETEASE_AVATAR, "") ?: ""
        set(value) = prefs.edit { putString(KEY_NETEASE_AVATAR, value) }

    /** 网易云会员（黑胶 VIP）。 */
    var neteaseVip: Boolean
        get() = prefs.getBoolean(KEY_NETEASE_VIP, false)
        set(value) = prefs.edit { putBoolean(KEY_NETEASE_VIP, value) }

    /** 网易云登录 Cookie（MUSIC_U/__csrf 等），JSON 字符串。 */
    var neteaseCookies: String
        get() = prefs.getString(KEY_NETEASE_COOKIES, "") ?: ""
        set(value) = prefs.edit { putString(KEY_NETEASE_COOKIES, value) }

    fun neteaseCookieMap(): Map<String, String> = runCatching {
        val root = JSONObject(neteaseCookies.ifEmpty { "{}" })
        val out = LinkedHashMap<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = root.optString(k, "")
        }
        out
    }.getOrDefault(emptyMap())

    fun saveNeteaseCookies(cookies: Map<String, String>) {
        val json = JSONObject()
        cookies.forEach { (k, v) -> if (k.isNotBlank()) json.put(k, v) }
        neteaseCookies = json.toString()
    }

    fun clearNeteaseCookies() {
        neteaseCookies = ""
        neteaseName = ""
        neteaseAvatar = ""
        neteaseVip = false
    }

    val isNeteaseLoggedIn: Boolean
        get() = !neteaseCookieMap()["MUSIC_U"].isNullOrBlank()

    /** 当前登录的网易云 userId（缓存，未登录为 0）。 */
    var neteaseUserId: Long
        get() = prefs.getLong(KEY_NETEASE_UID, 0L)
        set(value) = prefs.edit { putLong(KEY_NETEASE_UID, value) }

    /** 已缓存的最近播放曲目 id，离线时兜底展示。 */
    var recentSongIds: String
        get() = prefs.getString(KEY_RECENT_SONG_IDS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_RECENT_SONG_IDS, value) }

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
        private const val KEY_NETEASE_COOKIES = "netease_cookies"
        private const val KEY_NETEASE_NAME = "netease_name"
        private const val KEY_NETEASE_AVATAR = "netease_avatar"
        private const val KEY_NETEASE_VIP = "netease_vip"
        private const val KEY_NETEASE_UID = "netease_uid"
        private const val KEY_RECENT_SONG_IDS = "recent_song_ids"

        private const val KEY_PLAY_QUALITY = "play_quality"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_LYRIC_AUTO_SCROLL = "lyric_auto_scroll"
        private const val KEY_AUDIO_EFFECT = "audio_effect"
    }
}
