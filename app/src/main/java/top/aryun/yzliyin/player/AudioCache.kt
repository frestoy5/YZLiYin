package top.aryun.yzliyin.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.util.isDirectUrl
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 播放缓存：播放过的曲目在后台落盘，下次播放直接读本地文件，不再等网络。
 *
 * 文件按 `md5(song.key)` 命名，放在 `cacheDir/audio`；总容量超限时按最后修改时间淘汰，
 * 设置页「清除缓存」会连同其它缓存一起清掉。同一首并发下载自动去重。
 */
object AudioCache {

    private const val TAG = "AudioCache"

    /** 缓存总容量上限（字节）。 */
    private const val MAX_BYTES = 800L * 1024 * 1024

    private const val DIR_NAME = "audio"

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

    /** 正在下载的曲目 key（并发去重）。 */
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.applicationContext.cacheDir, DIR_NAME).apply { mkdirs() }
        // 上次进程中断留下的半成品直接清掉
        d.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
        dir = d
    }

    /** 已缓存的本地文件；没有则返回 null。 */
    fun cached(key: String): File? = fileFor(key)?.takeIf { it.isFile && it.length() > 0L }

    /** 已缓存音频占用的总字节数。 */
    fun totalBytes(): Long =
        dir?.listFiles()?.sumOf { if (it.isFile && it.name.endsWith(".audio")) it.length() else 0L }
            ?: 0L

    /** 已缓存的音频文件数。 */
    fun cachedCount(): Int =
        dir?.listFiles()?.count { it.isFile && it.name.endsWith(".audio") } ?: 0

    /** 删除某首歌的本地缓存文件，返回是否真的删掉了。 */
    fun remove(key: String): Boolean = fileFor(key)?.takeIf { it.delete() } != null

    fun isDownloading(key: String): Boolean = inFlight.containsKey(key)

    /** 播放时顺手缓存（不阻塞、不关心结果）。 */
    fun cacheInBackground(key: String, url: String) {
        if (url.isBlank() || url.startsWith("file:")) return
        if (cached(key) != null || isDownloading(key)) return
        scope.launch { runCatching { download(key, url) } }
    }

    /**
     * 下载并落盘；返回是否成功（已有缓存也算成功）。
     * 同一首已在下载时等待其完成（批量下载/播放缓存并发时的兜底）。
     */
    suspend fun download(key: String, url: String): Boolean = withContext(Dispatchers.IO) {
        if (cached(key) != null) return@withContext true
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
            return@withContext cached(key) != null
        }
        try {
            val target = fileFor(key) ?: return@withContext false
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.delete()

            val request = try {
                Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .apply {
                        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
                        if (BILI_CDN_HOSTS.any { host.endsWith(it) }) {
                            header("Referer", "https://www.bilibili.com")
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
            evictIfNeeded()
            true
        } finally {
            inFlight.remove(key)
        }
    }

    /** 超出容量上限时删除最旧的文件。 */
    private fun evictIfNeeded() {
        val files = dir?.listFiles()?.filter { it.isFile && it.name.endsWith(".audio") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_BYTES) return
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun fileFor(key: String): File? {
        val d = dir ?: return null
        return File(d, md5(key) + ".audio")
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
