package top.aryun.yzliyin.youtube

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.data.SessionStore
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.youtube.api.YouTubeMusicClient
import top.aryun.yzliyin.youtube.api.YouTubeMusicPlaybackRepository
import top.aryun.yzliyin.youtube.auth.SharedPreferencesYouTubeCookieRotationStateStore
import top.aryun.yzliyin.youtube.auth.YouTubeAuthAutoRefreshManager
import top.aryun.yzliyin.youtube.auth.YouTubeAuthBundle
import top.aryun.yzliyin.youtube.auth.YouTubeAuthRepository
import top.aryun.yzliyin.youtube.auth.YouTubeAuthRotationWorker
import top.aryun.yzliyin.youtube.auth.YouTubeCookieRotator
import top.aryun.yzliyin.youtube.auth.YOUTUBE_MUSIC_ORIGIN
import top.aryun.yzliyin.youtube.platform.YouTubeFeatureDisabledException
import top.aryun.yzliyin.youtube.platform.YouTubeFeatureGate
import top.aryun.yzliyin.youtube.platform.buildYouTubeInnertubeRequestHeaders
import top.aryun.yzliyin.youtube.platform.buildYouTubePageRequestHeaders
import top.aryun.yzliyin.youtube.platform.buildYouTubeStreamRequestHeaders
import top.aryun.yzliyin.youtube.platform.isTrustedYouTubeHost
import top.aryun.yzliyin.youtube.platform.isYouTubeGoogleVideoHost
import top.aryun.yzliyin.youtube.platform.isYouTubeInnertubeHost
import top.aryun.yzliyin.youtube.settings.YouTubeSettingsRepository
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// 共享 client 的连接参数，取自 fork AppContainer 的 configureSharedOkHttpClient
private const val SHARED_HTTP_CONNECT_TIMEOUT_MS = 8_000L
private const val SHARED_HTTP_READ_TIMEOUT_MS = 20_000L
private const val SHARED_HTTP_CALL_TIMEOUT_MS = 0L
private const val SHARED_HTTP_MAX_IDLE_CONNECTIONS = 8
private const val SHARED_HTTP_KEEP_ALIVE_MINUTES = 5L

/**
 * YouTube 装配的 Service Locator（第 1 阶段：平台支撑 + 认证）。
 *
 * 对应 fork `core.di.AppContainer` 里 YouTube 相关的成员与 sharedOkHttpClient 拦截器；
 * 本项目各源 API 自带独立 OkHttp client，YouTube 也用自己这一份，不与他人混用。
 * 由 [top.aryun.yzliyin.core.AppGraph.init] 调用 [init] 启动。
 *
 * TODO(第 2 阶段收尾): fork 的 youtubeMusicDownloadPlaybackRepository（下载专用，
 *  callTimeout 20s）待第 3 阶段接下载管理器时再补。
 * TODO(第 3 阶段): fork 关闭 YouTube 时还会取消进行中的下载（AudioDownloadManager）。
 */
object YouTubeGraph {

    private const val TAG = "YouTubeGraph"

    private lateinit var appContext: Context

    @Volatile
    private var initialized = false

    @Volatile
    private var youtubeEnabled = true

    /** 装配是否完成（Worker 等早期路径需要兜底判断，等价 fork AppContainer.isInitialized()）。 */
    fun isInitialized(): Boolean = initialized

    /** 设置里的 YouTube 总开关（缓存自 [updateSettingsFrom]，等价 fork settingsRepo.youtubeEnabledFlow）。 */
    fun isYoutubeEnabled(): Boolean = youtubeEnabled

    /** YouTube 登录态仓库（EncryptedSharedPreferences 持久化）。 */
    val youtubeAuthRepo: YouTubeAuthRepository by lazy {
        YouTubeAuthRepository(appContext)
    }

    /** 前台触发的登录态自动刷新（WebView 静默续期）。 */
    val youtubeAuthAutoRefreshManager: YouTubeAuthAutoRefreshManager by lazy {
        YouTubeAuthAutoRefreshManager(
            context = appContext,
            authProvider = youtubeAuthRepo::getAuthOnce,
            authHealthProvider = youtubeAuthRepo::getAuthHealthOnce,
            authUpdater = youtubeAuthRepo::saveAuth,
            rotatedCookieUpdater = youtubeAuthRepo::mergeRotatedCookies
        )
    }

    /** Google RotateCookies 端点的纯 HTTP 轮换器（*PSIDTS 续期），Worker 也走它。 */
    internal val cookieRotator: YouTubeCookieRotator by lazy {
        YouTubeCookieRotator(
            stateStore = SharedPreferencesYouTubeCookieRotationStateStore(appContext)
        )
    }

