package top.aryun.yzliyin.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Album
import top.aryun.yzliyin.core.model.Artist
import top.aryun.yzliyin.core.model.Comment
import top.aryun.yzliyin.core.model.LyricLine
import top.aryun.yzliyin.core.model.Paged
import top.aryun.yzliyin.core.model.Parsers
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Rank
import top.aryun.yzliyin.core.model.Song
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网易云音乐接口门面（底层 [NeteaseClient] weapi/api/eapi 直连官方接口）。
 *
 * 数据源仅网易云音乐；统一输出本项目的模型，登录态经 [AppGraph.store] 持久化。
 */
object NeteaseApi {

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

    // ================= 榜单 / 歌单 =================

    /** 官方全部榜单（`/api/toplist`）。 */
    suspend fun toplists(): List<Rank> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(client.getToplists())
            val list = root.optJSONArray("list") ?: return@runCatching emptyList()
            val out = ArrayList<Rank>(list.length())
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                out.add(
                    Rank(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        cover = o.optString("coverImgUrl"),
                        updateFrequency = o.optString("updateFrequency"),
                        intro = o.optString("description"),
                        tag = o.optString("tag").ifBlank { o.optString("ToplistType") },
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    /** 按 id 从榜单列表里取一条（找不到则返回空）。 */
    suspend fun rankInfo(id: String): Rank? = toplists().firstOrNull { it.id == id }

    /**
     * 歌单/榜单详情。
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

    /** 推荐歌单（`/weapi/personalized/playlist`）。 */
    suspend fun recommendedPlaylists(limit: Int = 10): List<Playlist> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(client.getRecommendedPlaylists(limit = limit))
            parsePlaylists(root.optJSONArray("result"))
        }.getOrDefault(emptyList())
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

    /** 当前登录用户的歌单（`/api/user/playlist`）。 */
    suspend fun userPlaylists(page: Int = 1, pageSize: Int = 30): List<Playlist> =
        withContext(Dispatchers.IO) {
            val uid = currentUserId() ?: return@withContext emptyList()
            runCatching {
                val raw = client.getUserPlaylists(uid, offset = (page - 1) * pageSize, limit = pageSize)
                parsePlaylists(JSONObject(raw).optJSONArray("playlist"))
            }.getOrDefault(emptyList())
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
            val id = songId.toLongOrNull() ?: throw IllegalStateException("歌曲 id 无效")
            val root = JSONObject(client.sendComment(id, content))
            when (val code = root.optInt("code", -1)) {
                200 -> "评论发送成功"
                301 -> throw IllegalStateException("请先登录后再发表评论")
                else -> throw IllegalStateException(root.optString("msg").ifBlank { "评论发送失败($code)" })
            }
        }
    }

    // ================= 播放 =================

    /**
     * 取真实播放链接（音质跟随 [top.aryun.yzliyin.core.data.SessionStore.playQuality]）。
     * @return 可播放 url；不可用时返回空串。
     */
    suspend fun songUrl(songId: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val id = songId.toLongOrNull() ?: return@withContext ""
            val level = store.playQuality.ifBlank { "exhigh" }
            val raw = runCatching { client.getSongDownloadUrl(id, level = level) }
                .getOrElse { client.getSongUrl(id) }
            val data = JSONObject(raw).optJSONArray("data") ?: return@withContext ""
            val first = data.optJSONObject(0) ?: return@withContext ""
            first.optString("url").takeIf(String::isNotBlank) ?: ""
        }.getOrDefault("")
    }

    /** 该曲是否需要会员（fee=1 需 VIP，fee=4 需购买专辑）。 */
    fun isVipFee(fee: Int): Boolean = fee == 1 || fee == 4

    // ================= 歌词 =================

    /** `/song/lyric/v1` → LRC 文本。 */
    suspend fun lyric(songId: String): List<LyricLine> = withContext(Dispatchers.IO) {
        runCatching {
            val id = songId.toLongOrNull() ?: return@withContext emptyList()
            val root = JSONObject(client.getLyricNew(id))
            if (root.optInt("code", 200) != 200) return@withContext emptyList()
            val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
            if (lrc.isBlank()) return@withContext emptyList()
            Parsers.parseLrc(lrc)
        }.getOrDefault(emptyList())
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
                throw IllegalStateException(root.optString("msg").ifBlank { "验证码发送失败" })
            }
            root.optString("msg").ifBlank { "验证码已发送" }
        }
    }

    /**
     * 验证码登录：verify → login → 落库 Cookie → 刷新账号信息。
     */
    suspend fun loginByCaptcha(phone: String, captcha: String): Result<Boolean> = runCatching {
        withContext(Dispatchers.IO) {
            val verify = JSONObject(client.verifyCaptcha(phone, captcha))
            if (verify.optInt("code", -1) != 200) {
                throw IllegalStateException(verify.optString("msg").ifBlank { "验证码错误" })
            }
            val login = JSONObject(client.loginByCaptcha(phone, captcha))
            if (login.optInt("code", -1) != 200) {
                throw IllegalStateException(login.optString("msg").ifBlank { "登录失败" })
            }
            // 登录成功：取回 Cookie（必要时补 __csrf），校验后落库
            val cookies = client.getCookies().toMutableMap()
            runCatching { client.ensureWeapiSession() }
            cookies.putAll(client.getCookies())
            if (cookies["MUSIC_U"].isNullOrBlank()) {
                throw IllegalStateException("登录未返回有效 Cookie，请改用网页登录")
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
            out.add(
                Song(
                    id = id,
                    name = name,
                    artist = joinNames(artists),
                    artistId = artists?.optJSONObject(0)?.optString("id").orEmpty(),
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
}
