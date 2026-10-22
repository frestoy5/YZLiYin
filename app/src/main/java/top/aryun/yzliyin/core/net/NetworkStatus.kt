package top.aryun.yzliyin.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.data.SessionStore
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络状态探测：连通性 + 实测延迟（毫秒）。
 *
 * 延迟用一次轻量 HTTP HEAD 请求的往返耗时近似（含 DNS / 建连 / 响应），
 * 连接变化时立即复测，平时按设置里的刷新间隔轮询（默认 15 秒）。
 * 轮询间隔与单次超时都在 设置 → 主题 里可调，改动下一次轮询即生效。
 */
object NetworkStatus {

    enum class Level { CHECKING, GOOD, FAIR, OFFLINE }

    /** 判定为「优秀」的延迟上限（毫秒），超过记为「中等」。 */
    private const val GOOD_MS = 200L

    /** AppGraph 还没起来时的兜底轮询间隔（毫秒）。 */
    private val defaultIntervalMs: Long
        get() = (SessionStore.NET_REFRESH_DEFAULT * 1000).toLong()

    /** 单次探测超时（毫秒），跟随设置项，取不到就用默认 2 秒。 */
    private val timeoutMs: Int
        get() = if (AppGraph.initialized) AppGraph.store.netProbeTimeoutMs else SessionStore.NET_PROBE_TIMEOUT_DEFAULT

    /** 轮询间隔（毫秒），跟随设置项；下限 10ms 对应 0.01 秒。 */
    private val intervalMs: Long
        get() = if (AppGraph.initialized) {
            (AppGraph.store.netRefreshSec * 1000).toLong().coerceAtLeast(10)
        } else {
            defaultIntervalMs
        }

    /** 探测目标：优先网易云域名，失败再试酷狗（两者都在常用网络下可达）。 */
    private val PROBE_HOSTS = listOf("https://music.163.com/", "https://www.kugou.com/")

    var level: Level by mutableStateOf(Level.CHECKING)
        private set

    /** 实测延迟；无网络或探测失败时为 null。 */
    var latencyMs: Long? by mutableStateOf(null)
        private set

    val offline: Boolean get() = level == Level.OFFLINE

    private var started = false
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Application 启动时调用一次；连接变化立即复测，平时按设置的间隔轮询。 */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app

        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { measure() }
                }

                override fun onLost(network: Network) {
                    scope.launch { measure() }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    scope.launch { measure() }
                }
            })
        }

        scope.launch {
            while (true) {
                measure()
                delay(intervalMs)
            }
        }
    }

    /** 立即复测一次（进入首页时调用）。 */
    fun refresh() {
        scope.launch { measure() }
    }

    private suspend fun measure() {
        if (!hasNetwork()) {
            latencyMs = null
            level = Level.OFFLINE
            return
        }
        val latency = ping()
        if (latency == null) {
            // 有连接但探测不通：视为无网络（连接受限 / 无外网）
            latencyMs = null
            level = Level.OFFLINE
        } else {
            latencyMs = latency
            level = if (latency <= GOOD_MS) Level.GOOD else Level.FAIR
        }
    }

    private fun hasNetwork(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun ping(): Long? {
        val timeout = timeoutMs
        for (host in PROBE_HOSTS) {
            val cost = runCatching {
                val conn = (URL(host).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = timeout
                    readTimeout = timeout
                    instanceFollowRedirects = false
                }
                val start = System.nanoTime()
                conn.responseCode
                val elapsed = (System.nanoTime() - start) / 1_000_000
                conn.disconnect()
                elapsed
            }.getOrNull()
            if (cost != null) return cost
        }
        return null
    }
}
