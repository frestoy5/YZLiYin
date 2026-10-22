package top.aryun.yzliyin.player

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import top.aryun.yzliyin.util.NPLogger
import java.io.File
import java.io.IOException

/**
 * 音频缓存的「自定义文件夹」后端：把 `ACTION_OPEN_DOCUMENT_TREE` 选中的 tree uri
 * 当成缓存目录用。
 *
 * Android 10 起直接往共享存储里写文件会被拒绝（除非申请 MediaStore 或「所有文件访问」），
 * 通用做法只有拿到 tree uri 再走 DocumentsContract 建文档 / 改名 / 删除，所以这里
 * 不返回 [File]，只返回能直接喂给播放器的 `content://` uri。
 *
 * 目录内容只在 [reindex] 时列一遍，之后由本类自己维护（写入 / 删除都同步更新索引），
 * 这样每次查缓存就不必再走一遍 binder；进设置页 / 曲库页时调用方会主动 [reindex] 复核。
 */
internal class AudioTreeStore(
    private val resolver: ContentResolver,
    val uri: Uri,
) {

    /** 目录里一个文件的原始记录。[name] 是完整文件名（如 `a1b2….audio`）。 */
    private data class Row(val name: String, val docId: String, val size: Long, val modified: Long)

    /** 已完成的缓存条目：base 名（去掉 `.audio` 的 md5）→ 文档 id / 大小 / 修改时间。 */
    data class Entry(val docId: String, val size: Long, val modified: Long)

    /** 树根文档 id，形如 `primary:Music/YZLiYin`；拿不到说明这个 tree uri 是坏的。 */
    private val treeDocId: String = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
    }.getOrDefault("")

    /** uri 是否解析得出树根；无效的 tree 一律不启用，回落默认缓存目录。 */
    val valid: Boolean get() = treeDocId.isNotEmpty()

    /** 已完成的缓存：base 名（去掉 `.audio` 的 md5）→ 文档。 */
    private var index: MutableMap<String, Entry>? = null

    private fun childrenUri(docId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)

    private fun docUriOf(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(uri, docId)

    /** 目录里的条目；没列过就先列一次。 */
    fun entries(): Map<String, Entry> = index ?: reindex()

    /** 重新扫一遍目录：外部（文件管理器等）增删文件后由调用方在 UI 打开时复核。 */
    fun reindex(): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        queryRows().forEach { row ->
            if (!row.name.endsWith(SUFFIX_AUDIO)) return@forEach
            out[row.name.removeSuffix(SUFFIX_AUDIO)] = Entry(row.docId, row.size, row.modified)
        }
        index = out
        return out
    }

    /** 清掉上次下载中断留下的半成品（`xxx.audio.part`），启用目录时调一次。 */
    fun cleanupPartials() {
        queryRows().filter { it.name.endsWith(PART_SUFFIX) }
            .forEach { row -> deleteDoc(row.docId) }
    }

    /** 这首歌在自定义目录里的读取地址；没有则返回 null。 */
    fun sourceOf(base: String): String? =
        entries()[base]?.let { docUriOf(it.docId).toString() }

    fun totalBytes(): Long = entries().values.sumOf { it.size }

    fun count(): Int = entries().size

    /** 删掉某首歌的缓存文件，返回是否真的删掉了。 */
    fun remove(base: String): Boolean {
        val entry = entries()[base] ?: return false
        val ok = deleteDoc(entry.docId)
        if (ok) index?.remove(base)
        return ok
    }

    /**
     * 把写好的本地文件搬进这个目录（写完先在这里落盘，再删掉默认目录里的原件）。
     * 同名旧文件先删掉，否则部分 provider 会造出 `xxx (1).audio` 这种重名副本。
     */
    fun import(file: File): Boolean {
        if (!valid || !file.isFile) return false
        val display = file.name
        val base = display.removeSuffix(SUFFIX_AUDIO)
        if (base.isEmpty()) return false
        remove(base)

        val created = runCatching {
            DocumentsContract.createDocument(
                resolver,
                docUriOf(treeDocId),
                MIME_BINARY,
                display,
            )
        }.getOrNull() ?: return false

        val copied = runCatching {
            file.inputStream().use { input ->
                val out = resolver.openOutputStream(created, "w")
                    ?: throw IOException("openOutputStream 返回 null")
                out.use { output -> input.copyTo(output) }
            }
        }.isSuccess
        if (!copied) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            NPLogger.w(TAG, "写入自定义缓存目录失败，改回默认目录")
            return false
        }

        val docId = runCatching { DocumentsContract.getDocumentId(created) }.getOrNull()
        if (docId == null) {
            // 拿不到文档 id 就没法把它登记进索引，宁可删掉也别留个查不到的孤儿文件
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            return false
        }
        index?.put(base, Entry(docId, file.length(), System.currentTimeMillis()))
        return true
    }

    private fun deleteDoc(docId: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, docUriOf(docId))
    }.getOrDefault(false)

    /** 列一遍目录（`.audio` 与 `.part` 都算上，由调用方各自筛选）。 */
    private fun queryRows(): List<Row> {
        if (!valid) return emptyList()
        val out = ArrayList<Row>()
        runCatching {
            resolver.query(
                childrenUri(treeDocId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    // 只认本 App 的命名（md5.audio / md5.audio.part），别人丢的文件不进索引
                    if (!name.endsWith(SUFFIX_AUDIO) && !name.endsWith(PART_SUFFIX)) continue
                    out += Row(
                        name = name,
                        docId = cursor.getString(0),
                        size = cursor.getLong(2).coerceAtLeast(0L),
                        modified = cursor.getLong(3),
                    )
                }
            }
        }.onFailure { NPLogger.w(TAG, "列出自定义缓存目录失败：${it.message}") }
        return out
    }

    companion object {
        private const val TAG = "AudioTreeStore"

        /** [AudioCache] 落盘文件的扩展名（不含点）。 */
        const val SUFFIX_AUDIO = ".audio"

        /** 半成品扩展名（含点），下载中断时留在目录里等清理。 */
        const val PART_SUFFIX = ".part"

        private const val MIME_BINARY = "application/octet-stream"
    }
}
