package top.aryun.yzliyin.core.net

import top.aryun.yzliyin.core.model.Paged
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.youtube.YouTubeGraph
import top.aryun.yzliyin.youtube.api.YouTubeMusicLibraryPlaylist
import top.aryun.yzliyin.youtube.api.YouTubeMusicPlaylistTrack
import top.aryun.yzliyin.youtube.api.YouTubeMusicSearchFilter
import top.aryun.yzliyin.youtube.api.YouTubeMusicSearchResult
import top.aryun.yzliyin.youtube.auth.YouTubeAuthBundle
import top.aryun.yzliyin.youtube.platform.YouTubeFeatureDisabledException

/**
 * YouTube 数据源门面：对内是 NeriPlayer 移植过来的 Innertube 栈
 * （[top.aryun.yzliyin.youtube.api.YouTubeMusicPlaybackRepository] 等），
 * 对外只暴露本项目播放 / 搜索 / 曲库需要的几个方法，风格与 NeteaseApi / KugouApi 一致。
 *
 * Song.id 存 videoId，key 为 `youtube:<videoId>`；取链失败返回空串，
 * 由 PlaybackResolver 统一转成面向用户的失败原因。
 */
object YouTubeApi {

    private const val TAG = "YouTubeApi"

    /**
     * 取可播放的音频直链。内部走多客户端回退（登录态 WEB_REMIX→TV_HTML5→…，
     * 匿名 VISION_OS→ANDROID_VR→…），签名 / n 参数与 PO Token 由移植栈处理。
     */
    suspend fun songUrl(videoId: String): String {
        if (videoId.isBlank()) return ""
        return runCatching {
            YouTubeGraph.playbackRepository.getBestPlayableAudio(videoId)?.url.orEmpty()
        }.onFailure { error ->
            // 功能开关关闭时会抛 internal 的 YouTubeFeatureDisabledException，属预期、不告警
            if (error::class.simpleName != "YouTubeFeatureDisabledException") {
                NPLogger.w(TAG, "songUrl failed: videoId=$videoId, ${error.message}", error)
            }
        }.getOrDefault("")
    }

    /** 当前是否已登录 YouTube（看加密存储里的 AuthBundle 有没有身份 Cookie）。 */
    fun isLoggedIn(): Boolean = runCatching {
        YouTubeGraph.youtubeAuthRepo.getAuthOnce().hasLoginCookies()
    }.getOrDefault(false)

    /** 登录 Cookie（登录页 WebView 里用，供鉴权仓库与 CookieStore 同步）。 */
    fun currentAuth(): YouTubeAuthBundle = YouTubeGraph.youtubeAuthRepo.getAuthOnce()

    /** 保存登录态（登录页抓到 Cookie 后调用）。 */
    fun saveAuth(bundle: YouTubeAuthBundle) {
        runCatching { YouTubeGraph.youtubeAuthRepo.saveAuth(bundle) }
            .onFailure { NPLogger.w(TAG, "saveAuth failed: ${it.message}", it) }
    }

    /** 退出登录：清鉴权仓库与 WebView Cookie。 */
    fun logout() {
        runCatching { YouTubeGraph.youtubeAuthRepo.clear() }
            .onFailure { NPLogger.w(TAG, "logout failed: ${it.message}", it) }
    }

    /** 搜索：走 Innertube（WEB_REMIX）搜索，登录与匿名均可（匿名走 bootstrap 游客态）。 */
    suspend fun searchSongs(keyword: String, page: Int = 1): Paged<Song> {
        if (keyword.isBlank()) return Paged(emptyList(), 0, page, false)
        return runCatching {
            val results = YouTubeGraph.youtubeMusicClient.search(
                query = keyword,
                limit = SEARCH_LIMIT,
                filter = YouTubeMusicSearchFilter.Song,
            )
            val songs = results.map { it.toSong() }
            Paged(songs, songs.size, page, false)
        }.onFailure { error ->
            if (error !is YouTubeFeatureDisabledException) {
                NPLogger.w(TAG, "searchSongs failed: $keyword, ${error.message}", error)
            }
        }.getOrDefault(Paged(emptyList(), 0, page, false))
    }

    /** 我的播放列表（曲库 tab 用）；未登录返回空列表。 */
    suspend fun userPlaylists(): List<Playlist> {
        if (!isLoggedIn()) return emptyList()
        // 不吞异常：失败要能传到曲库页展示原因（登录态明明有却拉不到歌单时不再静默）
        return YouTubeGraph.youtubeMusicClient.getLibraryPlaylists().map { it.toPlaylist() }
    }

    /** 某个播放列表里的曲目（一次拉全，YouTube Music 的列表页在 continuation 里已由客户端摊平）。 */
    suspend fun playlistSongs(playlistId: String): List<Song> {
        return runCatching {
            YouTubeGraph.youtubeMusicClient
                .getPlaylistDetail(browseId = playlistId)
                .tracks.map { it.toSong() }
        }.onFailure { NPLogger.w(TAG, "playlistSongs failed: $playlistId, ${it.message}", it) }
            .getOrDefault(emptyList())
    }

    /** 歌单详情（曲库 → YouTube 列表点进去用）。 */
    suspend fun playlistDetail(playlistId: String): Playlist? {
        return runCatching {
            val detail = YouTubeGraph.youtubeMusicClient.getPlaylistDetail(browseId = playlistId)
            Playlist(
                id = detail.playlistId.ifBlank { detail.browseId },
                name = detail.title,
                cover = detail.coverUrl,
                intro = detail.subtitle,
                songCount = detail.trackCount ?: detail.tracks.size,
                source = Source.YOUTUBE,
            )
        }.onFailure { NPLogger.w(TAG, "playlistDetail failed: $playlistId, ${it.message}", it) }
            .getOrNull()
    }

    /** 歌单曲目分页；YouTube Music 是 continuation 翻页，先按单页返回。 */
    suspend fun playlistTracks(playlistId: String, page: Int = 1, pageSize: Int = 100): Paged<Song> {
        val songs = if (page == 1) playlistSongs(playlistId) else emptyList()
        return Paged(songs, songs.size, page, false)
    }

    /** 单次搜索拉取的条目上限（YouTube Music 首页一般 20~30 条）。 */
    private const val SEARCH_LIMIT = 50
}

/** Innertube 搜索结果 → 本项目 Song；id 即 videoId，取链直接拿它调 /player。 */
private fun YouTubeMusicSearchResult.toSong() = Song(
    id = videoId,
    name = title,
    artist = artist,
    albumName = album,
    cover = coverUrl,
    durationMs = durationMs,
    source = Source.YOUTUBE,
)

/** 播放列表曲目 → Song，字段含义同上。 */
private fun YouTubeMusicPlaylistTrack.toSong() = Song(
    id = videoId,
    name = title,
    artist = artist,
    albumName = album,
    cover = coverUrl,
    durationMs = durationMs,
    source = Source.YOUTUBE,
)

/** 我的播放列表 → 本项目 Playlist；id 用 playlistId（浏览详情时作 browseId 传回客户端）。 */
private fun YouTubeMusicLibraryPlaylist.toPlaylist() = Playlist(
    id = playlistId.ifBlank { browseId },
    name = title,
    cover = coverUrl,
    intro = subtitle,
    songCount = trackCount ?: 0,
    source = Source.YOUTUBE,
)
