package top.aryun.yzliyin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.net.NetworkStatus
import top.aryun.yzliyin.player.AudioCache
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.util.AppLocale

class YzliyinApp : Application() {

    /**
     * 界面语言：Application 自己也要按所选语言重包。
     *
     * 通知栏、后台 PlaybackService、以及 `core/net` 深层那些拿不到 Compose
     * 上下文的错误文案，解析资源用的都是 applicationContext——只包 MainActivity
     * 的话这些地方会一直按**系统语言**显示，跟界面里选的对不上。
     *
     * 这一步跑在 `Application.onCreate` 前面，`AppGraph` 还没初始化，
     * 所以 [AppLocale.init] / 读偏好都直接走 SharedPreferences。
     */
    override fun attachBaseContext(newBase: Context) {
        AppLocale.init(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase, AppLocale.currentLanguage))
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        PlayerController.init(this)
        AudioCache.init(this)
        NetworkStatus.start(this)
        registerActivityLifecycleCallbacks(ListenTogetherLifecycleCallbacks())
    }
}

/**
 * 用 Activity 前后台切换近似进程前后台：一起听据此决定要不要
 * 探活/重连前台 WS、是否持有后台 WakeLock 保活。
 */
private class ListenTogetherLifecycleCallbacks : Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (startedActivities == 1 && AppGraph.initialized) {
            AppGraph.listenTogether.onApplicationForegrounded()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && AppGraph.initialized) {
            AppGraph.listenTogether.onApplicationBackgrounded()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
