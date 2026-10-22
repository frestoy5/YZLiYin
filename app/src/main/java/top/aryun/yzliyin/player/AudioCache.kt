package top.aryun.yzliyin.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.youtube.platform.isYouTubeGoogleVideoHost
import top.aryun.yzliyin.util.isDirectUrl
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 播放缓存：播放过的曲目在后台落盘，下次播放直接读本地文件，不再等网络。
 *
 * 默认写在 `cacheDir/audio`；用户在「设置 → 存储与缓存 → 存储文件管理」里选了文件夹后，
 * 新缓存改写到那个目录（SAF tree uri，见 [AudioTreeStore]）。**两个目录的文件都算数**：
 * 查询、统计、删除、淘汰一律两边一起处理，换目录不会让老文件失效，也不用搬文件。
 *
 * 文件按 `md5(song.key)` 命名；总容量超限时按最后修改时间淘汰，上限可以在
 * 「设置 → 存储与缓存 → 设置自定义缓存」里改（0 = 用默认值）。
 * 设置页「清除缓存」会连同其它缓存一起清掉。同一首并发下载自动去重。
 */
object AudioCache {

    private const val TAG = "AudioCache"

    /** 默认的缓存总容量上限（字节）；设置里自定义过就以 [maxBytes] 为准。 */
    private const val DEFAULT_MAX_BYTES = 800L * 1024 * 1024

    private const val DIR_NAME = "audio"

    /** 完成的缓存文件扩展名（不含点）。 */
    private const val SUFFIX = ".audio"

    /**
     * 手动下载（曲库多选 / 歌单下载）落盘用的后缀，比自动缓存多一个 `.dl`。
     *
     * 手动下载是「我要留着」，不参与 [evictIfNeeded] 的自动淘汰；[maxBytes] 管的
     * 只有自动缓存。否则把上限调小（比如 1MB）时，刚下完几 MB 的歌会被立刻删掉，
     * 「曲库 → 本地」永远是空的，看着像一直卡在下载中。
     */
    private const val DOWNLOADED_SUFFIX = ".dl$SUFFIX"

