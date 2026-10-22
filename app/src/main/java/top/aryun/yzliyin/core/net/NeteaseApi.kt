package top.aryun.yzliyin.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.data.JsonCodecs
import top.aryun.yzliyin.core.model.Album
import top.aryun.yzliyin.core.model.Artist
import top.aryun.yzliyin.core.model.Comment
import top.aryun.yzliyin.core.model.LyricLine
import top.aryun.yzliyin.core.model.Paged
import top.aryun.yzliyin.core.model.Parsers
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.ResolveResult
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.util.AppLocale
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.util.isDirectUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网易云音乐接口门面（底层 [NeteaseClient] weapi/api/eapi 直连官方接口）。
 *
 * 数据源仅网易云音乐；统一输出本项目的模型，登录态经 [AppGraph.store] 持久化。
 */
object NeteaseApi {

    private const val TAG = "NeteaseApi"

    private val client: NeteaseClient get() = AppGraph.netease
    private val store get() = AppGraph.store

    /** Cookie 变化后（登录/退出）同步到客户端。 */
    fun syncCookies() {
        client.setPersistedCookies(store.neteaseCookieMap())
    }

    val isLoggedIn: Boolean get() = store.isNeteaseLoggedIn

    // ================= 搜索 =================

    /**
     * 云搜索。
     * @param type 1=歌曲 10=专辑 100=歌手 1000=歌单
     */
    private fun searchRaw(keyword: String, type: Int, limit: Int, offset: Int): JSONObject {
        val raw = client.searchSongs(keyword, limit = limit, offset = offset, type = type)
        return JSONObject(raw)
    }

    suspend fun searchSongs(keyword: String, page: Int = 1, pageSize: Int = 30): Paged<Song> =
        withContext(Dispatchers.IO) {
            val root = searchRaw(keyword, 1, pageSize, (page - 1) * pageSize)
            val result = root.optJSONObject("result") ?: JSONObject()
            val total = result.optInt("songCount", 0)
            Paged(parseSongs(result.optJSONArray("songs")), total, page, page * pageSize < total)
        }

    suspend fun searchPlaylists(keyword: String, page: Int = 1, pageSize: Int = 30): Paged<Playlist> =
        withContext(Dispatchers.IO) {
            val root = searchRaw(keyword, 1000, pageSize, (page - 1) * pageSize)
            val result = root.optJSONObject("result") ?: JSONObject()
            val total = result.optInt("playlistCount", 0)
            Paged(parsePlaylists(result.optJSONArray("playlists")), total, page, page * pageSize < total)
        }

