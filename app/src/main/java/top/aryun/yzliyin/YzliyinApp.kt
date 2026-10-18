package top.aryun.yzliyin

import android.app.Activity
import android.app.Application
import android.os.Bundle
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.net.NetworkStatus
import top.aryun.yzliyin.player.AudioCache
import top.aryun.yzliyin.player.PlayerController

class YzliyinApp : Application() {
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