    /** Innertube 客户端（bootstrap / 元数据 / 搜索底座），对应 fork AppContainer.youtubeMusicClient。 */
    private val youtubeMusicClientDelegate = lazy {
        YouTubeMusicClient(
            authRepo = youtubeAuthRepo,
            okHttpClient = sharedOkHttpClient,
            authAutoRefreshManager = youtubeAuthAutoRefreshManager
        )
    }

    val youtubeMusicClient: YouTubeMusicClient
        get() = youtubeMusicClientDelegate.value

    /**
     * 播放仓库（取链：多客户端回退 / 签名 / PO Token），对应 fork AppContainer.youtubeMusicPlaybackRepository。
     * TODO(第 3 阶段): fork 还有下载专用实例（callTimeout 20s 的 youtubeMusicDownloadPlaybackRepository）。
     */
    private val playbackRepositoryDelegate = lazy {
        YouTubeMusicPlaybackRepository(
            okHttpClient = sharedOkHttpClient,
            settings = YouTubeSettingsRepository(),
            authProvider = youtubeAuthRepo::getAuthOnce,
            authAutoRefreshManager = youtubeAuthAutoRefreshManager,
            applicationContext = appContext
        )
    }

    val playbackRepository: YouTubeMusicPlaybackRepository
        get() = playbackRepositoryDelegate.value

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * YouTube 专用共享 OkHttpClient。
     *
     * 拦截器移植自 fork AppContainer.sharedOkHttpClient：按 host 注入三套请求头
     * （googlevideo 直链 / innertube / 页面），合并响应 Set-Cookie 回鉴权仓库，
     * 并在功能开关关闭时直接抛 [YouTubeFeatureDisabledException]。
     */
    val sharedOkHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host.lowercase()
                if (!isTrustedYouTubeHost(host)) {
                    return@addInterceptor chain.proceed(request)
                }
                if (!YouTubeFeatureGate.isEnabled()) {
                    throw YouTubeFeatureDisabledException()
                }

