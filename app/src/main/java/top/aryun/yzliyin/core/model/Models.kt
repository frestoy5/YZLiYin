package top.aryun.yzliyin.core.model

/**
 * 统一歌曲模型。
 *
 * [id] 是**该源自己的**歌曲 id（网易云数字 id / 酷狗音频 hash / B 站 bvid），
 * 跨源不保证唯一，凡用作集合键或去重键的地方一律用 [key]。
 *
 * [playId] / [playSubId] 存放取链所需的源特有参数：
 *  - 酷狗：[playId] = 音频 FileHash，[playSubId] = album_audio_id
 *  - B 站：[playId] = bvid，[playSubId] = cid
 *  - 网易云：留空，直接用 [id]
 */
data class Song(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String = "",
    val albumId: String = "",
    val albumName: String = "",
    val cover: String = "",
    val durationMs: Long = 0L,
    /** 该曲在网易云是否需要会员（fee=1/4）。 */
    val vip: Boolean = false,
    val source: Source = Source.NETEASE,
    val playId: String = "",
    val playSubId: String = "",
) {
    /** 跨源唯一的稳定键，用于队列去重、已取链集合与列表 key。 */
    val key: String get() = source.id + ":" + id
}

data class Album(
    val id: String,
    val name: String,
    val cover: String = "",
    val artist: String = "",
    val artistId: String = "",
    val publishDate: String = "",
    val intro: String = "",
    val songCount: Int = 0,
)

data class Artist(
    val id: String,
    val name: String,
    val avatar: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val intro: String = "",
)

data class Playlist(
    val id: String,
    val name: String,
    val cover: String = "",
    val intro: String = "",
    val author: String = "",
    val playCount: Long = 0,
    val songCount: Int = 0,
    val tags: List<String> = emptyList(),
    val source: Source = Source.NETEASE,
) {
    val key: String get() = source.id + ":" + id
}

data class Rank(
    val id: String,
    val name: String,
    val cover: String = "",
    val updateFrequency: String = "",
    val intro: String = "",
    val tag: String = "",
)

data class Comment(
    val id: String,
    val user: String,
    val avatar: String = "",
    val content: String,
    val time: String = "",
    val likeCount: Int = 0,
    val isHot: Boolean = false,
    val reply: String = "",
)

data class LyricLine(val timeMs: Long, val text: String)

/** 分页结果 */
data class Paged<T>(val items: List<T>, val total: Int, val page: Int, val hasMore: Boolean)

/** 网易云扫码登录三步状态 */
sealed class QrStatus {
    data object Waiting : QrStatus()
    data object Scanned : QrStatus()
    data class Success(val nickname: String) : QrStatus()
    data class Failed(val reason: String) : QrStatus()
}
