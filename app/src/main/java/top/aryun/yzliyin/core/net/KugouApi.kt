package top.aryun.yzliyin.core.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.data.JsonCodecs
import top.aryun.yzliyin.core.model.LyricLine
import top.aryun.yzliyin.core.model.Paged
import top.aryun.yzliyin.core.model.Parsers
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.QrStatus
import top.aryun.yzliyin.core.model.ResolveResult
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.util.AppLocale
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.util.isDirectUrl
import kotlin.math.abs

/**
 * 酷狗音乐门面，与 [NeteaseApi] 同一套约定：
 * 读取类失败返回空值，写操作返回 [Result]。
 */
object KugouApi {

    private const val TAG = "KugouApi"

    /** 取链音质回退顺序（由低到高试探，免费账号通常命中 128）。 */
    private val QUALITY_LADDER = listOf("128", "320", "flac", "high", "super")

    /** 酷狗「我喜欢」歌单在列表里的名字。 */
    private const val LIKES_NAME = "我喜欢"

    /** 用户歌单离线缓存键。 */
    private const val CACHE_USER_PLAYLISTS = "kugou_user_playlists"

    /** 风控（需要安全验证）的业务错误码。 */
    private const val SSA_ERRCODE = 20028

    /** 歌词候选最多逐条试几条（按分数从高到低，试到能下到内容为止）。 */
    private const val MAX_LYRIC_CANDIDATES = 3

    private val client: KugouClient get() = AppGraph.kugou
    private val store get() = AppGraph.store

    val isLoggedIn: Boolean get() = client.isLoggedIn()

    /**
     * 拉取当前账号的昵称与头像并落库（酷狗不公开字段名，这里按候选键容错解析）。
     * 解析不到时保留既有值，返回是否拿到了昵称。
     */
    suspend fun refreshProfile(): Boolean = withContext(Dispatchers.IO) {
        if (!client.isLoggedIn()) return@withContext false
        runCatching {
            val data = client.userDetail().body.optJSONObject("data")
                ?: return@runCatching false
            val nested = data.optJSONObject("info")
            val name = data.firstString("nickname", "nick_name", "username", "user_name", "name")
                .ifBlank { nested?.firstString("nickname", "nick_name", "name").orEmpty() }
            val avatar = normalizeCoverUrl(
                data.firstString("pic", "user_pic", "avatar", "headimg", "head_img", "img")
                    .ifBlank {
                        nested?.firstString("pic", "user_pic", "avatar", "headimg", "head_img", "img").orEmpty()
                    },
            )
            if (name.isNotBlank()) store.saveDisplayName(Source.KUGOU, name)
            if (avatar.isNotBlank()) store.saveAvatar(Source.KUGOU, avatar)
            NPLogger.d(
                TAG,
                "用户信息：name=${name.ifBlank { "(未识别)" }} avatar=${avatar.isNotBlank()} " +
                    "dataKeys=${data.keys().asSequence().toList()}",
            )
            name.isNotBlank()
        }.getOrElse {
            NPLogger.w(TAG, "获取用户信息失败：${it.message}")
            false
        }
    }

