package top.aryun.yzliyin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import top.aryun.yzliyin.R
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.screen.openBilibiliLive

/**
 * 进直播间的统一入口：先问从哪进——1. 本软件内观看  2. 在 B 站 APP 打开。
 *
 * 首页的直播卡片和 UP 主页的「正在直播」共用这一套，避免两处各写一遍。
 * [roomId] 传 null 表示不显示，这样调用方只要把状态直接丢进来，不用自己判空。
 *
 * Material3 的 `confirmButton` 恒定排在右侧，这里两个选项才是主要动作，
 * 因此把 `confirmButton` 置空、取消当 `dismissButton`，让两个选项从上往下排。
 */
@Composable
fun LiveRoomEntryDialog(roomId: Long?, nav: NavController, onDismiss: () -> Unit) {
    if (roomId == null) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.live_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(
                    onClick = {
                        onDismiss()
                        nav.navigate(Routes.liveRoom(roomId))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.live_in_app))
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        openBilibiliLive(context, roomId)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.live_in_bili_app))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.live_cancel))
            }
        },
    )
}
