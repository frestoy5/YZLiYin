package top.aryun.yzliyin.core.data

import org.json.JSONArray
import org.json.JSONObject
import top.aryun.yzliyin.core.model.Playlist
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source

/**
 * 离线缓存 / 播放会话的 JSON 编解码。
 *
 * 只覆盖「恢复展示」需要的字段；解析失败一律返回空，由调用方兜底。
 */
object JsonCodecs {

    fun songToJson(s: Song): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("artist", s.artist)
        put("artistId", s.artistId)
        put("artistIds", JSONArray(s.artistIds))
        put("albumId", s.albumId)
        put("albumName", s.albumName)
        put("cover", s.cover)
        put("durationMs", s.durationMs)
        put("vip", s.vip)
        put("source", s.source.id)
        put("playId", s.playId)
        put("playSubId", s.playSubId)
    }

    fun songFromJson(o: JSONObject?): Song? {
        o ?: return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val artistId = o.optString("artistId")
        return Song(
            id = id,
            name = o.optString("name"),
            artist = o.optString("artist"),
            artistId = artistId,
            // 旧版本的缓存没有这个字段，退回单个 artistId，避免回到「只能点第一个歌手」
            artistIds = o.optJSONArray("artistIds")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(artistId).filter { it.isNotBlank() },
            albumId = o.optString("albumId"),
            albumName = o.optString("albumName"),
            cover = o.optString("cover"),
            durationMs = o.optLong("durationMs", 0L),
            vip = o.optBoolean("vip", false),
            source = Source.fromId(o.optString("source")),
            playId = o.optString("playId"),
            playSubId = o.optString("playSubId"),
        )
    }

    fun songsToJson(songs: List<Song>): String =
        JSONArray().apply { songs.forEach { put(songToJson(it)) } }.toString()

    fun songsFromJson(text: String): List<Song> {
        if (text.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { songFromJson(arr.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun playlistToJson(p: Playlist): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("cover", p.cover)
        put("intro", p.intro)
        put("author", p.author)
        put("playCount", p.playCount)
        put("songCount", p.songCount)
        put("tags", JSONArray(p.tags))
        put("source", p.source.id)
    }

    fun playlistFromJson(o: JSONObject?): Playlist? {
        o ?: return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val tags = o.optJSONArray("tags")
        return Playlist(
            id = id,
            name = o.optString("name"),
            cover = o.optString("cover"),
            intro = o.optString("intro"),
            author = o.optString("author"),
            playCount = o.optLong("playCount", 0L),
            songCount = o.optInt("songCount", 0),
            tags = if (tags == null) emptyList() else (0 until tags.length()).map { tags.optString(it) },
            source = Source.fromId(o.optString("source")),
        )
    }

    fun playlistsToJson(playlists: List<Playlist>): String =
        JSONArray().apply { playlists.forEach { put(playlistToJson(it)) } }.toString()

    fun playlistsFromJson(text: String): List<Playlist> {
        if (text.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { playlistFromJson(arr.optJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
