package top.aryun.yzliyin.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Paged
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.QrStatus
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.util.NPLogger

/**
 * 哔哩哔哩门面，与 [NeteaseApi] 同一套约定。
 *
 * 曲目以视频为单位（一集 = 一首），播放取该视频的 DASH 音频轨。
 */
object BiliApi {

    private const val TAG = "BiliApi"
    private const val PAGE_SIZE = 20

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

    // ---------- 播放 ----------

    /**
     * 取音频直链。[cid] 为空时用视频第一集。
     * 注意：B 站 CDN 要求 `Referer: https://www.bilibili.com`，
     * 由 `PlaybackService` 的数据源头统一注入。
     */
    suspend fun audioUrl(bvid: String, cid: String): String = withContext(Dispatchers.IO) {
        if (bvid.isBlank()) return@withContext ""
        runCatching { client.audioUrl(bvid, cid) }.getOrElse {
            NPLogger.w(TAG, "取流失败：${it.message}")
            ""
        }
    }

    // ---------- 收藏夹 / 我喜欢 ----------

    /** 登录账号创建的收藏夹。 */
    suspend fun createdFolders(): List<Playlist> = folders { mid -> client.createdFavFolders(mid) }

    /** 登录账号订阅的收藏夹。 */
    suspend fun collectedFolders(): List<Playlist> = folders { mid -> client.collectedFavFolders(mid) }

    private suspend fun folders(
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
    }

    /**
     * B 站没有官方「我喜欢」列表，这里用**默认收藏夹**（`attr & 1`）承载，
     * 找不到默认收藏夹时退回第一个创建的收藏夹。
     */
    suspend fun likesPlaylist(): Playlist? = withContext(Dispatchers.IO) {
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
            )
        }.getOrElse {
            NPLogger.w(TAG, "获取「我喜欢」失败：${it.message}")
            null
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
        if (key.isBlank()) return@withContext QrStatus.Failed("二维码已失效，请刷新")
        runCatching {
            val result = client.qrPoll(key)
            when (result.code) {
                0 -> {
                    store.saveCookies(Source.BILIBILI, client.cookies())
                    // 昵称与头像随后的 refreshProfile() 拉取，这里不写占位值
                    QrStatus.Success(store.displayName(Source.BILIBILI).ifBlank { "B 站账号" })
                }
                86090 -> QrStatus.Scanned
                86038 -> QrStatus.Failed("二维码已过期，请刷新")
                else -> QrStatus.Waiting
            }
        }.getOrElse {
            NPLogger.w(TAG, "扫码状态查询失败：${it.message}")
            QrStatus.Waiting
        }
    }
}
