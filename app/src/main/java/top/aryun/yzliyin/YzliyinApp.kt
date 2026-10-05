package top.aryun.yzliyin

import android.app.Application
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.player.PlayerController

class YzliyinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        PlayerController.init(this)
    }
}
