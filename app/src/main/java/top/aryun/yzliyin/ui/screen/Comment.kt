package top.aryun.yzliyin.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Comment
import top.aryun.yzliyin.core.model.Paged
import top.aryun.yzliyin.core.net.NeteaseApi
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.formatCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentScreen(nav: NavController, mixsongid: String, name: String = "") {
    val store = AppGraph.store
    val scope = rememberCoroutineScope()

    var loggedIn by remember { mutableStateOf(store.isNeteaseLoggedIn) }
    var page by remember { mutableIntStateOf(1) }
    var data by remember { mutableStateOf<Paged<Comment>?>(null) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var tip by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    suspend fun load(p: Int) {
        loading = true
        runCatching { NeteaseApi.comments(mixsongid, page = p) }
            .onSuccess {
                data = it
                page = p
                error = null
            }
            .onFailure { error = it.message ?: "加载评论失败" }
        loading = false
    }

    LaunchedEffect(mixsongid) {
        loggedIn = store.isNeteaseLoggedIn
        if (mixsongid.isBlank()) {
            error = "缺少歌曲 ID"
            loading = false
        } else {
            load(1)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        TopAppBar(
            title = {
                Text(
                    buildString {
                        append("评论")
                        data?.let { append(" ${formatCount(it.total.toLong())}") }
                        if (name.isNotBlank()) append(" · $name")
                    },
                    maxLines = 1,
                )
            },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            tip?.let { msg ->
                item {
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            error?.let { msg ->
                item { Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            items(data?.items.orEmpty(), key = { it.id }) { c ->
                CommentItem(c)
            }
            if (loading) {
                item {
                    Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            data?.let { d ->
                if (d.items.isEmpty() && !loading && error == null) {
                    item {
                        Text("还没有评论，来抢沙发", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (d.items.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (page > 1) {
                                Button(onClick = { scope.launch { load(page - 1) } }) { Text("上一页") }
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                "$page${if (d.hasMore) "+" else ""}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            if (d.hasMore) {
                                Spacer(Modifier.width(12.dp))
                                Button(onClick = { scope.launch { load(page + 1) } }) { Text("下一页") }
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (loggedIn) "说点什么…" else "未登录 · 暂无法发表评论")
                },
                enabled = loggedIn && !sending,
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (draft.isBlank()) return@Button
                    sending = true
                    scope.launch {
                        NeteaseApi.sendComment(mixsongid, draft.trim())
                            .onSuccess {
                                draft = ""
                                tip = it
                                load(1)
                            }
                            .onFailure { tip = it.message ?: "发送失败" }
                        sending = false
                    }
                },
                enabled = loggedIn && draft.isNotBlank() && !sending,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }

        if (!loggedIn) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "未登录网易云账号 · 可浏览评论",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    nav.navigate(Routes.login(Routes.LOGIN_NETEASE_WEB)) { launchSingleTop = true }
                }) {
                    Text("去登录")
                }
            }
        }
    }
}

@Composable
private fun CommentItem(c: Comment) {
    Row {
        AsyncImage(
            model = c.avatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.user,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (c.isHot) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "热评",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    c.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(c.content, style = MaterialTheme.typography.bodyMedium)
            if (c.reply.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "回复：${c.reply}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    c.likeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
