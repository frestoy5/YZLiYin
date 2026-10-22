package top.aryun.yzliyin.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.listentogether.ListenTogetherSessionManager
import top.aryun.yzliyin.listentogether.listenTogetherDisplayMessage
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherConnectionState
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomSettings
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomStatuses
import top.aryun.yzliyin.listentogether.invite.DEFAULT_LISTEN_TOGETHER_BASE_URL
import top.aryun.yzliyin.listentogether.invite.buildListenTogetherInviteUri
import top.aryun.yzliyin.listentogether.invite.parseListenTogetherInvite
import top.aryun.yzliyin.listentogether.invite.resolveListenTogetherBaseUrl
import top.aryun.yzliyin.listentogether.invite.resolveListenTogetherInviteJoinBaseUrl
import top.aryun.yzliyin.listentogether.validation.validateListenTogetherJoinSecret
import top.aryun.yzliyin.listentogether.validation.validateListenTogetherNickname
import top.aryun.yzliyin.listentogether.validation.validateListenTogetherRoomCreation
import top.aryun.yzliyin.listentogether.validation.validateListenTogetherRoomId
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.Routes
import top.aryun.yzliyin.ui.components.StateTip
import top.aryun.yzliyin.util.AppLocale

/**
 * 一起听：创建/加入房间、成员与队列查看、房间设置、服务端与身份配置。
 *
 * 交互结果用页面顶部提示条与 Toast 反馈；房间状态由会话管理器持续推送。
 */