    private fun JSONObject.firstString(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() } }.orEmpty()

    // ---------- 搜索 ----------

    suspend fun searchSongs(keyword: String, page: Int = 1, pageSize: Int = 30): Paged<Song> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = client.search(keyword, page, pageSize)
                if (response.status != 200) return@runCatching Paged<Song>(emptyList(), 0, page, false)
                val data = response.body.optJSONObject("data")
                    ?: return@runCatching Paged<Song>(emptyList(), 0, page, false)
                val lists = data.optJSONArray("lists")
                    ?: return@runCatching Paged<Song>(emptyList(), 0, page, false)
                val total = data.optString("total").toIntOrNull() ?: 0
                val songs = (0 until lists.length()).mapNotNull { parseSearchItem(lists.optJSONObject(it)) }
                Paged(songs, total, page, page * pageSize < total)
            }.getOrElse {
                NPLogger.w(TAG, "搜索失败：${it.message}")
                Paged(emptyList(), 0, page, false)
            }
        }

    private fun parseSearchItem(item: JSONObject?): Song? {
        item ?: return null
        val hash = item.optString("FileHash").takeIf { it.isNotBlank() } ?: return null
        val name = item.optString("OriSongName").takeIf { it.isNotBlank() } ?: return null
        val cover = normalizeCoverUrl(item.optString("Image"))
            .ifBlank {
                normalizeCoverUrl(item.optJSONObject("trans_param")?.optString("union_cover").orEmpty())
            }
        return Song(
            id = hash,
            name = name,
            artist = item.optString("SingerName"),
            albumName = item.optString("AlbumName"),
            cover = cover,
            durationMs = normalizeDurationMs(item.optString("Duration").toLongOrNull().orZero()),
            source = Source.KUGOU,
            playId = hash,
        )
    }

    private fun Long?.orZero(): Long = this ?: 0L

    /**
     * 酷狗各接口的时长字段单位不统一：搜索接口 `Duration` 给的是**秒**，
     * 歌单接口的 `duration_128` / `timelen` 给的是**毫秒**（个别旧接口又会给秒），
     * 直接混用会让时长显示成巨大值或 0——而时长为 0 会连带
     * 歌词检索（酷狗按 duration 匹配）和跨源匹配（时长是打分项）一起失败，
     * 表现就是「时长、封面、歌词都加载不了」。
     *
     * 这里统一归一到毫秒：歌曲时长不可能短到 10 毫秒、也不可能长到 10000 秒
     * （约 2.8 小时），所以「小于 1 万视为秒、否则视为毫秒」的边界是安全的。
     */
    private fun normalizeDurationMs(raw: Long): Long = when {
        raw <= 0L -> 0L
        raw < 10_000L -> raw * 1000L
        else -> raw
    }

    /**
     * 封面模板的统一归一。
     *
     * 同一个站里两种模板写法混着返回：
     *  - `…/size/{size}/…`  → `/{size}/` 这条规则能匹配；
     *  - `…/{size}_150.jpg` → 只有裸 `{size}`，若只做 `/{size}/` 就匹配不上，
     *    `{size}_150` 会原样留在 URL 里 → Coil 请求必然失败 → 永久停在占位图。
     * 所以两条都做，且顺序不能反（先裸 `{size}` 会留下一个孤零零的 `/`）。
     * 空串原样返回，占位图交给 UI 层，绝不返回半截畸形 URL。
     */
    private fun normalizeCoverUrl(raw: String): String =
        raw.replace("/{size}/", "/").replace("{size}", "").trim()

    // ---------- 播放 ----------

    /**
     * 取播放链接。按 [QUALITY_LADDER] 逐个音质试探，返回第一个可用的直链。
     *
     * `/v5/url` 的三种结果都实测确认过：
     *  - `status=1` + `url` → 可播放；
     *  - `status=2` + `fail_process` 含 `buy`（附 60s `hash_offset`）→ 付费专辑，未购买只给试听，此时直接放弃试探；
     *  - `errcode=20028` → 触发风控，通常是设备尚未注册（dfid 无效）导致。
     *
     * [albumAudioId] 允许为空（酷狗接受 0）。
     */
    suspend fun songUrl(hash: String, albumAudioId: String): ResolveResult = withContext(Dispatchers.IO) {
        if (hash.isBlank()) return@withContext ResolveResult("", AppLocale.string(R.string.net_kugou_missing_hash))
        val albumId = albumAudioId.ifBlank { "0" }
        var challenged = false

        // 设置里指定了音质就先试它，失败再按阶梯回退
        val preferred = store.kugouQuality
        val ladder = if (preferred == "auto") QUALITY_LADDER
        else listOf(preferred) + QUALITY_LADDER.filter { it != preferred }

        for (quality in ladder) {
            val body = runCatching { client.songUrl(hash, albumId, quality).body }.getOrNull() ?: continue
            val direct = body.optJSONArray("url")?.optString(0).orEmpty().trim()
                .ifBlank { body.optJSONArray("backupUrl")?.optString(0).orEmpty().trim() }
            // 无版权/未购买时 url 可能是 JSON null，optString 给出的是字符串 "null"，
            // 只判非空会把它当有效直链传下去，下载时直接闪退
            if (isDirectUrl(direct)) return@withContext ResolveResult(direct)

            val failProcess = body.optJSONArray("fail_process")
            val needsBuy = (0 until (failProcess?.length() ?: 0))
                .any { failProcess?.optString(it) == "buy" } || body.has("hash_offset")
            if (needsBuy) {
                NPLogger.d(TAG, "取链被拒（付费专辑）：$hash")
                return@withContext ResolveResult(
                    "", AppLocale.string(R.string.net_kugou_paid)
                )
            }
            if (body.optInt("errcode") == SSA_ERRCODE) challenged = true
        }

        val reason = if (challenged) {
            AppLocale.string(R.string.net_kugou_security_check)
        } else {
            AppLocale.string(R.string.net_kugou_no_stream)
        }
        NPLogger.w(TAG, "取链失败：$hash（$reason）")
        ResolveResult("", reason)
    }

    // ---------- 歌词 ----------

    /**
     * 酷狗歌词：`krcs` 检索候选 → `lyrics/download` 下载明文 LRC（公开接口，无需登录）。
     *
     * 检索带上 hash 与时长提高匹配度；候选不止一条时按平台自报的 score 与时长差排序，
     * 从最像的开始逐条试（同名的现场版 / 翻唱常常排在前面）。
     * 中文译文随 KRC 一起取；**带中文译文的候选优先用**，否则留最靠前的一条兜底。
     * 无候选或全部下载失败时返回空列表。
     */
    suspend fun lyric(song: Song): List<LyricLine> = withContext(Dispatchers.IO) {
        runCatching {
            val keyword = listOf(song.name, song.artist)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (keyword.isBlank()) return@runCatching emptyList()

            val search = client.lyricSearch(
                keyword = keyword,
                hash = song.playId.ifEmpty { song.id },
                durationMs = song.durationMs,
            ).body
            val candidates = search.optJSONArray("candidates")
                ?: search.optJSONObject("data")?.optJSONArray("candidates")
                ?: JSONArray()

            // 接口给的候选未必第一条就是对的（同名的现场版 / 翻唱常常排在前面）：
            // 按平台自报的 score 降序、时长差升序排，从最像的开始逐条试，
            // 试到第一条能下到且解析出内容的就用它——与 NeriPlayer 的 getBestLyricPayload 同款策略。
            val ranked = (0 until candidates.length())
                .mapNotNull { candidates.optJSONObject(it) }
                .filter { it.optString("id").isNotBlank() && it.optString("accesskey").isNotBlank() }
                .sortedWith(
                    compareByDescending<JSONObject> { it.optDouble("score", 0.0) }
                        .thenBy { candidate ->
                            // 歌词接口的 duration 单位历来有秒/毫秒两种说法，
                            // 用搜索接口那套归一化兜住，时长差只作次级排序依据
                            val durationMs = normalizeDurationMs(candidate.optLong("duration", 0L))
                            if (durationMs <= 0L || song.durationMs <= 0L) {
                                Long.MAX_VALUE
                            } else {
                                abs(durationMs - song.durationMs)
                            }
                        },
                )
                .take(MAX_LYRIC_CANDIDATES)

            // 逐条试：能下到内容的先留作兜底；一旦遇到**带中文译文**的候选就直接用它。
            // 酷狗同一首歌会挂好几份歌词（不同上传者），排最前面的常常只有音译没有中文译文
            // （KRC 的 language 标签里 type==1 才是译文，type==0 是音译），
            // 只认第一条就会白丢翻译——这正是「日文歌下面没有中文」的一大来源。
            var fallback: List<LyricLine>? = null
            for (best in ranked) {
                val id = best.optString("id")
                val accessKey = best.optString("accesskey")

                val download = runCatching { client.lyricDownload(id, accessKey).body }.getOrNull()
                val content = download?.optString("content").orEmpty()
                if (content.isBlank()) continue
                val original = runCatching {
                    Parsers.parseLrc(String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8))
                }.getOrNull().orEmpty()
                if (original.isEmpty()) continue

                // 中文译文不在明文 LRC 里，只在 KRC 的 language 标签中，单独拉一次；
                // 拿不到就只显示原文，不影响主流程。
                val translated = runCatching {
                    val krc = client.lyricDownloadKrc(id, accessKey).body.optString("content")
                    if (krc.isBlank()) null else KugouKrc.translationLrc(krc)
                }.getOrNull()
                    ?.let { runCatching { Parsers.parseLrc(it) }.getOrNull() }
                    .orEmpty()

                if (translated.isNotEmpty()) {
                    return@runCatching Parsers.mergeTranslation(original, translated)
                }
                if (fallback == null) fallback = original
                // 整首都是汉字行，本来就不挂译文，再往下试只是白费两次请求
                if (original.none { !Parsers.isMostlyChinese(it.text) }) break
            }
            fallback.orEmpty()
        }.getOrElse {
            NPLogger.w(TAG, "获取歌词失败：${it.message}")
            emptyList()
        }
    }

    // ---------- 我的歌单 / 我喜欢 ----------

    /**
     * 用户歌单（含系统「我喜欢」）。
     * 返回值里「我喜欢」带 [Playlist.name]，调用方据 [isLikesName] 区分。
     * 取不到（离线）时回退上次缓存。
     */
    suspend fun userPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.userPlaylists(page = 1, pageSize = 100)
            if (response.status != 200) return@runCatching emptyList()
            val info = response.body.optJSONObject("data")?.optJSONArray("info")
                ?: return@runCatching emptyList()
            (0 until info.length()).mapNotNull { index ->
                val obj = info.optJSONObject(index) ?: return@mapNotNull null
                val listId = obj.optString("listid").takeIf { it.isNotBlank() && it != "0" }
                    ?: return@mapNotNull null
                val globalId = obj.optString("global_collection_id").orEmpty()
                val name = obj.optString("name").ifBlank { obj.optString("listname") }
                    .takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // 私有/自建歌单走 listid 接口，公开歌单走 global_collection_id 接口
                val routeId = if (globalId.isNotBlank()) globalId else listId
                Playlist(
                    id = routeId,
                    name = name,
                    cover = normalizeCoverUrl(obj.optString("pic")),
                    songCount = obj.optString("count").toIntOrNull()
                        ?: obj.optString("m_count").toIntOrNull() ?: 0,
                    source = Source.KUGOU,
                )
            }
        }.getOrElse {
            NPLogger.w(TAG, "获取用户歌单失败：${it.message}")
            emptyList()
        }
            .takeIf { it.isNotEmpty() }
            ?.also { store.cachePut(CACHE_USER_PLAYLISTS, JsonCodecs.playlistsToJson(it)) }
            ?: JsonCodecs.playlistsFromJson(store.cacheGet(CACHE_USER_PLAYLISTS))
    }

    /** 歌单名是否为酷狗的系统「我喜欢」。 */
    fun isLikesName(name: String): Boolean = name.trim() == LIKES_NAME

    /** 定位「我喜欢」歌单；未登录或接口未返回时为空。 */
    suspend fun likesPlaylist(): Playlist? = userPlaylists().firstOrNull { isLikesName(it.name) }

    /** 按 id 在用户歌单里找元信息（详情页标题/封面用）。 */
    suspend fun findPlaylist(playlistId: String): Playlist? =
        userPlaylists().firstOrNull { it.id == playlistId }

    // ---------- 歌单曲目 ----------

    suspend fun playlistTracks(playlistId: String, page: Int = 1, pageSize: Int = 100): Paged<Song> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = if (playlistId.startsWith("collection_")) {
                    client.playlistTracks(playlistId, page, pageSize)
                } else {
                    client.playlistTracksSelf(playlistId, page, pageSize)
                }
                if (response.status != 200) return@runCatching Paged<Song>(emptyList(), 0, page, false)
                val data = response.body.optJSONObject("data")
                    ?: return@runCatching Paged<Song>(emptyList(), 0, page, false)

                val publicSongs = data.optJSONArray("songlist")
                val songs = if (publicSongs != null) {
                    (0 until publicSongs.length()).mapNotNull { parsePublicItem(publicSongs.optJSONObject(it)) }
                } else {
                    val selfSongs = data.optJSONArray("songs") ?: JSONArray()
                    (0 until selfSongs.length()).mapNotNull { parseSelfItem(selfSongs.optJSONObject(it)) }
                }
                val total = data.optString("total").toIntOrNull() ?: songs.size
                Paged(songs, total, page, page * pageSize < total)
            }.getOrElse {
                NPLogger.w(TAG, "获取歌单曲目失败：${it.message}")
                Paged(emptyList(), 0, page, false)
            }
        }

    /** `/pubsongs/v2/get_other_list_file_nofilt` 的 data.songlist 结构。 */
    private fun parsePublicItem(obj: JSONObject?): Song? {
        obj ?: return null
        val audio = obj.optJSONObject("audio_info")
        val album = obj.optJSONObject("album_info")
        val hash = audio?.optString("hash_128").orEmpty()
            .ifBlank { obj.optJSONObject("deprecated")?.optString("hash").orEmpty() }
            .takeIf { it.isNotBlank() } ?: return null
        val albumAudioId = obj.optString("album_audio_id")
        val name = obj.optString("songname").takeIf { it.isNotBlank() } ?: return null
        return Song(
            id = hash,
            name = name,
            artist = obj.optString("author_name"),
            albumId = albumAudioId,
            albumName = album?.optString("album_name").orEmpty(),
            cover = normalizeCoverUrl(album?.optString("sizable_cover").orEmpty()),
            durationMs = normalizeDurationMs(audio?.optString("duration_128")?.toLongOrNull().orZero()),
            source = Source.KUGOU,
            playId = hash,
            playSubId = albumAudioId,
        )
    }

    /** `/v4/get_list_all_file` 的 data.songs 结构。 */
    private fun parseSelfItem(obj: JSONObject?): Song? {
        obj ?: return null
        val hash = obj.optString("hash").takeIf { it.isNotBlank() } ?: return null
        val rawName = obj.optString("name")
        val singerInfo = obj.optJSONArray("singerinfo")
        val artists = if (singerInfo != null && singerInfo.length() > 0) {
            (0 until singerInfo.length())
                .mapNotNull { singerInfo.optJSONObject(it)?.optString("name")?.takeIf { it.isNotBlank() } }
                .joinToString(", ")
        } else {
            rawName.substringBefore(" - ", "")
        }
        val albumAudioId = obj.optString("mixsongid")
            .ifBlank { obj.optString("album_id") }
        return Song(
            id = hash,
            name = rawName.substringAfter(" - ", rawName),
            artist = artists,
            albumId = albumAudioId,
            albumName = obj.optJSONObject("albuminfo")?.optString("name").orEmpty(),
            cover = normalizeCoverUrl(obj.optString("cover"))
                .ifBlank {
                    normalizeCoverUrl(obj.optJSONObject("trans_param")?.optString("union_cover").orEmpty())
                },
            durationMs = normalizeDurationMs(obj.optString("timelen").toLongOrNull().orZero()),
            source = Source.KUGOU,
            playId = hash,
            playSubId = albumAudioId,
        )
    }

    // ---------- 扫码登录 ----------

    suspend fun createQrKey(): String = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.createQrKey()
            response.body.optJSONObject("data")?.optString("qrcode").orEmpty()
        }.getOrElse {
            NPLogger.w(TAG, "申请二维码失败：${it.message}")
            ""
        }
    }

    /**
     * 轮询扫码状态。扫码进度在 `data.status`（顶层 `status` 是业务状态，不能拿来判断）：
     * 1 等待扫码、2 已扫码待确认、4 成功、0 已过期。
     */
    suspend fun checkQr(key: String): QrStatus = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.checkQrCode(key)
            val data = response.body.optJSONObject("data") ?: return@runCatching QrStatus.Waiting
            val status = data.optString("status").toIntOrNull() ?: return@runCatching QrStatus.Waiting
            when (status) {
                4 -> {
                    val token = data.optString("token")
                    val userid = data.optString("userid")
                    if (token.isBlank() || userid.isBlank()) {
                        QrStatus.Failed(AppLocale.string(R.string.net_kugou_no_credential))
                    } else {
                        client.saveLogin(token, userid)
                        store.saveCookies(Source.KUGOU, client.cookies())
                        val nickname = data.optString("nickname")
                        if (nickname.isNotBlank()) store.saveDisplayName(Source.KUGOU, nickname)
                        QrStatus.Success(store.displayName(Source.KUGOU).ifBlank { AppLocale.string(R.string.net_kugou_default_name) })
                    }
                }
                2 -> QrStatus.Scanned
                1 -> QrStatus.Waiting
                0 -> QrStatus.Failed(AppLocale.string(R.string.net_qr_expired))
                else -> QrStatus.Waiting
            }
        }.getOrElse {
            NPLogger.w(TAG, "扫码状态查询失败：${it.message}")
            QrStatus.Waiting
        }
    }

    fun logout() {
        store.clearSession(Source.KUGOU)
        client.seed(store.cookieMap(Source.KUGOU))
    }
}
