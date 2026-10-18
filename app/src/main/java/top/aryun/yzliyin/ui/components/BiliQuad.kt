package top.aryun.yzliyin.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.core.net.BiliQuadStep
import top.aryun.yzliyin.ui.Routes

/**
 * 「一键三连」按钮：点赞 / 投币 / 收藏 / 转发到动态。
 *
 * 点一下先弹勾选窗（投币可选 1 枚或 2 枚），右下角「确定」只执行勾上的项目；
 * 未登录 B 站时点击只提示并跳登录页，不发任何请求；
 * 执行中弹进度框，逐步展示成功 / 失败（各步互相独立，一步失败不影响后面的）。
 *
 * [tint] 供页内播放页盖在黑色画面上时传白色。
 */
@Composable
fun BiliQuadButton(
    bvid: String,
    nav: NavController,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 勾选项跨弹窗保留，下次再点还是上次那套；转发默认不勾，免得默默发一条动态
    var like by remember { mutableStateOf(true) }
    var coinOn by remember { mutableStateOf(true) }
    var coinCount by remember { mutableIntStateOf(2) }
    var favorite by remember { mutableStateOf(true) }
    var repost by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf<List<BiliQuadStep>?>(null) }

    fun run() {
        running = true
        steps = null
        scope.launch {
            val result = runCatching {
                BiliApi.quad(
                    bvid,
                    like = like,
                    coins = if (coinOn) coinCount else 0,
                    favorite = favorite,
                    repost = repost,
                )
            }.getOrElse {
                listOf(BiliQuadStep("三连", ok = false, message = it.message ?: "失败"))
            }
            steps = result
            running = false
        }
    }

    IconButton(
        onClick = {
            if (running) return@IconButton
            if (!BiliApi.isLoggedIn) {
                Toast.makeText(context, "三连需要先登录 B 站账号", Toast.LENGTH_SHORT).show()
                nav.navigate(Routes.login(Routes.LOGIN_BILIBILI_QR)) { launchSingleTop = true }
                return@IconButton
            }
            if (bvid.isBlank()) {
                Toast.makeText(context, "缺少稿件信息，无法投币点赞", Toast.LENGTH_SHORT).show()
                return@IconButton
            }
            showPicker = true
        },
        modifier = modifier,
    ) {
        if (running) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tint)
        } else {
            Icon(Icons.Filled.AutoAwesome, contentDescription = "一键三连", tint = tint)
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("一键三连") },
            text = {
                Column {
                    QuadOptionRow("点赞", like) { like = it }
                    QuadOptionRow("投币", coinOn) { coinOn = it }
                    // 投币选 1 枚还是 2 枚：勾上投币后才出现
                    if (coinOn) {
                        Row(Modifier.fillMaxWidth().padding(start = 48.dp)) {
                            listOf(1, 2).forEach { count ->
                                Row(
                                    Modifier.clickable { coinCount = count },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = coinCount == count,
                                        onClick = { coinCount = count },
                                    )
                                    Text(
                                        "$count 枚",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                        }
                    }
                    QuadOptionRow("收藏", favorite) { favorite = it }
                    QuadOptionRow("转发到动态", repost) { repost = it }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!like && !coinOn && !favorite && !repost) {
                            Toast.makeText(context, "至少勾选一项", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        showPicker = false
                        run()
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        )
    }

    val result = steps
    if (running || result != null) {
        AlertDialog(
            onDismissRequest = { if (!running) steps = null },
            title = { Text("一键三连") },
            text = {
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("正在投币点赞…")
                    }
                } else {
                    Column {
                        result?.forEach { step ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (step.ok) Icons.Filled.CheckCircle
                                    else Icons.Filled.Error,
                                    contentDescription = null,
                                    tint = if (step.ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (step.ok) "${step.name}成功"
                                    else "${step.name}失败：${step.message}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                if (!running) {
                    TextButton(onClick = { steps = null }) { Text("关闭") }
                }
            },
        )
    }
}

/** 一行勾选项：整行可点。 */
@Composable
private fun QuadOptionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
