package top.aryun.yzliyin.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.data.JsonCodecs
import top.aryun.yzliyin.core.model.Comment
import top.aryun.yzliyin.core.model.Paged
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.QrStatus
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.util.AppLocale
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.util.isDirectUrl

/**
 * 哔哩哔哩门面，与 [NeteaseApi] 同一套约定。
 *
 * 曲目以视频为单位（一集 = 一首），播放取该视频的 DASH 音频轨。
 */
object BiliApi {

    private const val TAG = "BiliApi"
    private const val PAGE_SIZE = 20

    /** 弹幕接口挂掉时的兜底弹幕服务器（官方公开地址，匿名也能读）。 */
    private const val FALLBACK_LIVE_DM_HOST = "wss://broadcastlv.chat.bilibili.com/sub"

    private val client: BiliClient get() = AppGraph.bilibili
    private val store get() = AppGraph.store

    val isLoggedIn: Boolean get() = client.isLoggedIn()

    /** 已登录账号的 mid（收藏夹接口需要）。 */
    val userId: Long get() = client.userId()

    fun logout() {
        store.clearSession(Source.BILIBILI)
        client.seed(store.cookieMap(Source.BILIBILI))
    }

    /** 拉取昵称与头像并落库，返回是否成功。 */
    suspend fun refreshProfile(): Boolean = withContext(Dispatchers.IO) {
        val profile = client.userProfile() ?: return@withContext false
        if (profile.name.isNotBlank()) store.saveDisplayName(Source.BILIBILI, profile.name)
        if (profile.avatar.isNotBlank()) store.saveAvatar(Source.BILIBILI, profile.avatar)
        NPLogger.d(TAG, "用户信息：name=${profile.name.ifBlank { "(空)" }} mid=${profile.mid}")
        profile.name.isNotBlank()
    }

    /** 校验登录是否还有效。 */
    suspend fun validateLogin(): Boolean = withContext(Dispatchers.IO) { client.validateLogin() }

    // ---------- 搜索 ----------

    suspend fun searchSongs(keyword: String, page: Int = 1): Paged<Song> = withContext(Dispatchers.IO) {
        runCatching {
            val (items, hasMore) = client.searchVideos(keyword, page)
            Paged(items.map { it.toSong() }, 0, page, hasMore)
        }.getOrElse {
            NPLogger.w(TAG, "搜索失败：${it.message}")
            Paged(emptyList(), 0, page, false)
        }
    }

    /**
     * UP 主空间投稿一页。[cursor] 是上一页返回的游标，首页传空串。
     * 失败时抛出异常，由页面显示错误与重试。
     */
    suspend fun upVideos(mid: String, cursor: String = ""): BiliUpPage {
        val parsedMid = mid.toLongOrNull() ?: 0L
        require(parsedMid > 0L) { AppLocale.string(R.string.net_missing_up_mid) }
        return client.upVideos(parsedMid, cursor)
    }

    /** 空间投稿条目转统一曲目模型：进页内播放页时拿 [Song.playId]（bvid）取流。 */
    fun upSongs(page: BiliUpPage): List<Song> = page.items.map { it.toSong() }

    /**
     * UP 主资料卡（关注 / 粉丝 / 获赞 / 大会员 / IP 归属地 / 简介）。
     * 失败抛出，由页面给错误与重试。
     */
    suspend fun upCard(mid: String): BiliUpCard {
        val parsedMid = mid.toLongOrNull() ?: 0L
        require(parsedMid > 0L) { AppLocale.string(R.string.net_missing_up_mid) }
        return client.upCard(parsedMid)
    }

    /**
     * UP 主动态流一页。[offset] 是上一页返回的游标，首页传空串。
     * 视频动态带 bvid，可在页内播放。
     */
    suspend fun upDynamics(mid: String, offset: String = ""): BiliDynamicPage {
        val parsedMid = mid.toLongOrNull() ?: 0L
        require(parsedMid > 0L) { AppLocale.string(R.string.net_missing_up_mid) }
        return client.upDynamics(parsedMid, offset)
    }

    // ---------- 评论 ----------

    /**
     * 视频评论区一页（[page] 从 1 起）。评论要先拿 aid，这里顺带解析一次视频信息。
     * [root] > 0 时取该楼中楼的回复列表。
     */
    suspend fun videoComments(bvid: String, page: Int = 1, root: Long = 0L): Paged<Comment> {
        val info = infoCache(bvid)
        val (items, hasMore) = client.videoComments(info.aid, page, PAGE_SIZE, root)
        return Paged(items, 0, page, hasMore)
    }

    /** 视频的 aid（评论、四连都要用），顺带取标题等信息。 */
    suspend fun videoInfo(bvid: String): BiliVideoInfo = infoCache(bvid)

