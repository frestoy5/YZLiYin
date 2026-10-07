package top.aryun.yzliyin.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Paged
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.QrStatus
import top.aryun.yzliyin.core.model.ResolveResult
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.util.NPLogger

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

    /** 风控（需要安全验证）的业务错误码。 */
    private const val SSA_ERRCODE = 20028

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
            val avatar = data.firstString("pic", "user_pic", "avatar", "headimg", "head_img", "img")
                .ifBlank {
                    nested?.firstString("pic", "user_pic", "avatar", "headimg", "head_img", "img").orEmpty()
                }
                .replace("/{size}/", "/")
                .replace("{size}", "")
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
        val cover = item.optString("Image").replace("/{size}/", "/")
            .ifBlank { item.optJSONObject("trans_param")?.optString("union_cover").orEmpty().replace("/{size}/", "/") }
        return Song(
            id = hash,
            name = name,
            artist = item.optString("SingerName"),
            albumName = item.optString("AlbumName"),
            cover = cover,
            durationMs = item.optString("Duration").toLongOrNull().orZero() * 1000L,
            source = Source.KUGOU,
            playId = hash,
        )
    }

    private fun Long?.orZero(): Long = this ?: 0L

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
        if (hash.isBlank()) return@withContext ResolveResult("", "酷狗曲目缺少音频 hash")
        val albumId = albumAudioId.ifBlank { "0" }
        var challenged = false

        for (quality in QUALITY_LADDER) {
            val body = runCatching { client.songUrl(hash, albumId, quality).body }.getOrNull() ?: continue
            val direct = body.optJSONArray("url")?.optString(0).orEmpty()
                .ifBlank { body.optJSONArray("backupUrl")?.optString(0).orEmpty() }
            if (direct.isNotBlank()) return@withContext ResolveResult(direct)

            val failProcess = body.optJSONArray("fail_process")
            val needsBuy = (0 until (failProcess?.length() ?: 0))
                .any { failProcess?.optString(it) == "buy" } || body.has("hash_offset")
            if (needsBuy) {
                NPLogger.d(TAG, "取链被拒（付费专辑）：$hash")
                return@withContext ResolveResult(
                    "", "该歌曲在酷狗需要购买或开通会员，当前账号只能试听"
                )
            }
            if (body.optInt("errcode") == SSA_ERRCODE) challenged = true
        }

        val reason = if (challenged) {
            "酷狗要求安全验证，设备注册可能未完成，请稍后重试"
        } else {
            "获取酷狗播放链接失败（可能无版权）"
        }
        NPLogger.w(TAG, "取链失败：$hash（$reason）")
        ResolveResult("", reason)
    }

    // ---------- 我的歌单 / 我喜欢 ----------

    /**
     * 用户歌单（含系统「我喜欢」）。
     * 返回值里「我喜欢」带 [Playlist.name]，调用方据 [isLikesName] 区分。
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
                    cover = obj.optString("pic").replace("{size}", ""),
                    songCount = obj.optString("count").toIntOrNull()
                        ?: obj.optString("m_count").toIntOrNull() ?: 0,
                    source = Source.KUGOU,
                )
            }
        }.getOrElse {
            NPLogger.w(TAG, "获取用户歌单失败：${it.message}")
            emptyList()
        }
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
            cover = album?.optString("sizable_cover").orEmpty().replace("/{size}/", "/"),
            durationMs = audio?.optString("duration_128")?.toLongOrNull().orZero(),
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
            cover = obj.optString("cover").replace("{size}", "")
                .ifBlank {
                    obj.optJSONObject("trans_param")?.optString("union_cover").orEmpty().replace("{size}", "")
                },
            durationMs = obj.optString("timelen").toLongOrNull().orZero(),
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
            val data = response.body.optJSONObject("data")
            val status = data?.optString("status").orEmpty().toIntOrNull() ?: return@runCatching QrStatus.Waiting
            when (status) {
                4 -> {
                    val token = data.optString("token")
                    val userid = data.optString("userid")
                    if (token.isBlank() || userid.isBlank()) {
                        QrStatus.Failed("酷狗未返回登录凭证")
                    } else {
                        client.saveLogin(token, userid)
                        store.saveCookies(Source.KUGOU, client.cookies())
                        val nickname = data.optString("nickname")
                        if (nickname.isNotBlank()) store.saveDisplayName(Source.KUGOU, nickname)
                        QrStatus.Success(store.displayName(Source.KUGOU).ifBlank { "酷狗用户" })
                    }
                }
                2 -> QrStatus.Scanned
                1 -> QrStatus.Waiting
                0 -> QrStatus.Failed("二维码已过期，请刷新")
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
