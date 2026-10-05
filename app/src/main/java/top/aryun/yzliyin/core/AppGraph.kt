package top.aryun.yzliyin.core

import android.content.Context
import top.aryun.yzliyin.core.data.SessionStore
import top.aryun.yzliyin.core.net.NeteaseClient

/** 全局依赖容器（Application 初始化一次）。 */
object AppGraph {

    lateinit var store: SessionStore
        private set

    /** 网易云官方接口客户端（数据源唯一）。 */
    lateinit var netease: NeteaseClient
        private set

    fun init(context: Context) {
        if (::store.isInitialized) return
        val app = context.applicationContext
        store = SessionStore(app)
        netease = NeteaseClient().apply { setPersistedCookies(store.neteaseCookieMap()) }
    }
}