    /**
     * 视频信息按 bvid 缓存在内存里：评论点赞 / 点踩 / 发表都会反复用到 aid，
     * 每次都重新拉一遍 `view` 会白发好几个请求。
     */
    private val infoLock = Mutex()
    private val infoCacheMap = linkedMapOf<String, BiliVideoInfo>()

    private suspend fun infoCache(bvid: String): BiliVideoInfo {
        infoCacheMap[bvid]?.let { return it }
        return infoLock.withLock {
            infoCacheMap[bvid]?.let { return@withLock it }
            client.videoInfo(bvid).also { infoCacheMap[bvid] = it }
        }
    }

    /** 发表 / 回复评论。[root] 为楼中楼根 rpid，[parent] 为被回复的 rpid。 */
    suspend fun sendComment(bvid: String, message: String, root: Long = 0L, parent: Long = 0L) {
        val info = infoCache(bvid)
        client.sendReply(info.aid, message, root, parent)
    }

    /**
     * 评论点赞 / 点踩。[like] 为 true 点赞、false 点踩；已赞再点赞、已踩再点踩会自动取消。
     * [current] 是这条评论当前的 likeAction（0=无 / 1=赞 / 2=踩）。
     */
    suspend fun commentAction(bvid: String, commentId: String, like: Boolean, current: Int): Boolean {
        val info = infoCache(bvid)
        val rpid = commentId.toLongOrNull() ?: return false
        val action = when {
            // 点赞
            like && current == 1 -> 0 // 已赞 → 取消
            like && current == 2 -> 1 // 已踩 → 切成赞（B 站会自动取消踩）
            like -> 1
            // 点踩
            !like && current == 2 -> -2 // 已踩 → 取消
            !like && current == 1 -> 2 // 已赞 → 切成踩
            else -> 2
        }
        return client.replyAction(info.aid, rpid, action)
    }

    // ---------- 一键三连 / 四连 ----------

    /**
     * 一键三连（可加转发）：只执行勾选的项目，每步独立、互不影响，返回逐步结果供 UI 展示。
     *
     * [coins] 是投币枚数（0 表示不投币，接口按 1~2 收），[like] / [favorite] / [repost]
     * 为 false 时跳过对应步骤。收藏用登录账号的默认收藏夹（`attr & 1`），没有就跳过；
     * 转发需要视频的动态 id（`view` 的 `data.dynamic`），拿不到就跳过。
     */
    suspend fun quad(
        bvid: String,
        like: Boolean = true,
        coins: Int = 2,
        favorite: Boolean = true,
        repost: Boolean = false,
    ): List<BiliQuadStep> {
        val info = infoCache(bvid)
        val aid = info.aid
        if (aid <= 0L) throw IllegalStateException(AppLocale.string(R.string.net_missing_video_id))

        val steps = mutableListOf<BiliQuadStep>()

        if (like) steps += runStep(AppLocale.string(R.string.net_quad_like)) { client.archiveLike(aid) }
        if (coins > 0) steps += runStep(AppLocale.string(R.string.net_quad_coin)) { client.coinAdd(aid, multiply = coins) }
        if (favorite) steps += runStep(AppLocale.string(R.string.net_quad_favorite)) {
            val folders = client.createdFavFolders(userId)
            val folder = folders.firstOrNull { it.isDefault } ?: folders.firstOrNull()
                ?: error(AppLocale.string(R.string.net_no_fav_folder))
            client.favDeal(aid, listOf(folder.mediaId))
        }
        if (repost) steps += runStep(AppLocale.string(R.string.net_quad_repost)) {
            if (info.dynamicId <= 0L) error(AppLocale.string(R.string.net_no_dynamic_repost))
            client.repostDynamic(info.dynamicId, info.ownerMid, aid, info.title)
        }
        return steps
    }

    private suspend fun runStep(name: String, block: suspend () -> Unit): BiliQuadStep = try {
        block()
        BiliQuadStep(name, ok = true)
    } catch (t: Throwable) {
        NPLogger.w(TAG, "四连「$name」失败：${t.message}")
        BiliQuadStep(name, ok = false, message = t.message ?: AppLocale.string(R.string.net_failed))
    }

    // ---------- 播放 ----------

    /**
     * 取音频直链。[cid] 为空时用视频第一集；音质跟随设置里的 B 站音质。
     * 注意：B 站 CDN 要求 `Referer: https://www.bilibili.com`，
     * 由 `PlaybackService` 的数据源头统一注入。
     */
    suspend fun audioUrl(bvid: String, cid: String): String = withContext(Dispatchers.IO) {
        if (bvid.isBlank()) return@withContext ""
        val url = runCatching { client.audioUrl(bvid, cid, biliAudioQualityId()) }.getOrElse {
            NPLogger.w(TAG, "取流失败：${it.message}")
            ""
        }
        // 统一按真实直链判（拿不到时客户端可能给出 "null" 之类占位值）
        if (isDirectUrl(url)) url else ""
    }