                val auth = youtubeAuthRepo.getAuthOnce().normalized()
                val originalHeaders = linkedMapOf<String, String>().apply {
                    request.headers.names().forEach { name ->
                        request.header(name)?.let { value -> put(name, value) }
                    }
                }
                val resolvedHeaders = when {
                    isYouTubeGoogleVideoHost(host) -> auth.buildYouTubeStreamRequestHeaders(
                        original = originalHeaders,
                        refererOrigin = request.header("Referer")
                            .orEmpty()
                            .removeSuffix("/")
                            .ifBlank {
                                request.header("Origin")
                                    .orEmpty()
                                    .removeSuffix("/")
                            }
                            .ifBlank { auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN } },
                        streamUrl = request.url.toString()
                    )
                    isYouTubeInnertubeRequest(request) -> auth.buildYouTubeInnertubeRequestHeaders(
                        original = originalHeaders,
                        authorizationOrigin = auth.origin.ifBlank { YOUTUBE_MUSIC_ORIGIN },
                        includeAuthorization = true
                    )
                    else -> auth.buildYouTubePageRequestHeaders(
                        original = originalHeaders
                    )
                }
                val requestBuilder = request.newBuilder()
                request.headers.names().forEach { name ->
                    requestBuilder.removeHeader(name)
                }
                resolvedHeaders.forEach { (name, value) ->
                    requestBuilder.header(name, value)
                }
                val response = chain.proceed(requestBuilder.build())
                // OkHttp 4.x：headers.values("Set-Cookie") 逐条取（fork 用的 OkHttp 5.x 同名 API 一致）
                val setCookieHeaders = response.headers.values("Set-Cookie")
                if (setCookieHeaders.isNotEmpty()) {
                    runCatching {
                        youtubeAuthRepo.mergeCookieUpdates(setCookieHeaders)
                    }.onFailure { error ->
                        NPLogger.w(TAG, "Failed to merge YouTube Set-Cookie headers", error)
                    }
                }
                response
            }
            .connectTimeout(SHARED_HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SHARED_HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 播放等长请求共用此客户端，不能用总时限截断（callTimeout=0 表示不启用）
            .callTimeout(SHARED_HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectionPool(
                ConnectionPool(
                    SHARED_HTTP_MAX_IDLE_CONNECTIONS,
                    SHARED_HTTP_KEEP_ALIVE_MINUTES,
                    TimeUnit.MINUTES
                )
            )
            .retryOnConnectionFailure(true)
        builder.build()
    }

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        updateSettingsFrom(SessionStore(appContext))
        startYouTubeAuthObserver()
        if (isYoutubeEnabled()) {
            // 长期不开 App 时没有任何前台流程会去续期，靠这个周期任务把会话保活
            YouTubeAuthRotationWorker.schedulePeriodicRotation(appContext)
            // 匿名播放也要预热 bootstrap/player.js（fork warmYouTubePlaybackOnAppStart）
            playbackRepository.warmBootstrapAsync()
        }
    }

    /**
     * 鉴权变化联动（fork AppContainer.startYouTubeAuthObserver）：
     * 登录态变了就清 bootstrap 缓存、清鉴权绑定的缓存（不取消在途播放请求）、
     * 逐出连接池并预热下一次 bootstrap。
     */
    private fun startYouTubeAuthObserver() {
        youtubeAuthRepo.authFlow
            .drop(1)
            .distinctUntilChangedBy { bundle -> bundle.toWarmBootstrapKey() }
            .onEach { _ ->
                if (!YouTubeFeatureGate.isEnabled()) {
                    return@onEach
                }
                youtubeMusicClient.clearBootstrapCache()
                // 只移除旧请求引用, 避免 auth 恢复成功时把当前播放请求自己取消掉
                playbackRepository.clearAuthBoundCaches(cancelInFlightPlayableAudio = false)
                // TODO(第 3 阶段): downloadPlaybackRepository.clearAuthBoundCaches(false)
                sharedOkHttpClient.connectionPool.evictAll()
                playbackRepository.warmBootstrapAsync()
            }
            .launchIn(scope)
    }

    /**
     * 设置改动入口（本阶段由 AppGraph 在 init 与设置变更时调用；UI 第 5 阶段接线）。
     *
     * 等价 fork AppContainer.startSettingsObserver 里 youtubeEnabledFlow 的分支：
     * 关 → 关 FeatureGate、停轮换 Worker、清 bootstrap/鉴权缓存、取消在途 YouTube 请求；
     * 开 → 开 FeatureGate、重排轮换 Worker、预热播放。
     * TODO(第 3 阶段): fork 关闭时还会取消进行中的下载并清下载缓存，待下载层落地。
     */
    fun updateSettingsFrom(store: SessionStore) {
        val enabled = store.youtubeEnabled
        val wasEnabled = YouTubeFeatureGate.isEnabled()
        youtubeEnabled = enabled
        YouTubeFeatureGate.update(enabled)
        if (!initialized) return
        if (wasEnabled && !enabled) {
            YouTubeAuthRotationWorker.cancelPeriodicRotation(appContext)
            if (youtubeMusicClientDelegate.isInitialized()) {
                youtubeMusicClientDelegate.value.clearBootstrapCache()
            }
            if (playbackRepositoryDelegate.isInitialized()) {
                playbackRepositoryDelegate.value.clearAuthBoundCaches()
            }
            cancelYouTubeCalls()
        } else if (!wasEnabled && enabled) {
            YouTubeAuthRotationWorker.schedulePeriodicRotation(appContext)
            playbackRepository.warmBootstrapAsync()
        }
    }

    /** 关闭 YouTube 时把排队/在途的 YouTube 请求全部取消（fork AppContainer.cancelYouTubeCalls）。 */
    private fun cancelYouTubeCalls() {
        if (!::appContext.isInitialized) return
        val calls = sharedOkHttpClient.dispatcher.queuedCalls() +
            sharedOkHttpClient.dispatcher.runningCalls()
        calls.filter { call -> isTrustedYouTubeHost(call.request().url.host) }
            .forEach { call -> call.cancel() }
    }

    private fun isYouTubeInnertubeRequest(request: Request): Boolean {
        val host = request.url.host.lowercase()
        val path = request.url.encodedPath.lowercase()
        return isYouTubeInnertubeHost(host) || path.startsWith("/youtubei/")
    }
}

// LOGIN_INFO / SIDCC 经常抖动, 不进 key, 免得把可复用的播放缓存也清掉（fork AppContainer 同款）
private data class YouTubeAuthWarmBootstrapKey(
    val hasEffectiveAuth: Boolean,
    val authorization: String,
    val xGoogAuthUser: String,
    val origin: String,
    val userAgent: String,
)

private fun YouTubeAuthBundle.toWarmBootstrapKey(): YouTubeAuthWarmBootstrapKey {
    val normalized = normalized()
    return YouTubeAuthWarmBootstrapKey(
        hasEffectiveAuth = normalized.hasEffectiveAuth(),
        authorization = normalized.authorization,
        xGoogAuthUser = normalized.xGoogAuthUser,
        origin = normalized.origin,
        userAgent = normalized.userAgent
    )
}