    suspend fun searchArtists(keyword: String, page: Int = 1, pageSize: Int = 30): Paged<Artist> =
        withContext(Dispatchers.IO) {
            val root = searchRaw(keyword, 100, pageSize, (page - 1) * pageSize)
            val result = root.optJSONObject("result") ?: JSONObject()
            val total = result.optInt("artistCount", 0)
            val arr = result.optJSONArray("artists")
            val out = ArrayList<Artist>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Artist(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        avatar = o.optString("img1v1Url").ifBlank { o.optString("picUrl") },
                        songCount = o.optInt("musicSize", 0),
                        albumCount = o.optInt("albumSize", 0),
                    )
                )
            }
            Paged(out, total, page, page * pageSize < total)
        }

    suspend fun searchAlbums(keyword: String, page: Int = 1, pageSize: Int = 30): Paged<Album> =
        withContext(Dispatchers.IO) {
            val root = searchRaw(keyword, 10, pageSize, (page - 1) * pageSize)
            val result = root.optJSONObject("result") ?: JSONObject()
            val total = result.optInt("albumCount", 0)
            Paged(parseAlbums(result.optJSONArray("albums")), total, page, page * pageSize < total)
        }

    /** 全站热搜词（`/api/search/hot`）。 */
    suspend fun hotSearch(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(client.getHotSearch())
            val hots = root.optJSONObject("result")?.optJSONArray("hots") ?: return@runCatching emptyList()
            (0 until hots.length()).mapNotNull { i ->
                hots.optJSONObject(i)?.optString("first")?.takeIf(String::isNotBlank)
            }
        }.getOrDefault(emptyList())
    }

    /** 搜索联想词（`/weapi/search/suggest/keyword`）。 */
    suspend fun searchSuggest(keyword: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(client.getSearchSuggest(keyword))
            if (root.optInt("code", 200) != 200) return@runCatching emptyList()
            val all = root.optJSONObject("result")?.optJSONArray("allMatch") ?: return@runCatching emptyList()
            (0 until all.length()).mapNotNull { i ->
                all.optJSONObject(i)?.optString("keyword")?.takeIf(String::isNotBlank)
            }
        }.getOrDefault(emptyList())
    }

    // ================= 歌单 =================

    /**
     * 歌单详情。
     *
     * 底层用 `/api/v6/playlist/detail`（n=1 时 tracks 截断但 trackIds 完整），
     * 因此只取元信息，曲目请用 [playlistTracks]。
     */
    suspend fun playlistDetail(id: String): Playlist? = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(client.getPlaylistDetail(id.toLong()))
            val pl = root.optJSONObject("playlist") ?: return@runCatching null
            // n=100000 时 trackIds 也是完整的，顺手缓存，避免 tracks 页再请求一次
            val raw = pl.optJSONArray("trackIds")
            if (raw != null && cachedTrackIds(id) == null) {
                val list = ArrayList<Long>(raw.length())
                for (i in 0 until raw.length()) {
                    val o = raw.optJSONObject(i)
                    val tid = (o?.optLong("id") ?: raw.optLong(i, -1L))
                    if (tid > 0) list.add(tid)
                }
                cacheTrackIds(id, list)
            }
            parsePlaylist(pl)
        }.getOrNull()
    }

    /** 歌单 id 列表缓存（trackIds 完整，详情按页批量补）。 */
    private val trackIdCache = LinkedHashMap<String, List<Long>>()

    private fun cachedTrackIds(id: String): List<Long>? =
        synchronized(trackIdCache) { trackIdCache[id] }

    private fun cacheTrackIds(id: String, ids: List<Long>) {
        synchronized(trackIdCache) {
            if (trackIdCache.size > 24) trackIdCache.remove(trackIdCache.keys.first())
            trackIdCache[id] = ids
        }
    }

    /** 歌单/榜单曲目（按 trackIds 分页，缺失详情批量补全并保持顺序）。 */
    suspend fun playlistTracks(playlistId: String, page: Int = 1, pageSize: Int = 50): Paged<Song> =
        withContext(Dispatchers.IO) {
            val ids = cachedTrackIds(playlistId) ?: runCatching {
                val root = JSONObject(client.getPlaylistDetail(playlistId.toLong(), n = 1))
                val raw = root.optJSONObject("playlist")?.optJSONArray("trackIds") ?: JSONArray()
                val list = ArrayList<Long>(raw.length())
                for (i in 0 until raw.length()) {
                    val o = raw.optJSONObject(i)
                    val id = (o?.optLong("id") ?: raw.optLong(i, -1L))
                    if (id > 0) list.add(id)
                }
                cacheTrackIds(playlistId, list)
                list
            }.getOrDefault(emptyList())

            val offset = (page - 1) * pageSize
            val slice = if (offset >= ids.size) emptyList() else ids.subList(offset, minOf(offset + pageSize, ids.size))
            val songs = if (slice.isEmpty()) emptyList() else fetchSongDetails(slice)
            Paged(songs, ids.size, page, offset + slice.size < ids.size)
        }

    /** 批量取歌曲详情（`/weapi/v3/song/detail`，每批 50，按 id 对齐保持顺序）。 */
    private fun fetchSongDetails(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Song>(ids.size)
        ids.chunked(50).forEach { chunk ->
            runCatching {
                val root = JSONObject(client.getSongDetail(chunk))
                val map = parseSongs(root.optJSONArray("songs")).associateBy { it.id.toLongOrNull() }
                chunk.forEach { id -> map[id]?.let(out::add) }
            }
        }
        return out
    }

    /** 推荐歌单（`/weapi/personalized/playlist`）；取不到（离线）时回退上次缓存。 */
    suspend fun recommendedPlaylists(limit: Int = 10): List<Playlist> = withContext(Dispatchers.IO) {
        val key = "netease_recommended_$limit"
        runCatching {
            val root = JSONObject(client.getRecommendedPlaylists(limit = limit))
            parsePlaylists(root.optJSONArray("result"))
        }.getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() }
            ?.also { store.cachePut(key, JsonCodecs.playlistsToJson(it)) }
            ?: JsonCodecs.playlistsFromJson(store.cacheGet(key))
    }

    /** 推荐新歌（`/weapi/personalized/newsong`，result[].song 为 v1 字段）。 */
    suspend fun newSongs(limit: Int = 12): List<Song> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(client.getPersonalizedNewsongs(limit = limit))
            val arr = root.optJSONArray("result") ?: return@runCatching emptyList()
            val out = ArrayList<Song>(arr.length())
            for (i in 0 until arr.length()) {
                val song = arr.optJSONObject(i)?.optJSONObject("song") ?: continue
                parseSongs(JSONArray().put(song)).firstOrNull()?.let(out::add)
            }
            out
        }.getOrDefault(emptyList())
    }

    /** 当前登录用户的歌单（`/api/user/playlist`）；离线时回退上次缓存。 */
    suspend fun userPlaylists(page: Int = 1, pageSize: Int = 30): List<Playlist> =
        withContext(Dispatchers.IO) {
            val key = "netease_user_playlists_$page"
            val uid = currentUserId()
                ?: return@withContext JsonCodecs.playlistsFromJson(store.cacheGet(key))
            runCatching {
                val raw = client.getUserPlaylists(uid, offset = (page - 1) * pageSize, limit = pageSize)
                parsePlaylists(JSONObject(raw).optJSONArray("playlist"))
            }.getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.also { store.cachePut(key, JsonCodecs.playlistsToJson(it)) }
                ?: JsonCodecs.playlistsFromJson(store.cacheGet(key))
        }

    /** 取当前登录用户 id（失败返回 null）。 */
    suspend fun currentUserId(): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val uid = client.getCurrentUserId()
            store.neteaseUserId = uid
            uid
        }.getOrNull() ?: store.neteaseUserId.takeIf { it > 0L }
    }

    // ================= 评论 =================

    /**
     * 网易云歌曲评论。
     * 第一页会把热评（hotComments）一并带出并标记 [Comment.isHot]。
     */
    suspend fun comments(songId: String, page: Int = 1, pageSize: Int = 20): Paged<Comment> =
        withContext(Dispatchers.IO) {
            val id = songId.toLongOrNull() ?: return@withContext Paged(emptyList(), 0, 1, false)
            val offset = (page - 1) * pageSize
            val root = JSONObject(client.getComments(id, limit = pageSize, offset = offset))
            val total = root.optInt("total", 0)
            val out = ArrayList<Comment>()
            val seen = HashSet<String>()
            if (page == 1) {
                val hot = root.optJSONArray("hotComments")
                if (hot != null) for (i in 0 until hot.length()) {
                    val c = hot.optJSONObject(i)?.let { parseComment(it, isHot = true) } ?: continue
                    if (seen.add(c.id)) out.add(c)
                }
            }
            val arr = root.optJSONArray("comments")
            if (arr != null) for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i)?.let { parseComment(it, isHot = false) } ?: continue
                if (seen.add(c.id)) out.add(c)
            }
            Paged(out, total, page, root.optBoolean("more", false))
        }

    /** 发表评论（需登录；未登录时服务端返回 301）。@return 提示语。 */
    suspend fun sendComment(songId: String, content: String): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val id = songId.toLongOrNull() ?: throw IllegalStateException(AppLocale.string(R.string.net_invalid_song_id))
            val root = JSONObject(client.sendComment(id, content))
            when (val code = root.optInt("code", -1)) {
                200 -> AppLocale.string(R.string.net_comment_sent)
                301 -> throw IllegalStateException(AppLocale.string(R.string.net_comment_need_login))
                else -> throw IllegalStateException(
                    root.optString("msg").ifBlank { AppLocale.string(R.string.net_comment_send_failed, code.toString()) }
                )
            }
        }
    }

    // ================= 播放 =================

    /**
     * 取真实播放链接（音质跟随 [top.aryun.yzliyin.core.data.SessionStore.playQuality]）。
     *
     * 会员/付费曲目在无权限时，服务端会下发一段**试听片段**（`url` 非空但带
     * `freeTrialInfo`，或 `fee` 为会员曲且 `payed=0`）。这种情况一律判为不可用，
     * 好让上层按「自动找音源」去其它源找同一首歌，而不是只播几十秒。
     */
    suspend fun songUrl(songId: String): ResolveResult = withContext(Dispatchers.IO) {
        runCatching {
            val id = songId.toLongOrNull()
                ?: return@withContext ResolveResult("", AppLocale.string(R.string.net_netease_invalid_id))
            val level = store.playQuality.ifBlank { "exhigh" }
            val raw = runCatching { client.getSongDownloadUrl(id, level = level) }
                .getOrElse { client.getSongUrl(id) }
            val first = JSONObject(raw).optJSONArray("data")?.optJSONObject(0)
                ?: return@withContext ResolveResult("", AppLocale.string(R.string.net_resolve_failed))
            // 无版权曲目这里下发的是 JSON null，optString 会给出字符串 "null"，
            // 必须按“是不是真的 http 直链”判，不能只判非空
            val url = first.optString("url")
            if (!isDirectUrl(url)) {
                return@withContext ResolveResult("", AppLocale.string(R.string.net_no_copyright))
            }
            val trial = !first.isNull("freeTrialInfo") ||
                (first.optInt("payed", -1) == 0 &&
                    !first.isNull("fee") && isVipFee(first.optInt("fee")))
            if (trial) {
                return@withContext ResolveResult("", AppLocale.string(R.string.net_netease_vip))
            }
            ResolveResult(url)
        }.getOrElse { ResolveResult("", AppLocale.string(R.string.net_resolve_failed)) }
    }

    /** 该曲是否需要会员（fee=1 需 VIP，fee=4 需购买专辑）。 */
    fun isVipFee(fee: Int): Boolean = fee == 1 || fee == 4

    // ================= 歌词 =================

    /**
     * `/song/lyric/v1` → LRC 文本。
     *
     * 客户端已带 `tv=1`（译文）、`ytv=1`（逐字译文），响应里的 `tlyric` / `ytlric`
     * 就是**中文译文**，一并解析后按时间戳并回原文行，见 [Parsers.mergeTranslation]。
     */
    /**
     * 取歌词。
     *
     * 所有失败路径**都记日志**：网易云取词失败在界面上与「这首歌本来就没词」长得一模一样
     * （都显示「暂无歌词」），不打日志就无从判断到底是接口报错、VIP/地区限制，
     * 还是这首歌真没有词——这是排查「默认歌词」的唯一观测点。
     */
    suspend fun lyric(songId: String): List<LyricLine> = withContext(Dispatchers.IO) {
        runCatching {
            val id = songId.toLongOrNull() ?: run {
                NPLogger.w(TAG, "取词跳过：songId 非数字（$songId）")
                return@withContext emptyList()
            }
            val root = JSONObject(client.getLyricNew(id))
            val code = root.optInt("code", 200)
            if (code != 200) {
                // VIP / 地区限制 / 风控 / 登录态过期都会落到这里
                NPLogger.w(TAG, "取词失败：code=$code id=$id")
                return@withContext emptyList()
            }
            val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
            if (lrc.isBlank()) {
                // 接口 200 却没给 lrc：多为无版权或需会员
                NPLogger.w(TAG, "取词返回空 lrc：id=$id")
                return@withContext emptyList()
            }

            val translated = listOfNotNull(
                root.optJSONObject("tlyric")?.optString("lyric").orEmpty().ifBlank { null },
                root.optJSONObject("ytlric")?.optString("lyric").orEmpty().ifBlank { null },
            ).firstNotNullOfOrNull { text ->
                runCatching { Parsers.parseLrc(text) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }.orEmpty()

            val lines = Parsers.mergeTranslation(Parsers.parseLrc(lrc), translated)
            if (lines.isEmpty()) NPLogger.w(TAG, "lrc 解析后无有效行：id=$id")
            lines
        }.getOrElse {
            NPLogger.w(TAG, "取词异常：id=$songId（${it.message}）")
            emptyList()
        }
    }

    // ================= 歌手 / 专辑 =================

    suspend fun artistDetail(id: String): Artist? = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(client.getArtistDetail(id.toLong()))
            val a = root.optJSONObject("data")?.optJSONObject("artist") ?: root.optJSONObject("artist")
                ?: return@runCatching null
            Artist(
                id = a.optString("id").ifBlank { id },
                name = a.optString("name"),
                avatar = a.optString("avatar").ifBlank { a.optString("img1v1Url") },
                songCount = a.optInt("musicSize", 0),
                albumCount = a.optInt("albumSize", 0),
                intro = a.optString("briefDesc").ifBlank { a.optString("description") },
            )
        }.getOrNull()
    }

    suspend fun artistSongs(id: String, page: Int = 1, pageSize: Int = 50): Paged<Song> =
        withContext(Dispatchers.IO) {
            val root = JSONObject(
                client.getArtistSongs(id.toLong(), offset = (page - 1) * pageSize, limit = pageSize)
            )
            val total = root.optInt("total", 0)
            val arr = root.optJSONArray("songs")
            val songs = parseSongs(arr)
            val more = root.optBoolean("more", false) || page * pageSize < total
            Paged(songs, total, page, more)
        }

    suspend fun artistAlbums(id: String, page: Int = 1, pageSize: Int = 30): Paged<Album> =
        withContext(Dispatchers.IO) {
            val root = JSONObject(
                client.getArtistAlbums(id.toLong(), offset = (page - 1) * pageSize, limit = pageSize)
            )
            val arr = root.optJSONArray("hotAlbums")
            val albums = parseAlbums(arr)
            val total = root.optJSONObject("artist")?.optInt("albumSize", 0) ?: 0
            Paged(albums, total.coerceAtLeast(albums.size), page, root.optBoolean("more", false))
        }

    suspend fun albumDetail(id: String): Album? = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(client.getAlbumDetail(id.toLong()))
            val a = root.optJSONObject("album") ?: return@runCatching null
            parseAlbum(a).copy(
                songCount = (root.optJSONArray("songs")?.length() ?: 0)
                    .takeIf { it > 0 } ?: a.optInt("size", 0)
            )
        }.getOrNull()
    }

    suspend fun albumSongs(id: String): List<Song> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(client.getAlbumDetail(id.toLong()))
            val arr = root.optJSONArray("songs")
                ?: root.optJSONObject("album")?.optJSONArray("songs")
                ?: return@runCatching emptyList()
            parseSongs(arr)
        }.getOrDefault(emptyList())
    }

    // ================= 用户 =================

    /** 发送短信验证码。 */
    suspend fun sendCaptcha(phone: String): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val raw = client.sendCaptcha(phone)
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) {
                throw IllegalStateException(root.optString("msg").ifBlank { AppLocale.string(R.string.net_captcha_send_failed) })
            }
            root.optString("msg").ifBlank { AppLocale.string(R.string.net_captcha_sent) }
        }
    }

    /**
     * 验证码登录：verify → login → 落库 Cookie → 刷新账号信息。
     */
    suspend fun loginByCaptcha(phone: String, captcha: String): Result<Boolean> = runCatching {
        withContext(Dispatchers.IO) {
            val verify = JSONObject(client.verifyCaptcha(phone, captcha))
            if (verify.optInt("code", -1) != 200) {
                throw IllegalStateException(verify.optString("msg").ifBlank { AppLocale.string(R.string.net_captcha_incorrect) })
            }
            val login = JSONObject(client.loginByCaptcha(phone, captcha))
            if (login.optInt("code", -1) != 200) {
                throw IllegalStateException(login.optString("msg").ifBlank { AppLocale.string(R.string.net_login_failed) })
            }
            // 登录成功：取回 Cookie（必要时补 __csrf），校验后落库
            val cookies = client.getCookies().toMutableMap()
            runCatching { client.ensureWeapiSession() }
            cookies.putAll(client.getCookies())
            if (cookies["MUSIC_U"].isNullOrBlank()) {
                throw IllegalStateException(AppLocale.string(R.string.net_login_no_cookie))
            }
            store.saveNeteaseCookies(cookies)
            client.setPersistedCookies(store.neteaseCookieMap())
            refreshAccount()
        }
    }

    /**
     * 拉取账号信息并落库（昵称/头像/会员/uid），供「我的」页展示。
     * @return 是否成功
     */
    suspend fun refreshAccount(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.getCurrentUserAccount()
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) return@runCatching false
            val profile = root.optJSONObject("profile") ?: return@runCatching false
            store.neteaseName = profile.optString("nickname")
            store.neteaseAvatar = profile.optString("avatarUrl")
            // vipType: 0=非会员，11=黑胶VIP 等
            store.neteaseVip = profile.optInt("vipType", 0) > 0
            store.neteaseUserId = profile.optLong("userId", 0L)
            true
        }.getOrDefault(false)
    }

    fun logout() {
        runCatching { client.logout() }
        store.clearNeteaseCookies()
        store.neteaseUserId = 0L
    }

    // ================= 解析 =================

    private fun parseSongs(arr: JSONArray?): List<Song> {
        if (arr == null) return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank() || id == "0") continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            val artists = o.optJSONArray("artists") ?: o.optJSONArray("ar")
            val album = o.optJSONObject("album") ?: o.optJSONObject("al")
            val artistIds = artists?.ids().orEmpty()
            out.add(
                Song(
                    id = id,
                    name = name,
                    artist = joinNames(artists),
                    artistId = artistIds.firstOrNull().orEmpty(),
                    artistIds = artistIds,
                    albumId = album?.optString("id").orEmpty(),
                    albumName = album?.optString("name").orEmpty(),
                    cover = (album?.optString("picUrl") ?: "").ifBlank { album?.optString("blurPicUrl") ?: "" },
                    durationMs = o.optLong("duration", o.optLong("dt", 0L)),
                    vip = isVipFee(o.optInt("fee", 0)),
                )
            )
        }
        return out
    }

    private fun parsePlaylists(arr: JSONArray?): List<Playlist> {
        if (arr == null) return emptyList()
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(parsePlaylist(o))
        }
        return out
    }

    private fun parsePlaylist(o: JSONObject): Playlist = Playlist(
        id = o.optString("id"),
        name = o.optString("name"),
        cover = o.optString("coverImgUrl").ifBlank { o.optString("picUrl") },
        intro = o.optString("description").ifBlank { o.optString("copywriter") },
        author = o.optJSONObject("creator")?.optString("nickname").orEmpty(),
        playCount = o.optLong("playCount", 0L),
        songCount = o.optInt("trackCount", 0).takeIf { it > 0 } ?: o.optInt("trackNumber", 0),
        tags = o.optJSONArray("tags")?.let { tags ->
            (0 until tags.length()).mapNotNull { tags.optString(it).takeIf(String::isNotBlank) }
        } ?: emptyList(),
    )

    private fun parseAlbums(arr: JSONArray?): List<Album> {
        if (arr == null) return emptyList()
        val out = ArrayList<Album>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseAlbum(o).takeIf { it.id.isNotBlank() }?.let(out::add)
        }
        return out
    }

    private fun parseAlbum(o: JSONObject): Album = Album(
        id = o.optString("id"),
        name = o.optString("name"),
        cover = o.optString("picUrl").ifBlank { o.optString("blurPicUrl") },
        artist = o.optJSONObject("artist")?.optString("name")
            ?: joinNames(o.optJSONArray("artists")),
        artistId = o.optJSONObject("artist")?.optString("id")
            ?: o.optJSONArray("artists")?.optJSONObject(0)?.optString("id").orEmpty(),
        publishDate = runCatching {
            val t = o.optLong("publishTime", 0L)
            if (t > 0) SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t)) else ""
        }.getOrDefault(""),
        intro = o.optString("description").ifBlank { o.optString("briefDesc") },
        songCount = o.optInt("size", 0),
    )

    private fun parseComment(o: JSONObject, isHot: Boolean): Comment {
        val user = o.optJSONObject("user")
        val reply = o.optJSONArray("beReplied")?.optJSONObject(0)
        return Comment(
            id = o.optString("commentId"),
            user = user?.optString("nickname").orEmpty(),
            avatar = user?.optString("avatarUrl").orEmpty(),
            content = o.optString("content"),
            time = runCatching {
                val t = o.optLong("time", 0L)
                if (t > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t)) else ""
            }.getOrDefault(""),
            likeCount = o.optInt("likedCount", 0),
            isHot = isHot,
            reply = buildString {
                reply?.optJSONObject("user")?.optString("nickname")?.takeIf(String::isNotBlank)
                    ?.let { append(it).append("：") }
                reply?.optString("content")?.takeIf(String::isNotBlank)?.let(::append)
            },
        )
    }

    private fun joinNames(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name").orEmpty()
            if (n.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append("/")
            sb.append(n)
        }
        return sb.toString()
    }

    /**
     * 合作歌手的 id 列表，顺序与 [joinNames] 拼出来的名字一一对应。
     * 判空标准与 [joinNames] 保持一致（以**名字**是否为空为准），
     * 否则某个歌手缺名字时两边会错位，点「乙」会跳到「丙」的主页。
     */
    private fun JSONArray?.ids(): List<String> {
        this ?: return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            if (o.optString("name").isEmpty()) continue
            out.add(o.optString("id"))
        }
        return out
    }
}
