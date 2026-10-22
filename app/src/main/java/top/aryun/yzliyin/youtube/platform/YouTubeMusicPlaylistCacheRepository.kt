package top.aryun.yzliyin.youtube.platform

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.platform.youtube/YouTubeMusicPlaylistCacheRepository
 * Created: 2026/7/2
 */

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.aryun.yzliyin.util.NPLogger
import java.io.File
import java.security.MessageDigest

data class CachedYouTubeMusicPlaylistTrack(
    val videoId: String,
    val name: String,
    val artist: String,
    val albumName: String,
    val durationMs: Long,
    val coverUrl: String
)

data class CachedYouTubeMusicPlaylistDetail(
    val browseId: String,
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val creatorName: String? = null,
    val coverUrl: String,
    val trackCount: Int,
    val firstPageSignature: String,
    val tracks: List<CachedYouTubeMusicPlaylistTrack>,
    val savedAtMs: Long = System.currentTimeMillis()
)

/**
 * YouTube Music 歌单浏览结果缓存。
 *
 * 适配说明（第 1 阶段）：fork 版落盘走 Room（NeriUserDataDatabase + PlatformPlaylistCacheRoomStore）
 * 并用 Gson 序列化旧文件；本项目尚未引入 Room/Gson，这里先退化为纯文件缓存（org.json 序列化，
 * 目录与文件名规则与 fork 的 legacy 文件一致），对外 API（read/save/clear/importLegacyCaches）
 * 保持不变。
 * TODO(后续阶段): 引入 Room 后按 fork 恢复 store 路径与 legacy 文件迁移逻辑。
 */
class YouTubeMusicPlaylistCacheRepository private constructor(
    private val cacheDir: File
) {

    constructor(context: Context) : this(
        cacheDir = File(context.applicationContext.filesDir, CACHE_DIR_NAME)
    )

    fun read(browseId: String): CachedYouTubeMusicPlaylistDetail? {
        val file = cacheFile(browseId)
        if (!file.exists()) return null
        return runCatching {
            readFile(file, browseId)
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read YouTube Music playlist cache: browseId=$browseId", error)
        }.getOrNull()
    }

    fun save(cache: CachedYouTubeMusicPlaylistDetail) {
        runCatching {
            writeFile(cache)
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to save YouTube Music playlist cache: browseId=${cache.browseId}", error)
        }
    }

    fun clear(browseId: String) {
        runCatching {
            cacheFile(browseId).delete()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to clear YouTube Music playlist cache: browseId=$browseId", error)
        }
    }

    fun importLegacyCaches() {
        // 纯文件缓存模式下文件本身就是主存储，无需迁移；
        // TODO(后续阶段): Room 化后恢复「读旧文件 → 写 Room → 删旧文件」的导入流程。
    }

    private fun readFile(file: File, expectedBrowseId: String?): CachedYouTubeMusicPlaylistDetail? {
        return parseJson(file.readText(Charsets.UTF_8))
            ?.takeIf { cache -> expectedBrowseId == null || cache.browseId == expectedBrowseId }
    }

    private fun cacheFile(browseId: String): File {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, "${sha256(browseId)}.json")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun writeFile(cache: CachedYouTubeMusicPlaylistDetail) {
        cacheFile(cache.browseId).writeText(toJson(cache), Charsets.UTF_8)
    }

    private fun toJson(cache: CachedYouTubeMusicPlaylistDetail): String {
        return JSONObject().apply {
            put("browseId", cache.browseId)
            put("playlistId", cache.playlistId)
            put("title", cache.title)
            put("subtitle", cache.subtitle)
            if (cache.creatorName != null) put("creatorName", cache.creatorName) else remove("creatorName")
            put("coverUrl", cache.coverUrl)
            put("trackCount", cache.trackCount)
            put("firstPageSignature", cache.firstPageSignature)
            put("savedAtMs", cache.savedAtMs)
            put("tracks", JSONArray().apply {
                cache.tracks.forEach { track ->
                    put(JSONObject().apply {
                        put("videoId", track.videoId)
                        put("name", track.name)
                        put("artist", track.artist)
                        put("albumName", track.albumName)
                        put("durationMs", track.durationMs)
                        put("coverUrl", track.coverUrl)
                    })
                }
            })
        }.toString()
    }

    private fun parseJson(raw: String): CachedYouTubeMusicPlaylistDetail? {
        return runCatching {
            val root = JSONObject(raw)
            val tracksJson = root.optJSONArray("tracks") ?: JSONArray()
            val tracks = buildList {
                for (i in 0 until tracksJson.length()) {
                    val track = tracksJson.optJSONObject(i) ?: continue
                    add(
                        CachedYouTubeMusicPlaylistTrack(
                            videoId = track.optString("videoId"),
                            name = track.optString("name"),
                            artist = track.optString("artist"),
                            albumName = track.optString("albumName"),
                            durationMs = track.optLong("durationMs"),
                            coverUrl = track.optString("coverUrl")
                        )
                    )
                }
            }
            CachedYouTubeMusicPlaylistDetail(
                browseId = root.optString("browseId"),
                playlistId = root.optString("playlistId"),
                title = root.optString("title"),
                subtitle = root.optString("subtitle"),
                creatorName = root.optString("creatorName").takeIf { it.isNotBlank() },
                coverUrl = root.optString("coverUrl"),
                trackCount = root.optInt("trackCount"),
                firstPageSignature = root.optString("firstPageSignature"),
                tracks = tracks,
                savedAtMs = root.optLong("savedAtMs", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    private companion object {
        const val PLATFORM = "youtube_music"
        const val TAG = "YouTubeMusicPlaylistCache"
        const val CACHE_DIR_NAME = "youtube_music_playlist_cache"
    }
}
