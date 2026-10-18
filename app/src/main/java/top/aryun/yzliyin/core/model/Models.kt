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
    /**
     * 这首歌**全部**歌手的 id，顺序与 [artist] 里按 `/` 切开的名字一一对应。
     * 只留 [artistId]（第一个歌手）会让点歌手名永远跳到同一个人，
     * 合作曲想进另一位歌手的主页就没了入口。
     */
    val artistIds: List<String> = emptyList(),
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

    /**
     * 歌手名列表，与 [artistIds] 按下标一一对应。
     * [artist] 由各源拼成 `甲/乙`，这里还原成列表供「选哪个歌手」弹窗展示。
     * 个别源只给名字不给 id，此时 [artistIds] 可能更短，按下标取不到就当作没有主页可跳。
     */
    val artistNames: List<String>
        get() = artist.split('/').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(artist.trim()).filter { it.isNotEmpty() } }

    /**
     * 可点进主页的歌手（名字, id），已剔除没有 id 的条目。
     * 名字取自 [artistNames]、id 取自同下标的 [artistIds]，两边一起截到较短的一边，
     * 避免某个歌手缺 id 时后面的全部错位成前一个人的主页。
     */
    val artistCandidates: List<Pair<String, String>>
        get() {
            val names = artistNames
            return (0 until minOf(names.size, artistIds.size))
                .map { names[it] to artistIds[it] }
                .filter { (_, id) -> id.isNotBlank() }
        }
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

/**
 * 一条评论（网易云与 B 站共用）。
 *
 * B 站特有字段（[likeAction] / [dislikeCount] / [replyCount]）一律带默认值，
 * 网易云那边构造时不用关心；B 站侧 [id] 存的是评论的 rpid（十进制字符串），
 * 点赞 / 点踩 / 回复都靠它定位。
 */
data class Comment(
    val id: String,
    val user: String,
    val avatar: String = "",
    val content: String,
    val time: String = "",
    val likeCount: Int = 0,
    val isHot: Boolean = false,
    val reply: String = "",
    /** 当前登录用户对这条的动作：0=无、1=已赞、2=已踩（B 站才有）。 */
    val likeAction: Int = 0,
    /** 踩数（B 站才有，普通 UI 一般不展示）。 */
    val dislikeCount: Int = 0,
    /** 楼中楼回复数（B 站才有）。 */
    val replyCount: Int = 0,
)

/**
 * 一行歌词。
 *
 * [translation] 是这行对应的**中文译文**（接口自带，不是现翻的）：
 * 网易云来自 `tlyric`，酷狗来自 KRC 的 `language` 标签，按时间戳配对而来；
 * 没有译文或原文本来就是中文时为空。
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
)

/** 分页结果 */
data class Paged<T>(val items: List<T>, val total: Int, val page: Int, val hasMore: Boolean)

/**
 * 用户在播放页手工改过的曲目信息（歌名 / 歌手 / 封面）。
 *
 * [name] / [artist] / [cover] 是**当前生效**的编辑值；
 * [baseName] / [baseArtist] / [baseCover] 是首次编辑前源站给的原始值，
 * 编辑面板的「恢复原信息」按钮据此还原，不会把上一次的编辑当成原值。
 */
data class SongInfoEdit(
    val name: String,
    val artist: String,
    val cover: String,
    val baseName: String,
    val baseArtist: String,
    val baseCover: String,
)

/**
 * 跨源跟随取到的展示信息：跟随源里同一首歌的封面 / 歌手。
 * [source] 记录取自哪个源，跟随源一切换，旧记录读出来即被忽略（无需清理）。
 */
data class FollowMeta(
    val cover: String,
    val artist: String,
    val source: String,
)

/** 取链结果：[url] 为空时 [reason] 是面向用户的原因说明。 */
data class ResolveResult(
    val url: String,
    val reason: String = "",
    /** 实际取到播放链接的客户端；跨源找音源时可能与曲目自身的 [Source] 不同。 */
    val source: Source? = null,
    /** 链接是否来自本机已缓存的音频文件（此时 [source] 为曲目原本的源）。 */
    val fromCache: Boolean = false,
) {
    val ok: Boolean get() = url.isNotBlank()
}

/** 网易云扫码登录三步状态 */
sealed class QrStatus {
    data object Waiting : QrStatus()
    data object Scanned : QrStatus()
    data class Success(val nickname: String) : QrStatus()
    data class Failed(val reason: String) : QrStatus()
}
