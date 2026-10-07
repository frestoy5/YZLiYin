package top.aryun.yzliyin.core

import android.content.Context
import top.aryun.yzliyin.core.data.SessionStore
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.core.net.BiliClient
import top.aryun.yzliyin.core.net.KugouClient
import top.aryun.yzliyin.core.net.NeteaseClient

/** 全局依赖容器（Application 初始化一次）。 */
object AppGraph {

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

    fun init(context: Context) {
        if (::store.isInitialized) return
        val app = context.applicationContext
        store = SessionStore(app)
        netease = NeteaseClient().apply { setPersistedCookies(store.neteaseCookieMap()) }
        kugou = KugouClient(app).apply { seed(store.cookieMap(Source.KUGOU)) }
        bilibili = BiliClient().apply { seed(store.cookieMap(Source.BILIBILI)) }
    }
}