    /** 设置的 B 站音质 → dash audio id（30216=64K / 30232=132K / 30280=192K），auto 为 0（最高带宽）。 */
    private fun biliAudioQualityId(): Int = when (store.biliQuality) {
        "64" -> 30216
        "132" -> 30232
        "192" -> 30280
        else -> 0
    }

    /**
     * 取投稿的画面 + 声音直链（应用内观看用）。取不到返回 null。
     * 同样要求 `Referer: https://www.bilibili.com`，由播放器的数据源注入。
     */
    suspend fun videoStreams(bvid: String, cid: String): BiliVideoStreams? {
        if (bvid.isBlank()) return null
        val streams = runCatching { client.videoStreams(bvid, cid) }.getOrElse {
            NPLogger.w(TAG, "取视频流失败：${it.message}")
            null
        } ?: return null
        // 统一按真实直链判（拿不到时客户端可能给出 "null" 之类占位值）
        return streams.takeIf { isDirectUrl(it.videoUrl) }
    }

    // ---------- 收藏夹 / 我喜欢 ----------

    /** 登录账号创建的收藏夹。 */
    suspend fun createdFolders(): List<Playlist> =
        folders("bili_created_folders") { mid -> client.createdFavFolders(mid) }

    /** 登录账号订阅的收藏夹。 */
    suspend fun collectedFolders(): List<Playlist> =
        folders("bili_collected_folders") { mid -> client.collectedFavFolders(mid) }

    private suspend fun folders(
        cacheKey: String,
        load: suspend (Long) -> List<BiliFavFolder>,
    ): List<Playlist> = withContext(Dispatchers.IO) {
        val mid = userId
        if (mid <= 0L) return@withContext emptyList()
        runCatching {
            load(mid).map { folder ->
                Playlist(
                    id = folder.mediaId.toString(),
                    name = folder.title,
                    cover = folder.cover,
                    intro = folder.intro,
                    author = "",
                    songCount = folder.count,
                    source = Source.BILIBILI,
                )
            }
        }.getOrElse {
            NPLogger.w(TAG, "获取收藏夹失败：${it.message}")
            emptyList()
        }
            .takeIf { it.isNotEmpty() }
            ?.also { store.cachePut(cacheKey, JsonCodecs.playlistsToJson(it)) }
            ?: JsonCodecs.playlistsFromJson(store.cacheGet(cacheKey))
    }

    /**
     * B 站没有官方「我喜欢」列表，这里用**默认收藏夹**（`attr & 1`）承载，
     * 找不到默认收藏夹时退回第一个创建的收藏夹；离线时回退上次缓存。
     */
    suspend fun likesPlaylist(): Playlist? = withContext(Dispatchers.IO) {
        val cacheKey = "bili_likes"
        val cached = runCatching { JSONObject(store.cacheGet(cacheKey)) }.getOrNull()
        runCatching {
            val mid = userId
            if (mid <= 0L) return@runCatching null
            val created = client.createdFavFolders(mid)
            val folder = created.firstOrNull { it.isDefault } ?: created.firstOrNull() ?: return@runCatching null
            Playlist(
                id = folder.mediaId.toString(),
                name = folder.title,
                cover = folder.cover,
                intro = folder.intro,
                songCount = folder.count,
                source = Source.BILIBILI,
            ).also { store.cachePut(cacheKey, JsonCodecs.playlistToJson(it).toString()) }
        }.getOrElse {
            NPLogger.w(TAG, "获取「我喜欢」失败：${it.message}")
            JsonCodecs.playlistFromJson(cached)
        }
    }

    /** 收藏夹元信息（详情页标题/封面用）。 */
    suspend fun folderInfo(mediaId: String): Playlist? = withContext(Dispatchers.IO) {
        runCatching {
            val parsedId = mediaId.toLongOrNull() ?: return@runCatching null
            client.favFolder(parsedId)?.let { folder ->
                Playlist(
                    id = folder.mediaId.toString(),
                    name = folder.title,
                    cover = folder.cover,
                    intro = folder.intro,
                    songCount = folder.count,
                    source = Source.BILIBILI,
                )
            }
        }.getOrNull()
    }

