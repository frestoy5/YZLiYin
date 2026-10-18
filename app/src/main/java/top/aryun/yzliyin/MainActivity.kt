package top.aryun.yzliyin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.listentogether.ListenTogetherInviteBus
import top.aryun.yzliyin.listentogether.invite.parseListenTogetherInvite
import top.aryun.yzliyin.ui.App
import top.aryun.yzliyin.ui.theme.YZLiYinTheme
import top.aryun.yzliyin.util.applyScreenOrientation

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 屏幕方向（设置 → 主题）：启动即套用竖屏 / 横屏 / 跟随传感器
        applyScreenOrientation(this, AppGraph.store.screenOrientation)
        requestNotificationPermission()
        handleListenTogetherInviteIntent(intent)
        setContent {
            YZLiYinTheme {
                App()
            }
        }
    }

    /** singleTask 模式下深链在已启动时会走这里，需要重新接一次意图。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleListenTogetherInviteIntent(intent)
    }

    /** 解析 `neriplayer://listen-together/join` 深链并投递给根布局弹确认框。 */
    private fun handleListenTogetherInviteIntent(intent: Intent?) {
        val invite = parseListenTogetherInvite(intent?.data) ?: return
        ListenTogetherInviteBus.publish(invite)
        // 消费掉深链数据，避免配置变更/重建时重复弹窗
        intent?.data = null
    }

    /** Android 13+ 播放通知需要运行时授权（Media3 前台服务通知）。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001,
                )
            }
        }
    }
}
