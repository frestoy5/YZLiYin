package top.aryun.yzliyin.core

import android.content.Context
import okhttp3.OkHttpClient
import top.aryun.yzliyin.core.data.SessionStore
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliClient
import top.aryun.yzliyin.core.net.KugouClient
import top.aryun.yzliyin.core.net.NeteaseClient
import top.aryun.yzliyin.listentogether.ListenTogetherSessionManager
import top.aryun.yzliyin.listentogether.data.ListenTogetherPrefs
import top.aryun.yzliyin.listentogether.network.http.ListenTogetherApi
import top.aryun.yzliyin.listentogether.network.ws.ListenTogetherWebSocketClient
import top.aryun.yzliyin.listentogether.player.ListenTogetherPlayer
import top.aryun.yzliyin.youtube.YouTubeGraph
import java.util.concurrent.TimeUnit

/** 全局依赖容器（Application 初始化一次）。 */
object AppGraph {

    lateinit var applicationContext: Context
        private set

    lateinit var store: SessionStore
        private set

    /** 网易云官方接口客户端。 */
    lateinit var netease: NeteaseClient
        private set

    /** 酷狗接口客户端（自研签名层，见 KugouClient）。 */
    lateinit var kugou: KugouClient
        private set

    /** 哔哩哔哩接口客户端。 */
    lateinit var bilibili: BiliClient
        private set

    /** 一起听本地偏好（服务端地址、昵称、身份 UUID、房间默认设置）。 */
    lateinit var listenTogetherPrefs: ListenTogetherPrefs
        private set

    /** 一起听会话管理器（构造时自启，负责 HTTP/WS 与房间同步）。 */
    lateinit var listenTogether: ListenTogetherSessionManager
        private set

    /** 一起听 HTTP 客户端（设置页测服可用性等）。 */
    lateinit var listenTogetherApi: ListenTogetherApi
        private set

    /** YouTube 平台支撑 + 认证装配（Service Locator，见 [YouTubeGraph]）。 */
    lateinit var youtube: YouTubeGraph
        private set

    /** 是否已完成初始化（一起听保活等路径会在极早期被调用，需要兜底判断）。 */
    val initialized: Boolean
        get() = ::store.isInitialized

    fun init(context: Context) {
        if (::store.isInitialized) return
        val app = context.applicationContext
        applicationContext = app
        store = SessionStore(app)
        netease = NeteaseClient().apply { setPersistedCookies(store.neteaseCookieMap()) }
        kugou = KugouClient(app).apply { seed(store.cookieMap(Source.KUGOU)) }
        bilibili = BiliClient().apply { seed(store.cookieMap(Source.BILIBILI)) }

        // YouTube 装配（独立 OkHttp client + 鉴权仓库 + 会话轮换 Worker），并按当前设置同步 FeatureGate
        YouTubeGraph.init(app)
        YouTubeGraph.updateSettingsFrom(store)
        youtube = YouTubeGraph

        listenTogetherPrefs = ListenTogetherPrefs(app)
        // WebSocket 需要 15s 心跳探测半开连接；HTTP 与 WS 共用同一连接池
        val listenTogetherClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
        listenTogetherApi = ListenTogetherApi(listenTogetherClient)
        listenTogether = ListenTogetherSessionManager(
            api = listenTogetherApi,
            webSocketClient = ListenTogetherWebSocketClient(listenTogetherClient)
        )
        ListenTogetherPlayer.init(app)
        ListenTogetherPlayer.attachManager(listenTogether)
    }
}