    /** 收藏夹内容（只保留视频条目）。 */
    suspend fun folderTracks(mediaId: String, page: Int = 1, pageSize: Int = PAGE_SIZE): Paged<Song> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parsedId = mediaId.toLongOrNull() ?: return@runCatching Paged<Song>(emptyList(), 0, page, false)
                val result = client.favFolderContents(parsedId, page, pageSize)
                Paged(result.items.map { it.toSong() }, result.folder?.count ?: 0, page, result.hasMore)
            }.getOrElse {
                NPLogger.w(TAG, "获取收藏夹内容失败：${it.message}")
                Paged(emptyList(), 0, page, false)
            }
        }

    private fun BiliFavItem.toSong(): Song = Song(
        id = bvid,
        name = title,
        artist = upperName,
        artistId = upperMid.takeIf { it > 0L }?.toString().orEmpty(),
        artistIds = listOfNotNull(upperMid.takeIf { it > 0L }?.toString()),
        cover = cover,
        durationMs = durationSec * 1000L,
        source = Source.BILIBILI,
        playId = bvid,
    )

    // ---------- 扫码登录 ----------

    @Volatile
    private var pendingQrKey: String = ""

    /** 申请二维码，返回待扫码内容（登录页自行渲染成图）。 */
    suspend fun createQrContent(): String = withContext(Dispatchers.IO) {
        val session = runCatching { client.qrGenerate() }
            .onFailure { NPLogger.w(TAG, "申请二维码失败：${it.message}") }
            .getOrNull() ?: return@withContext ""
        pendingQrKey = session.key
        session.url
    }

    /** 轮询扫码状态；成功后落地凭证。 */
    suspend fun checkQr(key: String = pendingQrKey): QrStatus = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext QrStatus.Failed(AppLocale.string(R.string.net_qr_expired))
        runCatching {
            val result = client.qrPoll(key)
            when (result.code) {
                0 -> {
                    store.saveCookies(Source.BILIBILI, client.cookies())
                    // 昵称与头像随后的 refreshProfile() 拉取，这里不写占位值
                    QrStatus.Success(store.displayName(Source.BILIBILI).ifBlank { AppLocale.string(R.string.net_bili_default_name) })
                }
                86090 -> QrStatus.Scanned
                86038 -> QrStatus.Failed(AppLocale.string(R.string.net_qr_expired))
                else -> QrStatus.Waiting
            }
        }.getOrElse {
            NPLogger.w(TAG, "扫码状态查询失败：${it.message}")
            QrStatus.Waiting
        }
    }

    // ---------- 直播 ----------

    /** 首页「正在直播」；两条源都挂了就返回空表，首页据此整块隐藏。 */
    suspend fun liveRooms(page: Int = 1, pageSize: Int = 20): List<BiliLiveRoom> =
        runCatching { client.liveRooms(page, pageSize) }
            .onFailure { NPLogger.w(TAG, "获取直播列表失败：${it.message}") }
            .getOrDefault(emptyList())

    /** 进房间要的详情（标题/封面/主播头像/看过人数）。 */
    suspend fun liveRoomInfo(roomId: Long): BiliLiveRoom = client.liveRoomInfo(roomId)

    /** 直播流地址，已按偏好排好序，取第一个就能播。 */
    suspend fun liveStreams(roomId: Long): List<BiliLiveStream> = client.liveStreams(roomId)

    /** 主播 mid → 他自己的直播间；该用户没开过直播间返回 null。 */
    suspend fun liveBrief(mid: Long): BiliLiveBrief? = client.liveBrief(mid)

    /**
     * 弹幕弹道；拿不到就退回官方公开弹幕服务器匿名读，
     * 保证进房至少能看到别人在说什么。
     */
    suspend fun liveDmInfo(roomId: Long): BiliLiveDmInfo =
        runCatching { client.liveDmInfo(roomId) }.getOrElse {
            NPLogger.w(TAG, "获取弹幕通道失败，改用公开地址：${it.message}")
            BiliLiveDmInfo(token = "", hosts = listOf(FALLBACK_LIVE_DM_HOST))
        }

    /** 进房先拉的最近弹幕，拉不到就空着等实时的。 */
    suspend fun liveDmHistory(roomId: Long): List<BiliLiveMsg> = client.liveDmHistory(roomId)

    /** 直播间发言；未登录 / 被禁言会抛错，由界面提示。 */
    suspend fun sendLiveMsg(roomId: Long, message: String) = client.sendLiveMsg(roomId, message)

    /** 主播是否已被我关注（房间页头像右侧的关注按钮）。 */
    suspend fun liveRelation(fid: Long): Boolean =
        runCatching { client.liveRelation(fid) }
            .onFailure { NPLogger.w(TAG, "查询关注状态失败：${it.message}") }
            .getOrDefault(false)

    /** 改关注；[follow] 为真则关注，否则取关。 */
    suspend fun liveRelationModify(fid: Long, follow: Boolean): Boolean =
        runCatching { client.liveRelationModify(fid, follow) }.getOrElse {
            NPLogger.w(TAG, "改关注失败：${it.message}")
            throw it
        }
}