@Composable
fun ListenTogetherScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = AppGraph.listenTogether
    val prefs = AppGraph.listenTogetherPrefs

    val session by manager.sessionState.collectAsStateWithLifecycle()
    val room by manager.roomState.collectAsStateWithLifecycle()
    val playState by PlayerController.state.collectAsStateWithLifecycle()
    val workerBaseUrl by prefs.workerBaseUrl.collectAsStateWithLifecycle()
    val workerBaseUrlInput by prefs.workerBaseUrlInput.collectAsStateWithLifecycle()
    val nickname by prefs.nickname.collectAsStateWithLifecycle()
    val allowMemberControl by prefs.allowMemberControl.collectAsStateWithLifecycle()
    val autoPauseOnMemberChange by prefs.autoPauseOnMemberChange.collectAsStateWithLifecycle()
    val shareAudioLinks by prefs.shareAudioLinks.collectAsStateWithLifecycle()

    var roomIdInput by rememberSaveable { mutableStateOf("") }
    var secretInput by rememberSaveable { mutableStateOf("") }
    var serverInput by rememberSaveable(workerBaseUrlInput) { mutableStateOf(workerBaseUrlInput) }
    var nicknameInput by rememberSaveable(nickname) { mutableStateOf(nickname) }
    var tip by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showResetIdentityDialog by remember { mutableStateOf(false) }

    val inRoom = !session.roomId.isNullOrBlank()
    val isController = session.role == "controller"
    // 走主题色而不是写死黑白，Material You 动态取色与自定义主题下也能对上底色
    val textColor = MaterialTheme.colorScheme.onSurface
    val subColor = MaterialTheme.colorScheme.onSurfaceVariant

    fun runBusy(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            tip = runCatching { block() }.getOrElse { it.message ?: context.getString(R.string.lt_op_failed) }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // 全站根页面统一避让状态栏；本页没有底栏/迷你播放条，只需处理导航栏与键盘
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.lt_back),
                    tint = textColor,
                )
            }
            Text(
                text = stringResource(R.string.lt_title),
                style = MaterialTheme.typography.titleLarge,
                color = textColor,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
            }
            TextButton(onClick = { nav.navigate(Routes.LISTEN_TOGETHER_DEBUG) }) {
                Text(stringResource(R.string.lt_debug), color = MaterialTheme.colorScheme.primary)
            }
        }

        if (tip.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text(
                    tip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        if (inRoom) {
            RoomSection(
                manager = manager,
                room = room,
                session = session,
                isController = isController,
                textColor = textColor,
                subColor = subColor,
                onCopyInvite = {
                    copyText(context, context.getString(R.string.lt_invite_info), buildInviteText(session))
                },
                onCopySecret = {
                    copyText(context, context.getString(R.string.lt_invite_secret), session.joinSecret.orEmpty())
                },
                onLeave = {
                    manager.leaveRoom()
                    tip = context.getString(R.string.lt_left_room)
                },
                onUpdateSettings = { settings ->
                    prefs.setAllowMemberControl(settings.allowMemberControl)
                    prefs.setAutoPauseOnMemberChange(settings.autoPauseOnMemberChange)
                    prefs.setShareAudioLinks(settings.shareAudioLinks)
                    val response = manager.updateRoomSettings(settings)
                    tip = if (response.ok) context.getString(R.string.lt_room_updated)
                    else (response.error ?: context.getString(R.string.lt_room_update_failed))
                },
            )
        } else {
            CreateOrJoinSection(
                playStateQueue = playState.queue,
                playStateIndex = playState.index,
                allowMemberControl = allowMemberControl,
                autoPauseOnMemberChange = autoPauseOnMemberChange,
                shareAudioLinks = shareAudioLinks,
                roomIdInput = roomIdInput,
                secretInput = secretInput,
                textColor = textColor,
                subColor = subColor,
                onRoomIdChange = { roomIdInput = it },
                onSecretChange = { secretInput = it },
                onToggleAllowMemberControl = {
                    prefs.setAllowMemberControl(it)
                },
                onToggleAutoPause = { prefs.setAutoPauseOnMemberChange(it) },
                onToggleShareAudioLinks = { prefs.setShareAudioLinks(it) },
                onCreate = {
                    runBusy {
                        createListenTogetherRoom(
                            context = context,
                            manager = manager,
                            prefs = prefs,
                            queue = playState.queue,
                            currentIndex = playState.index,
                            currentSong = playState.current,
                            positionMs = playState.positionMs,
                            isPlaying = playState.playing,
                            baseUrl = resolveListenTogetherBaseUrl(workerBaseUrlInput.ifBlank { workerBaseUrl }),
                        )
                    }
                },
                onJoin = {
                    runBusy {
                        joinListenTogetherRoom(
                            context = context,
                            manager = manager,
                            prefs = prefs,
                            roomId = roomIdInput,
                            joinSecret = secretInput,
                            baseUrl = resolveListenTogetherBaseUrl(workerBaseUrlInput.ifBlank { workerBaseUrl }),
                        )
                    }
                },
                onPasteInvite = {
                    val invite = parseListenTogetherInvite(readClipboardText(context))
                    if (invite == null) {
                        tip = context.getString(R.string.lt_clipboard_no_invite)
                    } else {
                        runBusy { joinListenTogetherFromInvite(manager, prefs, invite, context) }
                    }
                },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SectionTitle(stringResource(R.string.lt_server), textColor)
        Text(
            text = if (workerBaseUrl.isBlank()) stringResource(R.string.lt_server_default, DEFAULT_LISTEN_TOGETHER_BASE_URL)
            else stringResource(R.string.lt_server_custom, workerBaseUrl),
            style = MaterialTheme.typography.bodySmall,
            color = subColor,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = serverInput,
            onValueChange = { serverInput = it },
            label = { Text(stringResource(R.string.lt_server_url_label)) },
            placeholder = { Text(DEFAULT_LISTEN_TOGETHER_BASE_URL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    runBusy {
                        val target = serverInput.trim().ifBlank { DEFAULT_LISTEN_TOGETHER_BASE_URL }
                        val result = AppGraph.listenTogetherApi.testServerAvailability(target)
                        result.message
                    }
                },
            ) { Text(stringResource(R.string.lt_test_server)) }
            Button(
                onClick = {
                    prefs.setWorkerBaseUrlInput(serverInput.trim())
                    prefs.setWorkerBaseUrl(serverInput.trim())
                    tip = if (serverInput.isBlank()) context.getString(R.string.lt_server_reset)
                    else context.getString(R.string.lt_server_saved)
                },
            ) { Text(stringResource(R.string.lt_save)) }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

        SectionTitle(stringResource(R.string.lt_identity), textColor)
        OutlinedTextField(
            value = nicknameInput,
            onValueChange = { nicknameInput = it },
            label = { Text(stringResource(R.string.lt_nickname)) },
            singleLine = true,
            isError = nicknameInput.isNotBlank() && validateListenTogetherNickname(nicknameInput) != null,
            supportingText = {
                validateListenTogetherNickname(nicknameInput)?.let {
                    Text(it.formatForApp(), color = MaterialTheme.colorScheme.error)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.lt_identity_uuid,
                session.userUuid?.take(8)
                    ?: prefs.userUuid.value.take(8).ifBlank { stringResource(R.string.lt_not_generated) },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = subColor,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val error = validateListenTogetherNickname(nicknameInput)
                    if (error != null) {
                        tip = error.formatForApp()
                    } else {
                        prefs.setNickname(nicknameInput)
                        tip = context.getString(R.string.lt_nickname_saved)
                    }
                },
            ) { Text(stringResource(R.string.lt_save_nickname)) }
            OutlinedButton(
                onClick = {
                    if (inRoom) tip = context.getString(R.string.lt_leave_to_reset) else showResetIdentityDialog = true
                },
            ) { Text(stringResource(R.string.lt_reset_identity)) }
        }

        // 本页不显示迷你播放条与底栏，避让了导航栏后只需留少量尾部间距
        Spacer(Modifier.height(24.dp))
    }

    if (showResetIdentityDialog) {
        AlertDialog(
            onDismissRequest = { showResetIdentityDialog = false },
            title = { Text(stringResource(R.string.lt_reset_identity)) },
            text = { Text(stringResource(R.string.lt_reset_identity_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.resetUserUuid()
                        showResetIdentityDialog = false
                        tip = context.getString(R.string.lt_identity_reset_done)
                    },
                ) { Text(stringResource(R.string.lt_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetIdentityDialog = false }) { Text(stringResource(R.string.lt_cancel)) }
            },
        )
    }
}

// ---------- 建房 / 入房（供页面与邀请弹窗共用） ----------

internal suspend fun createListenTogetherRoom(
    context: Context,
    manager: ListenTogetherSessionManager,
    prefs: top.aryun.yzliyin.listentogether.data.ListenTogetherPrefs,
    queue: List<Song>,
    currentIndex: Int,
    currentSong: Song?,
    positionMs: Long,
    isPlaying: Boolean,
    baseUrl: String,
): String {
    validateListenTogetherRoomCreation(queue, currentIndex, currentSong)
        ?.let { return it.formatForApp() }
    val response = manager.createRoom(
        baseUrl = baseUrl,
        userUuid = prefs.getOrCreateUserUuid(),
        nickname = prefs.getOrCreateNickname(),
        queue = queue,
        currentIndex = currentIndex,
        positionMs = positionMs,
        isPlaying = isPlaying,
        roomSettings = ListenTogetherRoomSettings(
            allowMemberControl = prefs.allowMemberControl.value,
            autoPauseOnMemberChange = prefs.autoPauseOnMemberChange.value,
            shareAudioLinks = prefs.shareAudioLinks.value,
        ),
        currentSong = currentSong,
    )
    if (!response.ok) return response.error ?: context.getString(R.string.lt_create_room_failed)
    manager.connectWebSocket()
    return context.getString(R.string.lt_room_created, response.roomId)
}

internal suspend fun joinListenTogetherRoom(
    context: Context,
    manager: ListenTogetherSessionManager,
    prefs: top.aryun.yzliyin.listentogether.data.ListenTogetherPrefs,
    roomId: String,
    joinSecret: String,
    baseUrl: String,
    memberSecret: String? = null,
): String {
    val normalizedRoomId = roomId.trim()
    validateListenTogetherRoomId(normalizedRoomId)?.let { return it.formatForApp() }
    validateListenTogetherJoinSecret(joinSecret)?.let { return it.formatForApp() }
    val response = manager.joinRoom(
        baseUrl = baseUrl,
        roomId = normalizedRoomId,
        userUuid = prefs.getOrCreateUserUuid(),
        nickname = prefs.getOrCreateNickname(),
        memberSecret = memberSecret,
        joinSecret = joinSecret.trim(),
    )
    if (!response.ok) return response.error ?: context.getString(R.string.lt_join_room_failed)
    manager.connectWebSocket()
    return context.getString(R.string.lt_room_joined, response.roomId)
}

/** 通过邀请信息加入（深链或粘贴文本），服务端地址优先级：邀请内 > 本机已保存。 */
internal suspend fun joinListenTogetherFromInvite(
    manager: ListenTogetherSessionManager,
    prefs: top.aryun.yzliyin.listentogether.data.ListenTogetherPrefs,
    invite: top.aryun.yzliyin.listentogether.invite.ListenTogetherInvite,
    // 文案用的 Context；缺省按**当前**界面语言现包（App.kt 深链调用点不传）。
    // ⚠ 不能直接写 AppGraph.applicationContext：Application 只在进程启动时包过一次语言，
    //   用户中途切过语言后它还停在旧值，而这里的默认参数是**每次调用时**求值的，正好避开。
    context: Context = AppLocale.wrap(AppGraph.applicationContext, AppLocale.currentLanguage),
): String {
    if (invite.hasInvalidBaseUrl) return context.getString(R.string.lt_invite_url_invalid)
    val baseUrl = resolveListenTogetherInviteJoinBaseUrl(
        invite = invite,
        savedBaseUrlInput = prefs.workerBaseUrlInput.value,
        savedBaseUrl = prefs.workerBaseUrl.value,
    )
    return joinListenTogetherRoom(
        context = context,
        manager = manager,
        prefs = prefs,
        roomId = invite.roomId,
        joinSecret = invite.joinSecret.orEmpty(),
        baseUrl = baseUrl,
    )
}

// ---------- 房间内区块 ----------

@Composable
private fun RoomSection(
    manager: ListenTogetherSessionManager,
    room: ListenTogetherRoomState?,
    session: top.aryun.yzliyin.listentogether.protocol.ListenTogetherSessionState,
    isController: Boolean,
    textColor: Color,
    subColor: Color,
    onCopyInvite: () -> Unit,
    onCopySecret: () -> Unit,
    onLeave: () -> Unit,
    onUpdateSettings: (ListenTogetherRoomSettings) -> Unit,
) {
    SectionTitle(stringResource(R.string.lt_room), textColor)
    SectionCard {
        InfoRow(stringResource(R.string.lt_room_id), session.roomId.orEmpty(), textColor)
        InfoRow(
            stringResource(R.string.lt_role),
            if (isController) stringResource(R.string.lt_role_host) else stringResource(R.string.lt_role_listener),
            textColor,
        )
        InfoRow(
            stringResource(R.string.lt_connection_state),
            when (session.connectionState) {
                ListenTogetherConnectionState.CONNECTED -> stringResource(R.string.lt_connected)
                ListenTogetherConnectionState.CONNECTING -> stringResource(R.string.lt_connecting)
                ListenTogetherConnectionState.DISCONNECTED -> stringResource(R.string.lt_disconnected)
            },
            textColor,
        )
        room?.let { state ->
            InfoRow(stringResource(R.string.lt_room_status), roomStatusText(state), textColor)
            InfoRow(stringResource(R.string.lt_member_count), state.members.size.toString(), textColor)
            InfoRow(stringResource(R.string.lt_queue_count), state.queue.size.toString(), textColor)
            val track = state.queue.getOrNull(state.currentIndex.coerceIn(0, (state.queue.size - 1).coerceAtLeast(0)))
                ?: state.track
            track?.let {
                InfoRow(stringResource(R.string.lt_current_song), it.name, textColor)
                InfoRow(stringResource(R.string.lt_artist), it.artist, textColor)
            }
            InfoRow(
                stringResource(R.string.lt_playback_state),
                if (state.playback.state == "playing") stringResource(R.string.lt_playing)
                else stringResource(R.string.lt_paused),
                textColor,
            )
            state.closedReason?.takeIf { it.isNotBlank() }?.let {
                InfoRow(stringResource(R.string.lt_close_reason), it, textColor)
            }
        }
    }
    session.roomNotice?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            listenTogetherDisplayMessage(it) ?: it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    session.lastError?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            listenTogetherDisplayMessage(it) ?: it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onCopyInvite) { Text(stringResource(R.string.lt_copy_invite)) }
        if (session.joinSecret?.isNotBlank() == true) {
            OutlinedButton(onClick = onCopySecret) { Text(stringResource(R.string.lt_copy_secret)) }
        }
        OutlinedButton(onClick = onLeave) { Text(stringResource(R.string.lt_leave_room)) }
    }

    val settings = room?.settings
    if (isController && settings != null) {
        Spacer(Modifier.height(14.dp))
        SectionTitle(stringResource(R.string.lt_room_settings), textColor)
        SwitchRow(
            title = stringResource(R.string.lt_allow_member_control),
            desc = stringResource(R.string.lt_allow_member_control_desc),
            checked = settings.allowMemberControl,
            textColor = textColor,
            subColor = subColor,
            onCheckedChange = { onUpdateSettings(settings.copy(allowMemberControl = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.lt_auto_pause),
            desc = stringResource(R.string.lt_auto_pause_desc),
            checked = settings.autoPauseOnMemberChange,
            textColor = textColor,
            subColor = subColor,
            onCheckedChange = { onUpdateSettings(settings.copy(autoPauseOnMemberChange = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.lt_share_audio_links),
            desc = stringResource(R.string.lt_share_audio_links_desc),
            checked = settings.shareAudioLinks,
            textColor = textColor,
            subColor = subColor,
            onCheckedChange = { onUpdateSettings(settings.copy(shareAudioLinks = it)) },
        )
    }

    room?.members?.takeIf { it.isNotEmpty() }?.let { members ->
        Spacer(Modifier.height(14.dp))
        SectionTitle(stringResource(R.string.lt_members), textColor)
        SectionCard {
            members.forEach { member ->
                val isSelf = member.userUuid == session.userUuid
                val roleLabel = stringResource(
                    if (member.role == "controller") R.string.lt_role_host else R.string.lt_role_listener,
                )
                val meLabel = stringResource(R.string.lt_me)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = member.nickname.ifBlank { member.userUuid.take(8) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = buildString {
                            append(roleLabel)
                            if (isSelf) append(" · $meLabel")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                    )
                }
            }
        }
    }

    room?.queue?.takeIf { it.isNotEmpty() }?.let { queue ->
        Spacer(Modifier.height(14.dp))
        SectionTitle(stringResource(R.string.lt_queue), textColor)
        val currentIndex = room.currentIndex
        SectionCard {
            queue.take(30).forEachIndexed { index, track ->
                val isCurrent = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else subColor,
                        modifier = Modifier.width(28.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = track.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = subColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (queue.size > 30) {
                Text(
                    text = stringResource(R.string.lt_queue_more, queue.size - 30),
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                )
            }
        }
    }
}

/** 分区卡片：把裸文本信息行收进有底色和圆角的容器里，避免整页贴在背景上。 */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            content()
        }
    }
}

@Composable
private fun CreateOrJoinSection(
    playStateQueue: List<Song>,
    playStateIndex: Int,
    allowMemberControl: Boolean,
    autoPauseOnMemberChange: Boolean,
    shareAudioLinks: Boolean,
    roomIdInput: String,
    secretInput: String,
    textColor: Color,
    subColor: Color,
    onRoomIdChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onToggleAllowMemberControl: (Boolean) -> Unit,
    onToggleAutoPause: (Boolean) -> Unit,
    onToggleShareAudioLinks: (Boolean) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onPasteInvite: () -> Unit,
) {
    SectionTitle(stringResource(R.string.lt_create_room), textColor)
    Text(
        text = if (playStateQueue.isEmpty()) stringResource(R.string.lt_create_room_empty)
        else stringResource(R.string.lt_create_room_hint, playStateQueue.size, playStateIndex + 1),
        style = MaterialTheme.typography.bodySmall,
        color = subColor,
    )
    Spacer(Modifier.height(8.dp))
    SwitchRow(
        title = stringResource(R.string.lt_allow_member_control),
        desc = stringResource(R.string.lt_allow_member_control_desc_create),
        checked = allowMemberControl,
        textColor = textColor,
        subColor = subColor,
        onCheckedChange = onToggleAllowMemberControl,
    )
    SwitchRow(
        title = stringResource(R.string.lt_auto_pause),
        desc = stringResource(R.string.lt_auto_pause_desc),
        checked = autoPauseOnMemberChange,
        textColor = textColor,
        subColor = subColor,
        onCheckedChange = onToggleAutoPause,
    )
    SwitchRow(
        title = stringResource(R.string.lt_share_audio_links),
        desc = stringResource(R.string.lt_share_audio_links_desc),
        checked = shareAudioLinks,
        textColor = textColor,
        subColor = subColor,
        onCheckedChange = onToggleShareAudioLinks,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onCreate, enabled = playStateQueue.isNotEmpty()) {
            Text(stringResource(R.string.lt_create_room))
        }
        StateTip(stringResource(R.string.lt_host_can_copy_invite))
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.lt_join_room), textColor)
    OutlinedTextField(
        value = roomIdInput,
        onValueChange = { onRoomIdChange(it.uppercase()) },
        label = { Text(stringResource(R.string.lt_room_id_label)) },
        placeholder = { Text(stringResource(R.string.lt_room_id_hint)) },
        singleLine = true,
        isError = roomIdInput.isNotBlank() && validateListenTogetherRoomId(roomIdInput) != null,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = secretInput,
        onValueChange = onSecretChange,
        label = { Text(stringResource(R.string.lt_invite_secret)) },
        singleLine = true,
        isError = secretInput.isNotBlank() && validateListenTogetherJoinSecret(secretInput) != null,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onJoin) { Text(stringResource(R.string.lt_join_room)) }
        OutlinedButton(onClick = onPasteInvite) { Text(stringResource(R.string.lt_paste_invite)) }
    }
}

// ---------- 小组件 ----------

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun InfoRow(key: String, value: String, color: Color) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = color.copy(alpha = 0.62f),
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    textColor: Color,
    subColor: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = textColor)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = subColor)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------- 工具 ----------

@Composable
private fun roomStatusText(state: ListenTogetherRoomState): String = when (state.roomStatus) {
    ListenTogetherRoomStatuses.ACTIVE -> stringResource(R.string.lt_status_active)
    ListenTogetherRoomStatuses.CONTROLLER_OFFLINE -> stringResource(R.string.lt_status_host_offline)
    ListenTogetherRoomStatuses.CLOSED -> stringResource(R.string.lt_status_closed)
    else -> state.roomStatus
}

internal fun buildInviteText(
    session: top.aryun.yzliyin.listentogether.protocol.ListenTogetherSessionState,
): String {
    val roomId = session.roomId.orEmpty()
    val secret = session.joinSecret.orEmpty()
    return buildListenTogetherInviteUri(
        roomId = roomId,
        inviterNickname = session.nickname,
        baseUrl = session.baseUrl,
        joinSecret = secret,
    )
}

internal fun copyText(context: Context, label: String, text: String) {
    if (text.isBlank()) {
        Toast.makeText(context, context.getString(R.string.lt_copy_empty, label), Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, context.getString(R.string.lt_copy_done, label), Toast.LENGTH_SHORT).show()
}

internal fun readClipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}
