package top.aryun.yzliyin.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.core.model.Source

/**
 * 扫描手机里已有的音乐文件（MediaStore），产出直接放进「曲库 → 本地」的 [Song]。
 *
 * **不复制文件**：曲目 key 是 `local:<mediaId>`，播放时 [AudioCache.cachedSource]
 * 直接拼出 `content://media/...` 喂给播放器，磁盘一个字节都不多占。
 * 用户删掉原文件后，[aliveIds] 负责把这条记录从本地列表里剔掉。
 */
object LocalScan {

    /** 本机音乐的 key 前缀：`local:<mediaId>`（Song.key = `source.id + ":" + id`）。 */
    const val KEY_PREFIX = "local:"

    /** Android 13 起是 READ_MEDIA_AUDIO，再往老是 READ_EXTERNAL_STORAGE。 */
    fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        @Suppress("DEPRECATION")
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun hasPermission(context: Context): Boolean = runCatching {
        context.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 某个 MediaStore 音频 id 对应的播放地址（纯拼装，不需要 Context）。 */
    fun contentUri(mediaId: String): String? = mediaId.toLongOrNull()?.let {
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it).toString()
    }

    /**
     * 扫一遍本机音乐。
     *
     * 返回 `null` 表示**没扫成**（没权限 / 查询失败），调用方据此提示开权限；
     * 返回空列表才是真的「一首都没找到」。
     */
    suspend fun scan(context: Context): List<Song>? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null

        val selection = buildString {
            append(MediaStore.Audio.Media.DURATION)
            append(" > 0")
            // Android 10+ 正在落盘、还没发布完的文件不该列出来
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ")
                append(MediaStore.Audio.Media.IS_PENDING)
                append(" = 0")
            }
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
        )

        var queried = false
        val songs = ArrayList<Song>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                queried = true
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                while (c.moveToNext()) {
                    val id = c.getString(iId) ?: continue
                    val duration = c.getLong(iDuration)
                    if (duration <= 0L) continue
                    songs += Song(
                        id = id,
                        name = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: id,
                        artist = c.getString(iArtist)
                            ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                            .orEmpty(),
                        albumName = c.getString(iAlbum).orEmpty(),
                        cover = c.getLong(iAlbumId)
                            .takeIf { it > 0 }
                            ?.let { albumArtUri(it) }
                            .orEmpty(),
                        durationMs = duration,
                        source = Source.LOCAL,
                    )
                }
            }
        }
        if (queried) songs else null
    }

    /**
     * 这些本机音乐的 media id 还在不在。
     *
     * 返回 `null` 表示**查不了**（没权限、查询失败），调用方应原样保留列表，
     * 否则用户一收回权限，所有扫描记录就会被当成失效一次性清空。
     */
    fun aliveIds(context: Context, songs: List<Song>): Set<String>? {
        if (songs.isEmpty()) return emptySet()
        if (!hasPermission(context)) return null
        val alive = HashSet<String>()
        val queried = runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                null,
                null,
                null,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                while (c.moveToNext()) c.getString(iId)?.let(alive::add)
                true
            } ?: false
        }.getOrDefault(false)
        return if (queried) alive else null
    }

    private fun albumArtUri(albumId: Long): String = ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"),
        albumId,
    ).toString()
}