    /** 自定义目录里手动下载条目的 base 名后缀（那边按 base 名存，不带 `.audio`）。 */
    private const val DL_BASE_SUFFIX = ".dl"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 需要 Referer 的 B 站 CDN 域名后缀（与 PlaybackService 保持一致）。 */
    private val BILI_CDN_HOSTS = listOf(
        "bilivideo.com", "bilivideo.cn", "biliapi.net", "hdslb.com",
        "mountaintoys.cn", "akamaized.net",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var dir: File? = null

    /** 自定义缓存文件夹（null = 用默认的 [dir]）。 */
    private var tree: AudioTreeStore? = null

    /** 正在下载的曲目 key（并发去重）。 */
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    fun init(context: Context) {
        val app = context.applicationContext
        if (dir == null) {
            val d = File(app.cacheDir, DIR_NAME).apply { mkdirs() }
            // 上次进程中断留下的半成品直接清掉
            d.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
            dir = d
        }
        // 恢复上次选的自定义文件夹（权限在选的时候已持久化，这里只校验还灵不灵）
        if (tree == null) {
            val saved = storedDirUri()
            if (saved.isNotBlank()) {
                val store = buildTree(app, saved)
                if (store != null) {
                    tree = store
                    store.cleanupPartials()
                } else {
                    NPLogger.w(TAG, "上次选的缓存目录已失效，回落默认目录")
                    runCatching { AppGraph.store.audioCacheDirUri = "" }
                }
            }
        }
    }

    /** 当前的缓存目录设置：空串 = 默认的应用缓存目录。 */
    fun dirUri(): String = storedDirUri()

    private fun storedDirUri(): String =
        runCatching { AppGraph.store.audioCacheDirUri }.getOrDefault("")

    /**
     * 换缓存目录：[uri] 是 `ACTION_OPEN_DOCUMENT_TREE` 给的 tree uri，空串表示回到默认目录。
     * 返回是否切换成功（拿不到读写权限或 uri 解析不了时维持原状）。
     */
    fun setCacheDir(context: Context, uri: String): Boolean {
        val app = context.applicationContext
        if (uri.isBlank()) {
            tree = null
            runCatching { AppGraph.store.audioCacheDirUri = "" }
            return true
        }
        // 持久化授权：不存的话重启后 uri 还在但读写权限没了，缓存会整片失效
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { NPLogger.w(TAG, "取缓存目录权限失败：${it.message}") }

        val store = buildTree(app, uri) ?: return false
        tree = store
        store.cleanupPartials()
        runCatching { AppGraph.store.audioCacheDirUri = uri }
        return true
    }

    private fun buildTree(context: Context, uri: String): AudioTreeStore? =
        runCatching { AudioTreeStore(context.contentResolver, Uri.parse(uri)) }
            .getOrNull()
            ?.takeIf { it.valid }

    /** 复核目录里的实际文件（进设置 / 曲库页时调一次，把外部增删同步进统计与索引）。 */
    fun refresh() {
        runCatching { tree?.reindex() }
    }

    /**
     * 这首歌本地缓存的读取地址（`content://` 或 `file://`），没有则返回 null。
     * 播放层直接把它当 URL 用，所以默认目录与自定义目录都要认。
     */
    fun cachedSource(key: String): String? {
        // 曲库「扫描歌曲」进来的本机音乐：key = local:<mediaId>，地址直接拼，
        // 既不在缓存目录、也不占缓存上限
        if (key.startsWith(LocalScan.KEY_PREFIX)) {
            return LocalScan.contentUri(key.removePrefix(LocalScan.KEY_PREFIX))
        }
        treeBases(key).forEach { base -> tree?.sourceOf(base)?.let { return it } }
        val file = localFile(key) ?: return null
        return Uri.fromFile(file).toString()
    }

    /** 已缓存的本地文件（只认默认目录；自定义目录里的不是 [File]，走 [cachedSource]）。 */
    private fun localFile(key: String): File? =
        existingFile(key)?.takeIf { it.isFile && it.length() > 0L }

    /** 已缓存音频占用的总字节数（两个目录合计）。 */
    fun totalBytes(): Long =
        (dir?.listFiles()?.sumOf { if (it.isFile && it.name.endsWith(SUFFIX)) it.length() else 0L }
            ?: 0L) + (tree?.totalBytes() ?: 0L)

    /** 已缓存的音频文件数（两个目录合计）。 */
    fun cachedCount(): Int =
        (dir?.listFiles()?.count { it.isFile && it.name.endsWith(SUFFIX) } ?: 0) +
            (tree?.count() ?: 0)

    /** 删除某首歌的本地缓存文件（两个目录、两种后缀都找一遍），返回是否真的删掉了。 */
    fun remove(key: String): Boolean {
        val fromTree = treeBases(key).map { tree?.remove(it) ?: false }.any { it }
        val fromFile = listOfNotNull(downloadedFile(key), autoFile(key))
            .filter { it.isFile && it.delete() }
            .isNotEmpty()
        return fromTree || fromFile
    }

    fun isDownloading(key: String): Boolean = inFlight.containsKey(key)

    /** 播放时顺手缓存（不阻塞、不关心结果）。 */
    fun cacheInBackground(key: String, url: String) {
        if (url.isBlank() || url.startsWith("file:")) return
        if (cachedSource(key) != null || isDownloading(key)) return
        scope.launch { runCatching { download(key, url) } }
    }

    /**
     * 下载并落盘；返回是否成功（已有缓存也算成功）。
     * 同一首已在下载时等待其完成（批量下载/播放缓存并发时的兜底）。
     */
    suspend fun download(key: String, url: String, downloaded: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
        if (cachedSource(key) != null) return@withContext true
        if (url.isBlank() || url.startsWith("file:")) return@withContext false
        // 只认 http(s) 直链："null"、残缺链接等一律按失败处理，
        // 下载路径不许把异常抛给调用方（无版权曲就是这么把 App 闪退的）
        if (!isDirectUrl(url)) return@withContext false
        if (inFlight.putIfAbsent(key, true) != null) {
            var waited = 0L
            while (inFlight.containsKey(key) && waited < 120_000L) {
                delay(200)
                waited += 200
            }
            return@withContext cachedSource(key) != null
        }
        try {
            val target = (if (downloaded) downloadedFile(key) else autoFile(key))
                ?: return@withContext false
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.delete()

            val request = try {
                Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .apply {
                        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
                        when {
                            BILI_CDN_HOSTS.any { host.endsWith(it) } ->
                                header("Referer", "https://www.bilibili.com")

                            isYouTubeGoogleVideoHost(host) -> {
                                header("Referer", "https://www.youtube.com/")
                                header("Origin", "https://www.youtube.com")
                            }
                        }
                    }
                    .build()
            } catch (e: IllegalArgumentException) {
                NPLogger.w(TAG, "链接无法解析，放弃下载：$url")
                return@withContext false
            }

            // 网络 IO 异常（断网、连接被重置、403 之类）按失败上报，不能炸到上层
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.body ?: return@withContext false
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
            } catch (e: IOException) {
                tmp.delete()
                NPLogger.w(TAG, "下载中断：${e.message}")
                return@withContext false
            }

            if (tmp.length() <= 0L) {
                tmp.delete()
                return@withContext false
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            // 选了自定义文件夹：默认目录只当中转，搬过去后删掉原件
            tree?.let { t ->
                if (t.import(target)) target.delete()
            }
            // 手动下载要留着，不拿去跟自动缓存抢容量
            if (!downloaded) evictIfNeeded()
            true
        } finally {
            inFlight.remove(key)
        }
    }

    /** 当前生效的容量上限：设置里自定义过就用自定义值，否则 [DEFAULT_MAX_BYTES]。 */
    fun maxBytes(): Long {
        val custom = runCatching { AppGraph.store.audioCacheLimitBytes }.getOrDefault(0L)
        return if (custom > 0) custom else DEFAULT_MAX_BYTES
    }

    /** 未自定义时的默认容量上限（设置页用来说明「0 = 用默认值」）。 */
    fun defaultMaxBytes(): Long = DEFAULT_MAX_BYTES

    /** 改完上限立刻按新值淘汰一次，不用等下一次下载。 */
    fun trimToLimit() = runCatching { evictIfNeeded() }

    /** 超出容量上限时删除最旧的文件（两个目录按修改时间一起排序）。 */
    private fun evictIfNeeded() {
        val max = maxBytes()
        var total = totalBytes()
        while (total > max) {
            val victim = oldest() ?: return
            if (!victim.delete()) return
            total -= victim.size
        }
    }

    /** 两个目录里最旧的那个自动缓存文件；手动下载的（`.dl`）不参与淘汰。 */
    private fun oldest(): Victim? {
        val fromFile = dir?.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) && !it.name.endsWith(DOWNLOADED_SUFFIX) }
            ?.minByOrNull { it.lastModified() }
            ?.let { Victim(it.lastModified(), it.length(), it, null) }
        val fromTree = tree?.entries()
            ?.filterKeys { !it.endsWith(DL_BASE_SUFFIX) }
            ?.minByOrNull { it.value.modified }
            ?.let { (name, entry) -> Victim(entry.modified, entry.size, null, name) }
        return listOfNotNull(fromFile, fromTree).minByOrNull { it.modified }
    }

    /** 淘汰候选：文件在默认目录用 [file] 删，在自定义目录按 [name] 删。 */
    private data class Victim(val modified: Long, val size: Long, val file: File?, val name: String?)

    private fun Victim.delete(): Boolean =
        if (file != null) file.delete() else name?.let { tree?.remove(it) } ?: false

    /** 自动缓存的落盘位置（参与淘汰）。 */
    private fun autoFile(key: String): File? {
        val d = dir ?: return null
        return File(d, md5(key) + SUFFIX)
    }

    /** 手动下载的落盘位置（不参与淘汰）。 */
    private fun downloadedFile(key: String): File? {
        val d = dir ?: return null
        return File(d, md5(key) + DOWNLOADED_SUFFIX)
    }

    /** 默认目录里已有的文件：优先认手动下载的那份（更可能是用户要的版本）。 */
    private fun existingFile(key: String): File? =
        listOfNotNull(downloadedFile(key), autoFile(key)).firstOrNull { it.isFile }

    /** 自定义目录里的条目 base 名：自动缓存是 md5，手动下载多一个 `.dl`。 */
    private fun treeBases(key: String): List<String> = listOf(md5(key), md5(key) + DL_BASE_SUFFIX)

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
