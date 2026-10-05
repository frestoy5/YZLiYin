package top.aryun.yzliyin.core.model

/** 统一歌曲模型（数据源仅网易云音乐，[id] 即网易云歌曲 id）。 */
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
)

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
)

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
