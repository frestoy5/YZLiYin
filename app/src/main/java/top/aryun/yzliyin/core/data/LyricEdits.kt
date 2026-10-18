package top.aryun.yzliyin.core.data

import top.aryun.yzliyin.core.AppGraph
import java.io.File
import java.security.MessageDigest

/**
 * 用户在播放页「编辑歌词」里改过的歌词，按曲目 key 落盘。
 *
 * 存的是 LRC 原文：**文件存在就表示这首歌用我写的歌词**（内容为空表示这首歌没歌词，
 * 不再回落到源站），删掉文件即恢复源站歌词。
 * 放在 filesDir 而不是 cacheDir，设置里的「清缓存」不会把用户手写的内容一起清掉。
 */
object LyricEdits {

    private const val DIR_NAME = "lyric-edits"

    /** 读出编辑过的 LRC；没编辑过返回 null。 */
    fun read(key: String): String? =
        fileFor(key)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    /** 保存编辑（空字符串表示这首歌没歌词）。 */
    fun save(key: String, lrc: String) {
        val file = fileFor(key) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(lrc)
        }
    }

    /** 恢复源站歌词：删掉编辑文件即可。 */
    fun clear(key: String) {
        fileFor(key)?.let { runCatching { it.delete() } }
    }

    private fun fileFor(key: String): File? {
        val ctx = runCatching { AppGraph.applicationContext }.getOrNull() ?: return null
        return File(File(ctx.filesDir, DIR_NAME), md5(key) + ".lrc")
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
