package top.aryun.yzliyin.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbUpOffAlt
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.model.Comment
import top.aryun.yzliyin.core.net.BiliApi
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.formatCount

/**
 * B 站评论区（应用内）：浏览、发表、回复、点赞 / 点踩。
 *
 * 与网易云的 [CommentScreen] 平行，数据来自 `x/v2/reply`（type=1 稿件）。
 * 未登录可浏览；点赞 / 点踩 / 发表都需要 B 站登录，未登录时点击提示并跳扫码登录。
 * 评论要先拿 aid，进入时通过 [BiliApi.videoInfo] 解析一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliCommentScreen(nav: NavController, bvid: String, name: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf(name) }
    var page by remember { mutableIntStateOf(1) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 发表 / 回复
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<Comment?>(null) }

    suspend fun load(p: Int, append: Boolean) {
        loading = true
        runCatching { BiliApi.videoComments(bvid, page = p) }
            .onSuccess {
                comments = if (append) comments + it.items else it.items
                hasMore = it.hasMore
                page = p
                error = null
            }
            .onFailure { error = it.message ?: context.getString(R.string.bili_comments_load_fail) }
        loading = false
    }

    /** 未登录时提示并跳登录页，返回是否已登录。 */
    fun requireLogin(): Boolean {
        if (BiliApi.isLoggedIn) return true
        Toast.makeText(context, context.getString(R.string.bili_comments_login_required), Toast.LENGTH_SHORT).show()
        nav.navigate(Routes.login(Routes.LOGIN_BILIBILI_QR)) { launchSingleTop = true }
        return false
    }

    /** 点赞 / 点踩；未登录只提示不发请求。 */
    fun doAction(c: Comment, like: Boolean) {
        if (!requireLogin()) return
        val currentId = c.id
        val currentAction = c.likeAction
        scope.launch {
            runCatching { BiliApi.commentAction(bvid, currentId, like, currentAction) }
                .onSuccess {
                    // 本地乐观更新计数与动作态，免得整页重载
                    comments = comments.map { item ->
                        if (item.id != currentId) item else {
                            val newAction = when {
                                like && currentAction == 1 -> 0
                                like -> 1
                                !like && currentAction == 2 -> 0
                                else -> 2
                            }
                            val delta = when {
                                newAction == 1 && currentAction != 1 -> 1
                                newAction != 1 && currentAction == 1 -> -1
                                else -> 0
                            }
                            item.copy(
                                likeAction = newAction,
                                likeCount = (item.likeCount + delta).coerceAtLeast(0),
                            )
                        }
                    }
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: context.getString(R.string.bili_comments_action_fail),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }

    fun refresh() {
        scope.launch {
            runCatching { BiliApi.videoInfo(bvid) }
                .onSuccess {
                    if (title.isBlank()) title = it.title
                }
                .onFailure { }
            load(1, append = false)
        }
    }

    LaunchedEffect(bvid) {
        if (bvid.isBlank()) {
            error = context.getString(R.string.bili_comments_missing)
            loading = false
            return@LaunchedEffect
        }
        refresh()
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
                        append(stringResource(R.string.bili_comments_title))
                        if (comments.isNotEmpty()) append(" ${formatCount(comments.size.toLong())}")
                        if (title.isNotBlank()) append(" · $title")
                    },
                    maxLines = 1,
                )
            },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.bili_back),
                    )
                }
            },
        )

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { msg ->
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            msg,
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { refresh() }) { Text(stringResource(R.string.bili_retry)) }
                    }
                }
            }

            if (comments.isEmpty() && !loading && error == null) {
                item {
                    Text(
                        stringResource(R.string.bili_comments_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            itemsIndexed(comments, key = { _, c -> c.id }) { _, c ->
                BiliCommentItem(
                    comment = c,
                    onLike = { doAction(c, like = true) },
                    onDislike = { doAction(c, like = false) },
                    onReply = {
                        if (requireLogin()) replyTarget = c
                    },
                )
            }

            if (loading) {
                item {
                    Text(
                        stringResource(R.string.bili_comments_loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            if (comments.isNotEmpty() && hasMore) {
                item {
                    Button(
                        onClick = { scope.launch { load(page + 1, append = true) } },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.bili_comments_load_more)) }
                }
            }
        }

        // 回复目标提示条
        replyTarget?.let { target ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.bili_comments_reply_to, target.user),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                IconButton(onClick = { replyTarget = null }, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.bili_comments_cancel_reply),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // 输入栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (BiliApi.isLoggedIn) stringResource(R.string.bili_comments_hint)
                        else stringResource(R.string.bili_comments_hint_not_login),
                    )
                },
                enabled = !sending,
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (draft.isBlank()) return@Button
                    if (!requireLogin()) return@Button
                    sending = true
                    val target = replyTarget
                    scope.launch {
                        runCatching {
                            BiliApi.sendComment(
                                bvid = bvid,
                                message = draft.trim(),
                                root = target?.id?.toLongOrNull() ?: 0L,
                                parent = target?.id?.toLongOrNull() ?: 0L,
                            )
                        }.onSuccess {
                            draft = ""
                            replyTarget = null
                            Toast.makeText(
                                context,
                                context.getString(R.string.bili_comments_sent),
                                Toast.LENGTH_SHORT,
                            ).show()
                            load(1, append = false)
                        }.onFailure {
                            Toast.makeText(
                                context,
                                it.message ?: context.getString(R.string.bili_comments_send_fail),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        sending = false
                    }
                },
                enabled = draft.isNotBlank() && !sending,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }

        if (!BiliApi.isLoggedIn) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.bili_comments_not_login),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    nav.navigate(Routes.login(Routes.LOGIN_BILIBILI_QR)) { launchSingleTop = true }
                }) { Text(stringResource(R.string.bili_comments_go_login)) }
            }
        }
    }
}

@Composable
private fun BiliCommentItem(
    comment: Comment,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onReply: () -> Unit,
) {
    Row {
        AsyncImage(
            model = comment.avatar,
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
                    comment.user,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (comment.isHot) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.bili_comments_hot),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    comment.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(comment.content, style = MaterialTheme.typography.bodyMedium)
            if (comment.reply.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    comment.reply,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 点赞
                IconButton(onClick = onLike, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (comment.likeAction == 1) Icons.Filled.ThumbUp
                        else Icons.Filled.ThumbUpOffAlt,
                        contentDescription = stringResource(R.string.bili_like),
                        tint = if (comment.likeAction == 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    comment.likeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (comment.likeAction == 1) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                // 点踩
                IconButton(onClick = onDislike, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (comment.likeAction == 2) Icons.Filled.ThumbDown
                        else Icons.Filled.ThumbDownOffAlt,
                        contentDescription = stringResource(R.string.bili_dislike),
                        tint = if (comment.likeAction == 2) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReply) {
                    Text(stringResource(R.string.bili_reply), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
