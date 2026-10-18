package top.aryun.yzliyin.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import top.aryun.yzliyin.core.model.FollowMeta
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.SongInfoEdit
import top.aryun.yzliyin.core.model.Source

/** 上次播放快照（队列、当前下标、进度毫秒）。 */
data class PlaybackSession(val songs: List<Song>, val index: Int, val positionMs: Long)

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

    // ---------- 曲库：最近播放 / 本地音频 ----------

    /** 最近播放的**完整**曲目（JSON，最新在前，最多 [RECENT_LIMIT] 首）。 */
    fun readRecentSongs(): List<Song> = JsonCodecs.songsFromJson(recentSongsRaw).map { applySongInfoEdit(it) }

    /** 记录一次播放，去重后置顶并截断到上限。 */
    fun pushRecentSong(song: Song) {
        val list = (listOf(song) + readRecentSongs().filterNot { it.key == song.key })
            .take(RECENT_LIMIT)
        // 落盘时还原成源站原始信息：读取时再套编辑，「恢复原信息」才能真正还原
        recentSongsRaw = JsonCodecs.songsToJson(list.map { stripSongInfoEdit(it) })
        // 旧的 id 列表仍写一份，保持兼容
        recentSongIds = list.joinToString(",") { it.key }
    }

    /** 已经落盘到本地的曲目（JSON），曲库「本地」页据此列出。 */
    fun readDownloadedSongs(): List<Song> = JsonCodecs.songsFromJson(downloadedRaw).map { applySongInfoEdit(it) }

    /** 追加一首本地音频对应的曲目，按 [Song.key] 去重。 */
    fun pushDownloadedSong(song: Song) {
        downloadedRaw = JsonCodecs.songsToJson(
            (listOf(song) + readDownloadedSongs().filterNot { it.key == song.key })
                .take(DOWNLOAD_LIMIT)
                .map { stripSongInfoEdit(it) },
        )
    }

    /** 移除已从缓存中删除的曲目。 */
    fun removeDownloadedSong(key: String) {
        downloadedRaw = JsonCodecs.songsToJson(readDownloadedSongs().filterNot { it.key == key })
    }

    private var recentSongsRaw: String
        get() = prefs.getString(KEY_RECENT_SONGS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_RECENT_SONGS, value) }

    private var downloadedRaw: String
        get() = prefs.getString(KEY_DOWNLOADED_SONGS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_DOWNLOADED_SONGS, value) }

    // ---------- 曲目信息手工编辑（播放页「编辑歌曲信息」） ----------

    /** 某曲目手工编辑过的信息；没编辑过返回 null。 */
    fun songInfoEdit(key: String): SongInfoEdit? = songInfoEdits()[key]

    /** 写入某曲目的编辑信息；传 null 表示撤销编辑、还原源站给的信息。 */
    fun saveSongInfoEdit(key: String, edit: SongInfoEdit?) {
        val map = songInfoEdits().toMutableMap()
        if (edit == null) map.remove(key) else map[key] = edit
        val root = JSONObject()
        map.forEach { (k, e) ->
            root.put(k, JSONObject().apply {
                put("name", e.name)
                put("artist", e.artist)
                put("cover", e.cover)
                put("baseName", e.baseName)
                put("baseArtist", e.baseArtist)
                put("baseCover", e.baseCover)
            })
        }
        songInfoEditsRaw = if (root.length() == 0) "" else root.toString()
        songInfoEditsCache = map
    }

    /**
     * 把手工编辑套到曲目上；没编辑过原样返回。
     * 编辑过的字段留空时回退到编辑前的原始值，避免出现空歌名。
     */
    fun applySongInfoEdit(song: Song): Song {
        val e = songInfoEdits()[song.key] ?: return song
        return song.copy(
            name = e.name.ifBlank { e.baseName.ifBlank { song.name } },
            artist = e.artist.ifBlank { e.baseArtist.ifBlank { song.artist } },
            cover = e.cover.ifBlank { e.baseCover.ifBlank { song.cover } },
        )
    }

    /**
     * 落盘前把编辑抹掉、还原成源站原始信息：存储里永远是原始值，读取时再套编辑。
     * 这样撤销编辑后列表 / 队列能真正还原，不会残留改过的字。
     */
    private fun stripSongInfoEdit(song: Song): Song {
        val e = songInfoEdits()[song.key] ?: return song
        return song.copy(
            name = e.baseName.ifBlank { song.name },
            artist = e.baseArtist.ifBlank { song.artist },
            cover = e.baseCover.ifBlank { song.cover },
        )
    }

    /** 解析一次后缓存：列表批量套用编辑时不必每首歌都读一遍 JSON。 */
    private fun songInfoEdits(): Map<String, SongInfoEdit> {
        songInfoEditsCache?.let { return it }
        val parsed = runCatching {
            val root = JSONObject(songInfoEditsRaw.ifEmpty { "{}" })
            root.keys().asSequence().mapNotNull { key ->
                val o = root.optJSONObject(key) ?: return@mapNotNull null
                key to SongInfoEdit(
                    name = o.optString("name"),
                    artist = o.optString("artist"),
                    cover = o.optString("cover"),
                    baseName = o.optString("baseName"),
                    baseArtist = o.optString("baseArtist"),
                    baseCover = o.optString("baseCover"),
                )
            }.toMap()
        }.getOrDefault(emptyMap())
        songInfoEditsCache = parsed
        return parsed
    }

    @Volatile
    private var songInfoEditsCache: Map<String, SongInfoEdit>? = null

    private var songInfoEditsRaw: String
        get() = prefs.getString(KEY_SONG_INFO_EDITS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SONG_INFO_EDITS, value) }

    // ---------- 跨源跟随取到的封面 / 歌手 ----------

    /** 某曲目在跟随源里匹配到的展示信息；没开跟随或还没匹配到则为 null。 */
    fun followMeta(key: String): FollowMeta? {
        val wanted = metadataFollow
        if (wanted == FOLLOW_OFF) return null
        return followMetas()[key]?.takeIf { it.source == wanted }
    }

    /** 写入跨源匹配到的封面 / 歌手（只留最近若干条，防止长期播放撑大偏好）。 */
    fun saveFollowMeta(key: String, meta: FollowMeta) {
        val map = followMetas().toMutableMap()
        map[key] = meta
        while (map.size > FOLLOW_META_LIMIT) {
            val oldest = map.entries.firstOrNull()?.key ?: break
            map.remove(oldest)
        }
        val root = JSONObject()
        map.forEach { (k, m) ->
            root.put(k, JSONObject().apply {
                put("cover", m.cover)
                put("artist", m.artist)
                put("source", m.source)
            })
        }
        followMetaRaw = root.toString()
        followMetaCache = map
    }

    /**
     * 把跟随源的封面/歌手套到曲目上；没开跟随或没匹配到就原样返回。
     * 用户手工编辑过的字段优先级更高，仍显示编辑值，不被跟随源盖掉。
     * 播放状态每次刷新都会走这里，必须零成本：读内存缓存，不碰磁盘。
     */
    fun applyFollowMeta(song: Song): Song {
        val meta = followMeta(song.key) ?: return song
        val edit = songInfoEdits()[song.key]
        return song.copy(
            cover = if (edit != null && edit.cover != edit.baseCover) {
                song.cover
            } else {
                meta.cover.ifBlank { song.cover }
            },
            artist = if (edit != null && edit.artist != edit.baseArtist) {
                song.artist
            } else {
                meta.artist.ifBlank { song.artist }
            },
        )
    }

    private fun followMetas(): Map<String, FollowMeta> {
        followMetaCache?.let { return it }
        val parsed = runCatching {
            val root = JSONObject(followMetaRaw.ifEmpty { "{}" })
            root.keys().asSequence().mapNotNull { key ->
                val o = root.optJSONObject(key) ?: return@mapNotNull null
                key to FollowMeta(
                    cover = o.optString("cover"),
                    artist = o.optString("artist"),
                    source = o.optString("source"),
                )
            }.toMap()
        }.getOrDefault(emptyMap())
        followMetaCache = parsed
        return parsed
    }

    @Volatile
    private var followMetaCache: Map<String, FollowMeta>? = null

    private var followMetaRaw: String
        get() = prefs.getString(KEY_FOLLOW_META, "") ?: ""
        set(value) = prefs.edit { putString(KEY_FOLLOW_META, value) }

    // ---------- 离线缓存 ----------
    /** 读取列表数据的 JSON 快照（歌单等），离线时兜底。 */
    fun cacheGet(key: String): String = prefs.getString("cache_$key", "") ?: ""

    /** 写入列表 JSON 快照；单条过大时放弃，防止 SharedPreferences 膨胀。 */
    fun cachePut(key: String, value: String) {
        if (value.length > MAX_CACHE_CHARS) return
        prefs.edit { putString("cache_$key", value) }
    }

    // ---------- 播放会话（上次播放进度） ----------
    /** 保存队列快照（仅在队列变化时调用，避免高频重写大 JSON）。 */
    fun savePlaybackQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        // 存原始信息，读取时套编辑，撤销编辑后恢复出来的队列才是干净的
        prefs.edit { putString(KEY_LAST_QUEUE, JsonCodecs.songsToJson(songs.map { stripSongInfoEdit(it) })) }
    }

    /** 更新播放下标与进度（高频调用，只写两个标量，不重写队列）。 */
    fun savePlaybackProgress(index: Int, positionMs: Long) {
        if (index < 0) return
        prefs.edit {
            putInt(KEY_LAST_INDEX, index)
            putLong(KEY_LAST_POSITION, positionMs.coerceAtLeast(0L))
        }
    }

    /** 读取播放快照；无有效快照时返回 null。 */
    fun loadPlaybackSession(): PlaybackSession? {
        val songs = JsonCodecs.songsFromJson(prefs.getString(KEY_LAST_QUEUE, "").orEmpty())
            .map { applySongInfoEdit(it) }
        if (songs.isEmpty()) return null
        return PlaybackSession(
            songs = songs,
            index = prefs.getInt(KEY_LAST_INDEX, 0).coerceIn(0, songs.size - 1),
            positionMs = prefs.getLong(KEY_LAST_POSITION, 0L).coerceAtLeast(0L),
        )
    }

    // ---------- 外观 ----------
    /** 外观模式：system（跟随系统深色）/ light（白天）/ dark（黑夜）。 */
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value) }

    /**
     * 屏幕方向：auto（跟随手机方向传感器，默认）/ portrait（竖屏）/ landscape（横屏）。
     * 手机想用平板那样的横屏排版，或平板上锁竖屏，都从设置 → 主题这里切。
     */
    var screenOrientation: String
        get() = prefs.getString(KEY_SCREEN_ORIENTATION, "auto") ?: "auto"
        set(value) = prefs.edit { putString(KEY_SCREEN_ORIENTATION, value) }

    /**
     * 界面大小：整套界面尺寸的倍数（1f 为默认）。
     * 在 Compose 根上缩放 Density 实现，dp 全部按倍数走，系统字体设置不受影响。
     */
    var uiScale: Float
        get() = prefs.getFloat(KEY_UI_SCALE, 1f).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        set(value) = prefs.edit { putFloat(KEY_UI_SCALE, value.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)) }

    /**
     * 字体大小：文字的倍数（1f 为默认）。
     * 缩放的是 sp → px 的换算，只影响字号，控件尺寸保持不变。
     */
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1f).coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
        set(value) = prefs.edit { putFloat(KEY_FONT_SCALE, value.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)) }

    // ---------- 播放行为 ----------
    /** 播放音质：standard / higher / exhigh（默认）/ lossless / hires。 */
    var playQuality: String
        get() = prefs.getString(KEY_PLAY_QUALITY, "exhigh") ?: "exhigh"
        set(value) = prefs.edit { putString(KEY_PLAY_QUALITY, value) }

    /** 酷狗音质：auto（按阶梯试探，默认）/ 128 / 320 / flac / high / super。 */
    var kugouQuality: String
        get() = prefs.getString(KEY_KUGOU_QUALITY, "auto") ?: "auto"
        set(value) = prefs.edit { putString(KEY_KUGOU_QUALITY, value) }

    /** 哔哩哔哩音质：auto（最高带宽，默认）/ 64 / 132 / 192（对应 dash audio id 30216/30232/30280）。 */
    var biliQuality: String
        get() = prefs.getString(KEY_BILI_QUALITY, "auto") ?: "auto"
        set(value) = prefs.edit { putString(KEY_BILI_QUALITY, value) }

    /** 列表播完是否自动连播下一首。 */
    var autoPlayNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PLAY_NEXT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PLAY_NEXT, value) }

    /**
     * 当前源取不到播放链接时，是否自动去其它源按歌名+歌手找同一首。
     * 顺序固定为 网易云 → 酷狗 → 哔哩哔哩，从当前源之后接着找。
     */
    var autoSourceFallback: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SOURCE_FALLBACK, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SOURCE_FALLBACK, value) }

    /**
     * 跨源跟随元数据：off（关闭，用当前源的封面/歌手）/ netease / kugou / bilibili。
     * 选了某个源后，播放页与迷你条的封面、歌手、歌词优先取自该源（按歌名+歌手+时长匹配）。
     * 歌词另有兜底：本源取不到时本来就会自动去另一源补，与这个设置无关。
     */
    var metadataFollow: String
        get() = prefs.getString(KEY_METADATA_FOLLOW, FOLLOW_OFF) ?: FOLLOW_OFF
        set(value) = prefs.edit { putString(KEY_METADATA_FOLLOW, value) }

    /** 打开 App 时是否自动接着上次的队列继续播放（默认停在暂停态）。 */
    var autoPlayOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PLAY_ON_LAUNCH, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PLAY_ON_LAUNCH, value) }

    /** 播放页歌词是否自动滚动到当前行。 */
    var lyricAutoScroll: Boolean
        get() = prefs.getBoolean(KEY_LYRIC_AUTO_SCROLL, true)
        set(value) = prefs.edit { putBoolean(KEY_LYRIC_AUTO_SCROLL, value) }

    // ---------- 歌词外观（播放页三个点 → 歌词样式） ----------

    /** 歌词字号倍数，1f 为默认。 */
    var lyricFontSize: Float
        get() = prefs.getFloat(KEY_LYRIC_FONT_SIZE, 1f).coerceIn(LYRIC_FONT_MIN, LYRIC_FONT_MAX)
        set(value) = prefs.edit {
            putFloat(KEY_LYRIC_FONT_SIZE, value.coerceIn(LYRIC_FONT_MIN, LYRIC_FONT_MAX))
        }

    /** 当前行歌词的波浪（弯曲）强度，0f 为直线。 */
    var lyricWave: Float
        get() = prefs.getFloat(KEY_LYRIC_WAVE, 0f).coerceIn(0f, 1f)
        set(value) = prefs.edit { putFloat(KEY_LYRIC_WAVE, value.coerceIn(0f, 1f)) }

    /** 当前行歌词的光亮程度，0f 为原样。 */
    var lyricGlow: Float
        get() = prefs.getFloat(KEY_LYRIC_GLOW, 0f).coerceIn(0f, 1f)
        set(value) = prefs.edit { putFloat(KEY_LYRIC_GLOW, value.coerceIn(0f, 1f)) }

    /** 低频增强音效开关（旧版全局开关，现作为各源开关的迁移默认值）。 */
    var audioEffect: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_EFFECT, false)
        set(value) = prefs.edit { putBoolean(KEY_AUDIO_EFFECT, value) }

    /** 指定源的低频增强音效开关。 */
    fun effectEnabled(source: Source): Boolean = prefs.getBoolean(
        when (source) {
            Source.NETEASE -> KEY_EFFECT_NETEASE
            Source.KUGOU -> KEY_EFFECT_KUGOU
            Source.BILIBILI -> KEY_EFFECT_BILIBILI
        },
        audioEffect,
    )

    fun setEffect(source: Source, enabled: Boolean) {
        prefs.edit {
            putBoolean(
                when (source) {
                    Source.NETEASE -> KEY_EFFECT_NETEASE
                    Source.KUGOU -> KEY_EFFECT_KUGOU
                    Source.BILIBILI -> KEY_EFFECT_BILIBILI
                },
                enabled,
            )
        }
    }

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
        private const val KEY_RECENT_SONGS = "recent_songs"
        private const val KEY_DOWNLOADED_SONGS = "downloaded_songs"
        private const val KEY_SONG_INFO_EDITS = "song_info_edits"
        private const val KEY_METADATA_FOLLOW = "metadata_follow"
        private const val KEY_FOLLOW_META = "follow_meta"

        /** 跨源跟随：关闭（用当前源的封面/歌手）。 */
        const val FOLLOW_OFF = "off"

        /** 跨源跟随缓存条数上限。 */
        private const val FOLLOW_META_LIMIT = 100

        /** 最近播放保留条数。 */
        private const val RECENT_LIMIT = 30

        /** 本地音频索引保留条数（远超缓存容量能容纳的量，只是兜底）。 */
        private const val DOWNLOAD_LIMIT = 500

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_SCREEN_ORIENTATION = "screen_orientation"
        private const val KEY_UI_SCALE = "ui_scale"
        private const val KEY_FONT_SCALE = "font_scale"

        /** 界面大小倍数上下限（1f 为默认）。 */
        const val UI_SCALE_MIN = 0.85f
        const val UI_SCALE_MAX = 1.4f

        /** 字体大小倍数上下限（1f 为默认）。 */
        const val FONT_SCALE_MIN = 0.85f
        const val FONT_SCALE_MAX = 1.5f

        private const val KEY_PLAY_QUALITY = "play_quality"
        private const val KEY_KUGOU_QUALITY = "kugou_quality"
        private const val KEY_BILI_QUALITY = "bili_quality"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_AUTO_SOURCE_FALLBACK = "auto_source_fallback"
        private const val KEY_AUTO_PLAY_ON_LAUNCH = "auto_play_on_launch"
        private const val KEY_LYRIC_AUTO_SCROLL = "lyric_auto_scroll"
        private const val KEY_LYRIC_FONT_SIZE = "lyric_font_size"
        private const val KEY_LYRIC_WAVE = "lyric_wave"
        private const val KEY_LYRIC_GLOW = "lyric_glow"

        /** 歌词字号倍数上下限。 */
        const val LYRIC_FONT_MIN = 0.7f
        const val LYRIC_FONT_MAX = 1.6f
        private const val KEY_AUDIO_EFFECT = "audio_effect"
        private const val KEY_EFFECT_NETEASE = "effect_netease"
        private const val KEY_EFFECT_KUGOU = "effect_kugou"
        private const val KEY_EFFECT_BILIBILI = "effect_bilibili"

        private const val KEY_LAST_QUEUE = "last_queue"
        private const val KEY_LAST_INDEX = "last_index"
        private const val KEY_LAST_POSITION = "last_position"

        /** 单条缓存上限（字符数），约 512KB。 */
        private const val MAX_CACHE_CHARS = 512 * 1024
    }
}
