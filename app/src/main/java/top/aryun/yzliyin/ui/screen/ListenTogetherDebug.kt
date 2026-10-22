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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.listentogether.invite.buildListenTogetherInviteUri
import top.aryun.yzliyin.listentogether.invite.parseListenTogetherInvite
import top.aryun.yzliyin.listentogether.invite.resolveListenTogetherBaseUrl
import top.aryun.yzliyin.listentogether.invite.resolveListenTogetherInviteJoinBaseUrl
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherConnectionState
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherMember
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomSettings
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomStatuses
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherSessionState
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherTrack
import top.aryun.yzliyin.listentogether.validation.ListenTogetherValidationError
import top.aryun.yzliyin.listentogether.validation.validateListenTogetherNickname
import top.aryun.yzliyin.listentogether.validation.validateListenTogetherRoomCreation
import top.aryun.yzliyin.listentogether.validation.validateListenTogetherUserUuid
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.ui.components.Chip
import top.aryun.yzliyin.ui.components.SectionHeader
import top.aryun.yzliyin.ui.components.StateTip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一起听开发者调试面板。
 *
 * 对齐 NeriPlayer 的 ListenTogetherDebugPanel：头部状态、快捷动作、房间动作、
 * 邀请复制、已连接动作（含发送测试事件）、房间/本地设置、会话字段、当前曲目、
 * 成员列表、队列列表与 JSON 快照。
 *
 * 只读展示 + 直接调用 [AppGraph.listenTogether] 的会话管理器，不改变一起听主流程。
 * 文案走字符串资源（`ltd_*`，见 values[-ja|-en]/ltdbg_strings.xml），颜色沿用设置页的「暗色纯白 / 亮色纯黑」约定。
 * 诊断输出里的状态转储（`status=$status`、JSON 快照、字段名等）保持原样不翻译——那些是协议字段、不是界面文案。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListenTogetherDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = AppGraph.listenTogether
    val prefs = AppGraph.listenTogetherPrefs
    val api = AppGraph.listenTogetherApi

    val session by manager.sessionState.collectAsState()
    val room by manager.roomState.collectAsState()
    val playState by PlayerController.state.collectAsState()

    val savedServerInput by prefs.workerBaseUrlInput.collectAsState()
    val savedUserUuid by prefs.userUuid.collectAsState()
    val savedNickname by prefs.nickname.collectAsState()
    val savedAllowMemberControl by prefs.allowMemberControl.collectAsState()
    val savedAutoPauseOnMemberChange by prefs.autoPauseOnMemberChange.collectAsState()
    val savedShareAudioLinks by prefs.shareAudioLinks.collectAsState()

    var serverInput by rememberSaveable { mutableStateOf("") }
    var nicknameInput by rememberSaveable { mutableStateOf("") }
    var userUuidInput by rememberSaveable { mutableStateOf("") }
    var allowMemberControl by rememberSaveable { mutableStateOf(true) }
    var autoPauseOnMemberChange by rememberSaveable { mutableStateOf(true) }
    var shareAudioLinks by rememberSaveable { mutableStateOf(true) }

    var showSessionDetails by rememberSaveable { mutableStateOf(false) }
    var showTrackPayload by rememberSaveable { mutableStateOf(false) }
    var showMembers by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showSnapshot by rememberSaveable { mutableStateOf(false) }
    var runningAction by remember { mutableStateOf<String?>(null) }
    var tip by remember { mutableStateOf("") }

    val textColor = MaterialTheme.colorScheme.onSurface
    val subColor = MaterialTheme.colorScheme.onSurfaceVariant

    val inRoom = !session.roomId.isNullOrBlank()
    val controllerKey = room?.controllerUserUuid?.takeIf { it.isNotBlank() }
        ?: room?.controllerUserId?.takeIf { it.isNotBlank() }
    val sessionUuid = session.userUuid?.takeIf { it.isNotBlank() }
    val role = if (controllerKey != null && sessionUuid != null) {
        if (sessionUuid.equals(controllerKey, ignoreCase = true)) "controller" else "listener"
    } else {
        session.role
    }
    val isController = role == "controller"
    val effectiveBaseUrl = resolveListenTogetherBaseUrl(serverInput)
    val tokenPreview = session.token.maskedTokenPreview()
    val nicknameError = validateListenTogetherNickname(nicknameInput)
    val userUuidError = validateListenTogetherUserUuid(userUuidInput)
    val roomCreationError = validateListenTogetherRoomCreation(
        queue = playState.queue,
        currentIndex = playState.index,
        currentSong = playState.current,
    )
    val inviteUri = remember(session.roomId, session.nickname, session.joinSecret, effectiveBaseUrl) {
        val roomId = session.roomId
        val joinSecret = session.joinSecret
        if (roomId.isNullOrBlank() || joinSecret.isNullOrBlank()) {
            null
        } else {
            runCatching {
                buildListenTogetherInviteUri(
                    roomId = roomId,
                    inviterNickname = session.nickname,
                    baseUrl = effectiveBaseUrl,
                    joinSecret = joinSecret,
                )
            }.getOrNull()
        }
    }
    val roomSettings = if (inRoom) {
        room?.settings ?: ListenTogetherRoomSettings(
            allowMemberControl = allowMemberControl,
            autoPauseOnMemberChange = autoPauseOnMemberChange,
            shareAudioLinks = shareAudioLinks,
        )
    } else {
        ListenTogetherRoomSettings(
            allowMemberControl = allowMemberControl,
            autoPauseOnMemberChange = autoPauseOnMemberChange,
            shareAudioLinks = shareAudioLinks,
        )
    }

    fun toast(message: String) {
        tip = message
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun runAction(label: String, block: suspend () -> String?) {
        if (runningAction != null) return
        runningAction = label
        scope.launch {
            runCatching { block() }
                .onSuccess { message -> message?.let(::toast) }
                .onFailure { error -> toast(error.message ?: error.javaClass.simpleName) }
            runningAction = null
        }
    }

    fun persistLocalSettings() {
        prefs.setWorkerBaseUrl(effectiveBaseUrl)
        prefs.setWorkerBaseUrlInput(serverInput)
        prefs.setUserUuid(userUuidInput)
        prefs.setNickname(nicknameInput)
        prefs.setAllowMemberControl(allowMemberControl)
        prefs.setAutoPauseOnMemberChange(autoPauseOnMemberChange)
        prefs.setShareAudioLinks(shareAudioLinks)
    }

    fun pushRoomSettings(updated: ListenTogetherRoomSettings) {
        runAction(context.getString(R.string.ltd_updating_room_settings)) {
            val result = manager.updateRoomSettings(updated)
            if (result.ok) {
                context.getString(R.string.ltd_room_settings_updated)
            } else {
                context.getString(
                    R.string.ltd_room_settings_update_fail,
                    result.error ?: context.getString(R.string.ltd_websocket_not_connected),
                )
            }
        }
    }

    fun currentSnapshot(): String = buildListenTogetherDebugSnapshot(
        session = session,
        room = room,
        role = role,
        tokenPreview = tokenPreview,
        effectiveBaseUrl = effectiveBaseUrl,
        serverInput = serverInput,
        identityUuid = userUuidInput,
        nickname = nicknameInput,
        settings = roomSettings,
        queueSize = playState.queue.size,
        currentIndex = playState.index,
        currentSongName = playState.current?.name,
        isPlaying = playState.playing,
        positionMs = playState.positionMs,
    )

    LaunchedEffect(savedServerInput) {
        if (serverInput != savedServerInput) serverInput = savedServerInput
    }
    LaunchedEffect(savedNickname, inRoom) {
        if (!inRoom && nicknameInput.isBlank()) {
            nicknameInput = savedNickname.ifBlank { prefs.getOrCreateNickname() }
        }
    }
    LaunchedEffect(savedUserUuid, inRoom) {
        if (!inRoom && userUuidInput.isBlank()) {
            userUuidInput = savedUserUuid.ifBlank { prefs.getOrCreateUserUuid() }
        }
    }
    LaunchedEffect(savedAllowMemberControl, savedAutoPauseOnMemberChange, savedShareAudioLinks, inRoom) {
        if (!inRoom) {
            allowMemberControl = savedAllowMemberControl
            autoPauseOnMemberChange = savedAutoPauseOnMemberChange
            shareAudioLinks = savedShareAudioLinks
        }
    }
    LaunchedEffect(room?.settings, isController, inRoom) {
        val settings = room?.settings
        if (inRoom && isController && settings != null) {
            allowMemberControl = settings.allowMemberControl
            autoPauseOnMemberChange = settings.autoPauseOnMemberChange
            shareAudioLinks = settings.shareAudioLinks
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 本页不在 Scaffold 内，需自行避让状态栏与导航栏
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopAppBar(
            title = {
                Text(stringResource(R.string.ltd_title), fontWeight = FontWeight.Bold, color = textColor)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.ltd_back), tint = textColor)
                }
            },
            actions = {
                runningAction?.let { label ->
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = subColor,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            },
        )

        if (tip.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text(tip, style = MaterialTheme.typography.bodySmall, color = textColor)
            }
        }

        // ===== 头部状态 =====
        SectionHeader(stringResource(R.string.ltd_section_status))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip(connectionStateText(session.connectionState))
            Chip(roleText(role))
            Chip(roomStatusText(room?.roomStatus))
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.roomId?.let { stringResource(R.string.ltd_room_hash, it) }
                    ?: stringResource(R.string.ltd_not_in_room),
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.ltd_room_version, room?.version ?: -1),
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
            )
        }

        // ===== 快捷动作 =====
        SectionHeader(stringResource(R.string.ltd_section_quick_actions))
        DebugCard {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        runAction(context.getString(R.string.ltd_action_testing_server)) {
                            val result = api.testServerAvailability(effectiveBaseUrl)
                            if (result.ok) {
                                context.getString(R.string.ltd_server_reachable, effectiveBaseUrl)
                            } else {
                                context.getString(R.string.ltd_server_unreachable, result.message)
                            }
                        }
                    },
                    enabled = runningAction == null,
                ) {
                    Icon(Icons.Outlined.NetworkCheck, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ltd_btn_test_server))
                }
                OutlinedButton(
                    onClick = {
                        toast(
                            if (manager.sendPing()) {
                                context.getString(R.string.ltd_ping_sent)
                            } else {
                                context.getString(R.string.ltd_ping_send_fail)
                            }
                        )
                    },
                    enabled = session.connectionState == ListenTogetherConnectionState.CONNECTED,
                ) {
                    Icon(Icons.Outlined.SettingsEthernet, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("ping")
                }
                OutlinedButton(
                    onClick = {
                        manager.connectWebSocket()
                        toast(context.getString(R.string.ltd_ws_requested))
                    },
                    enabled = !session.wsUrl.isNullOrBlank(),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ltd_btn_connect_ws))
                }
                OutlinedButton(
                    onClick = {
                        manager.disconnectWebSocket()
                        toast(context.getString(R.string.ltd_ws_disconnected))
                    },
                    enabled = session.connectionState != ListenTogetherConnectionState.DISCONNECTED,
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ltd_btn_disconnect_ws))
                }
                OutlinedButton(
                    onClick = {
                        val wsUrl = session.wsUrl
                        if (wsUrl.isNullOrBlank()) {
                            toast(context.getString(R.string.ltd_ws_url_empty))
                        } else {
                            debugCopy(context, context.getString(R.string.ltd_ws_url), wsUrl)
                            toast(context.getString(R.string.ltd_ws_url_copied))
                        }
                    },
                    enabled = !session.wsUrl.isNullOrBlank(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ltd_btn_copy_ws_url))
                }
            }
        }

        // ===== 房间动作 =====
        SectionHeader(
            stringResource(if (inRoom) R.string.ltd_section_connected_actions else R.string.ltd_section_room_actions),
        )
        DebugCard {
            if (!inRoom) {
                DebugFieldRow(
                    label = stringResource(R.string.ltd_create_room_queue_note),
                    value = stringResource(
                        R.string.ltd_create_room_queue_value,
                        playState.queue.size.toString(),
                        playState.index.toString(),
                    ),
                    textColor = subColor,
                )
                roomCreationError?.let {
                    DebugErrorText(it)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            runAction(context.getString(R.string.ltd_action_creating_room)) {
                                persistLocalSettings()
                                manager.createRoom(
                                    baseUrl = effectiveBaseUrl,
                                    userUuid = userUuidInput,
                                    nickname = nicknameInput,
                                    queue = playState.queue,
                                    currentIndex = playState.index,
                                    positionMs = playState.positionMs,
                                    isPlaying = playState.playing,
                                    roomSettings = roomSettings,
                                    currentSong = playState.current,
                                )
                                manager.connectWebSocket()
                                context.getString(
                                    R.string.ltd_room_created,
                                    manager.sessionState.value.roomId ?: "-",
                                )
                            }
                        },
                        enabled = runningAction == null &&
                            playState.queue.isNotEmpty() &&
                            userUuidError == null &&
                            nicknameError == null &&
                            roomCreationError == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ltd_btn_create_and_connect))
                    }
                    Button(
                        onClick = {
                            runAction(context.getString(R.string.ltd_action_parsing_invite)) {
                                val invite = parseListenTogetherInvite(debugReadClipboard(context))
                                    ?: return@runAction context.getString(R.string.ltd_no_valid_invite_in_clipboard)
                                val joinUuid = userUuidInput
                                    .takeIf { validateListenTogetherUserUuid(it) == null }
                                    ?: prefs.getOrCreateUserUuid()
                                val joinNickname = nicknameInput
                                    .takeIf { validateListenTogetherNickname(it) == null }
                                    ?: prefs.getOrCreateNickname()
                                manager.joinRoom(
                                    baseUrl = resolveListenTogetherInviteJoinBaseUrl(
                                        invite = invite,
                                        savedBaseUrlInput = serverInput,
                                        savedBaseUrl = effectiveBaseUrl,
                                    ),
                                    roomId = invite.roomId,
                                    userUuid = joinUuid,
                                    nickname = joinNickname,
                                    joinSecret = invite.joinSecret,
                                )
                                manager.connectWebSocket()
                                context.getString(R.string.ltd_room_joined, invite.roomId)
                            }
                        },
                        enabled = runningAction == null &&
                            (userUuidInput.isBlank() || userUuidError == null) &&
                            (nicknameInput.isBlank() || nicknameError == null),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Link, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ltd_btn_paste_invite_join))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            runAction(context.getString(R.string.ltd_action_refreshing_room)) {
                                prefs.setNickname(nicknameInput)
                                val roomId = session.roomId
                                if (roomId.isNullOrBlank()) {
                                    context.getString(R.string.ltd_no_room_now)
                                } else {
                                    val response = manager.refreshRoomState(effectiveBaseUrl, roomId)
                                    context.getString(
                                        R.string.ltd_room_state_refreshed,
                                        response.ok,
                                        response.state?.version ?: "-",
                                    )
                                }
                            }
                        },
                        enabled = runningAction == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ltd_btn_refresh_room))
                    }
                    Button(
                        onClick = {
                            manager.leaveRoom()
                            toast(context.getString(R.string.ltd_left_room))
                        },
                        enabled = session.connectionState != ListenTogetherConnectionState.CONNECTING,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.StopCircle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ltd_btn_leave_room))
                    }
                }

                HorizontalDivider()
                Text(stringResource(R.string.ltd_send_test_event), style = MaterialTheme.typography.bodyMedium, color = textColor)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val event = manager.buildHeartbeatEvent(
                                state = if (playState.playing) "playing" else "paused",
                                positionMs = playState.positionMs,
                                includeQueue = true,
                            )
                            val sent = manager.sendControlEventOverWebSocket(event)
                            toast(
                                if (sent) {
                                    context.getString(R.string.ltd_heartbeat_ws_sent)
                                } else {
                                    context.getString(R.string.ltd_ws_not_connected_send_fail)
                                }
                            )
                        },
                        enabled = session.connectionState == ListenTogetherConnectionState.CONNECTED,
                    ) {
                        Text(stringResource(R.string.ltd_btn_heartbeat_ws))
                    }
                    OutlinedButton(
                        onClick = {
                            runAction(context.getString(R.string.ltd_action_sending_http_heartbeat)) {
                                val event = manager.buildHeartbeatEvent(
                                    state = if (playState.playing) "playing" else "paused",
                                    positionMs = playState.positionMs,
                                    includeQueue = false,
                                )
                                val response = manager.sendControlEvent(event)
                                if (response.ok) {
                                    context.getString(R.string.ltd_heartbeat_http_sent)
                                } else {
                                    context.getString(
                                        R.string.ltd_heartbeat_http_fail,
                                        response.error ?: "-",
                                    )
                                }
                            }
                        },
                        enabled = runningAction == null && !session.token.isNullOrBlank(),
                    ) {
                        Text(stringResource(R.string.ltd_btn_heartbeat_http))
                    }
                    val stableKey = room?.track?.stableKey
                    OutlinedButton(
                        onClick = {
                            if (stableKey.isNullOrBlank()) {
                                toast(context.getString(R.string.ltd_track_missing_stable_key))
                            } else {
                                val event = manager.buildRequestLinkEvent(
                                    stableKey = stableKey,
                                    currentIndex = room?.currentIndex,
                                    track = room?.track,
                                    forceRefresh = true,
                                )
                                val sent = manager.sendControlEventOverWebSocket(event)
                                toast(
                                    if (sent) {
                                        context.getString(R.string.ltd_play_link_requested)
                                    } else {
                                        context.getString(R.string.ltd_ws_not_connected_send_fail)
                                    }
                                )
                            }
                        },
                        enabled = !stableKey.isNullOrBlank(),
                    ) {
                        Text(stringResource(R.string.ltd_btn_request_play_link_ws))
                    }
                }
            }
        }

        // ===== 邀请复制 =====
        SectionHeader(stringResource(R.string.ltd_section_invite_share))
        DebugCard {
            if (!inRoom) {
                StateTip(stringResource(R.string.ltd_not_in_room_no_invite))
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val uri = inviteUri
                            if (uri == null) {
                                toast(context.getString(R.string.ltd_invite_link_unavailable_toast))
                            } else {
                                debugCopy(context, context.getString(R.string.ltd_invite_link), uri)
                                toast(context.getString(R.string.ltd_invite_link_copied))
                            }
                        },
                        enabled = inviteUri != null,
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ltd_btn_copy_invite_link))
                    }
                    OutlinedButton(
                        onClick = {
                            debugCopy(context, context.getString(R.string.ltd_invite_secret), session.joinSecret.orEmpty())
                            toast(context.getString(R.string.ltd_invite_secret_copied))
                        },
                        enabled = !session.joinSecret.isNullOrBlank(),
                    ) {
                        Text(stringResource(R.string.ltd_btn_copy_invite_secret))
                    }
                    OutlinedButton(
                        onClick = {
                            debugCopy(context, context.getString(R.string.ltd_room_id), session.roomId.orEmpty())
                            toast(context.getString(R.string.ltd_room_id_copied))
                        },
                        enabled = !session.roomId.isNullOrBlank(),
                    ) {
                        Text(stringResource(R.string.ltd_btn_copy_room_id))
                    }
                    OutlinedButton(
                        onClick = {
                            val roomId = session.roomId.orEmpty()
                            val shareText = buildString {
                                append(
                                    context.getString(
                                        R.string.ltd_share_text,
                                        session.nickname ?: context.getString(R.string.ltd_friend),
                                        roomId,
                                    )
                                )
                                inviteUri?.let {
                                    append("\n")
                                    append(it)
                                }
                            }
                            debugCopy(context, context.getString(R.string.ltd_invite_label), shareText)
                            toast(context.getString(R.string.ltd_share_text_copied))
                        },
                        enabled = inviteUri != null,
                    ) {
                        Text(stringResource(R.string.ltd_btn_copy_share_text))
                    }
                    OutlinedButton(
                        onClick = {
                            val invite = parseListenTogetherInvite(debugReadClipboard(context))
                            if (invite == null) {
                                toast(context.getString(R.string.ltd_no_valid_invite_in_clipboard))
                            } else {
                                toast(
                                    buildString {
                                        append(context.getString(R.string.ltd_invite_parse_room, invite.roomId))
                                        append(context.getString(R.string.ltd_invite_parse_inviter, invite.inviterNickname ?: "-"))
                                        append(
                                            context.getString(
                                                R.string.ltd_invite_parse_server,
                                                invite.baseUrl ?: context.getString(R.string.ltd_invite_parse_default),
                                            )
                                        )
                                        if (invite.hasInvalidBaseUrl) append(context.getString(R.string.ltd_invite_parse_bad_base_url))
                                    }
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.ltd_btn_parse_invite))
                    }
                }
                Text(
                    text = inviteUri ?: stringResource(R.string.ltd_invite_link_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = subColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ===== 房间设置 =====
        SectionHeader(stringResource(R.string.ltd_section_room_settings))
        DebugCard {
            Text(
                text = when {
                    !inRoom -> stringResource(R.string.ltd_settings_local_default_note)
                    isController -> stringResource(R.string.ltd_settings_host_note)
                    else -> stringResource(R.string.ltd_settings_host_only_note)
                },
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
            )
            val settingsEnabled = runningAction == null && (!inRoom || isController)
            DebugSwitchRow(
                title = stringResource(R.string.ltd_allow_member_control_title),
                desc = stringResource(R.string.ltd_allow_member_control_desc),
                checked = allowMemberControl,
                enabled = settingsEnabled,
                textColor = textColor,
                subColor = subColor,
            ) { value ->
                allowMemberControl = value
                prefs.setAllowMemberControl(value)
                if (inRoom && isController) {
                    pushRoomSettings(
                        roomSettings.copy(allowMemberControl = value)
                    )
                }
            }
            DebugSwitchRow(
                title = stringResource(R.string.ltd_auto_pause_title),
                desc = stringResource(R.string.ltd_auto_pause_desc),
                checked = autoPauseOnMemberChange,
                enabled = settingsEnabled,
                textColor = textColor,
                subColor = subColor,
            ) { value ->
                autoPauseOnMemberChange = value
                prefs.setAutoPauseOnMemberChange(value)
                if (inRoom && isController) {
                    pushRoomSettings(
                        roomSettings.copy(autoPauseOnMemberChange = value)
                    )
                }
            }
            DebugSwitchRow(
                title = stringResource(R.string.ltd_share_audio_links_title),
                desc = stringResource(R.string.ltd_share_audio_links_desc),
                checked = shareAudioLinks,
                enabled = settingsEnabled,
                textColor = textColor,
                subColor = subColor,
            ) { value ->
                shareAudioLinks = value
                prefs.setShareAudioLinks(value)
                if (inRoom && isController) {
                    pushRoomSettings(
                        roomSettings.copy(shareAudioLinks = value)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        persistLocalSettings()
                        toast(context.getString(R.string.ltd_settings_saved_local))
                    },
                    enabled = runningAction == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.ltd_btn_save_local_default))
                }
                if (inRoom && isController) {
                    OutlinedButton(
                        onClick = {
                            pushRoomSettings(
                                ListenTogetherRoomSettings(
                                    allowMemberControl = allowMemberControl,
                                    autoPauseOnMemberChange = autoPauseOnMemberChange,
                                    shareAudioLinks = shareAudioLinks,
                                )
                            )
                        },
                        enabled = runningAction == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.ltd_btn_sync_to_room))
                    }
                }
            }
            Text(
                text = stringResource(
                    R.string.ltd_room_settings_summary,
                    roomSettings.allowMemberControl,
                    roomSettings.autoPauseOnMemberChange,
                    roomSettings.shareAudioLinks,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
            )
        }

        // ===== 本地身份与服务器 =====
        SectionHeader(stringResource(R.string.ltd_section_identity_server))
        DebugCard {
            OutlinedTextField(
                value = serverInput,
                onValueChange = { serverInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ltd_server_url_label)) },
                singleLine = true,
            )
            Text(
                text = stringResource(R.string.ltd_effective_base_url, effectiveBaseUrl),
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
            )
            OutlinedTextField(
                value = nicknameInput,
                onValueChange = { nicknameInput = it.trim().take(24) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ltd_nickname)) },
                singleLine = true,
                isError = nicknameError != null,
            )
            nicknameError?.let { DebugErrorText(it) }
            OutlinedTextField(
                value = userUuidInput,
                onValueChange = { userUuidInput = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ltd_identity_uuid)) },
                singleLine = true,
                isError = userUuidError != null,
            )
            userUuidError?.let { DebugErrorText(it) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        persistLocalSettings()
                        toast(context.getString(R.string.ltd_identity_server_saved))
                    },
                    enabled = runningAction == null &&
                        (nicknameInput.isBlank() || nicknameError == null) &&
                        (userUuidInput.isBlank() || userUuidError == null),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.ltd_btn_save_locally))
                }
                OutlinedButton(
                    onClick = {
                        if (inRoom) {
                            toast(context.getString(R.string.ltd_leave_room_to_reset_identity))
                        } else {
                            userUuidInput = prefs.resetUserUuid()
                            toast(context.getString(R.string.ltd_identity_uuid_reset))
                        }
                    },
                    enabled = runningAction == null && !inRoom,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.ltd_btn_reset_identity_uuid))
                }
            }
            val defaultLabel = stringResource(R.string.ltd_default)
            Text(
                text = stringResource(
                    R.string.ltd_saved_summary,
                    savedServerInput.ifBlank { defaultLabel },
                    savedNickname.ifBlank { "-" },
                    savedUserUuid.ifBlank { "-" },
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = subColor,
            )
        }

        // ===== 会话状态 =====
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        DebugSectionHeader(
            title = stringResource(R.string.ltd_section_session_state),
            expanded = showSessionDetails,
            onToggleExpanded = { showSessionDetails = !showSessionDetails },
            textColor = textColor,
        )
        DebugCard {
            DebugFieldGrid(
                listOf(
                    stringResource(R.string.ltd_field_connection_state) to connectionStateText(session.connectionState),
                    stringResource(R.string.ltd_field_role) to roleText(role),
                    stringResource(R.string.ltd_field_room_status) to roomStatusText(room?.roomStatus),
                    stringResource(R.string.ltd_room_id) to (session.roomId ?: "-"),
                    stringResource(R.string.ltd_field_version) to (room?.version?.toString() ?: "-"),
                    stringResource(R.string.ltd_field_member_count) to (room?.members?.size?.toString() ?: "0"),
                    stringResource(R.string.ltd_field_queue_length) to (room?.queue?.size?.toString() ?: "0"),
                    stringResource(R.string.ltd_field_playback_state) to playbackStateText(room, role, playState.playing),
                )
            )
            session.lastError?.takeIf { it.isNotBlank() }?.let {
                DebugBanner(stringResource(R.string.ltd_recent_error), it, textColor = textColor, highlighted = true)
            }
            session.roomNotice
                ?.takeIf { it.isNotBlank() && !it.startsWith("member_joined:") && !it.startsWith("member_left:") }
                ?.let { DebugBanner(stringResource(R.string.ltd_room_notice), it, textColor = textColor) }
            if (showSessionDetails) {
                DebugFieldGrid(
                    listOf(
                        stringResource(R.string.ltd_field_current_track) to (room?.track?.name ?: playState.current?.name ?: "-"),
                        stringResource(R.string.ltd_field_effective_server) to effectiveBaseUrl,
                        stringResource(R.string.ltd_ws_url) to (session.wsUrl ?: "-"),
                        "Token" to tokenPreview,
                        stringResource(R.string.ltd_identity_uuid) to (session.userUuid ?: "-"),
                        stringResource(R.string.ltd_field_session_nickname) to (session.nickname ?: "-"),
                        "schemaVersion" to (room?.schemaVersion?.toString() ?: "-"),
                        "expectedPositionMs" to (session.expectedPositionMs?.let(::debugDuration) ?: "-"),
                        "basePositionMs" to (room?.playback?.basePositionMs?.let(::debugDuration) ?: "-"),
                        "baseTimestampMs" to (room?.playback?.baseTimestampMs?.let(::debugClock) ?: "-"),
                        "playbackRate" to (room?.playback?.playbackRate?.toString() ?: "-"),
                        "repeatMode/shuffle" to (
                            room?.playback?.let { "${it.repeatMode ?: "-"} / ${it.shuffleEnabled ?: "-"}" } ?: "-"
                            ),
                        "controllerUserUuid" to (room?.controllerUserUuid ?: "-"),
                        "controllerUserId" to (room?.controllerUserId ?: "-"),
                        "controllerHeartbeatAt" to (room?.controllerHeartbeatAt?.let(::debugEpoch) ?: "-"),
                        "controllerOfflineSince" to (room?.controllerOfflineSince?.let(::debugEpoch) ?: "-"),
                        "updatedAt" to (room?.updatedAt?.let(::debugEpoch) ?: "-"),
                        "closedReason" to (room?.closedReason ?: "-"),
                        "currentIndex" to (room?.currentIndex?.toString() ?: "-"),
                        "joinSecret" to session.joinSecret.maskedTokenPreview(),
                        "memberSecret" to session.memberSecret.maskedTokenPreview(),
                    )
                )
            }
        }

        // ===== 当前曲目调试 =====
        DebugSectionHeader(
            title = stringResource(R.string.ltd_section_current_track_payload),
            expanded = showTrackPayload,
            onToggleExpanded = { showTrackPayload = !showTrackPayload },
            textColor = textColor,
        )
        DebugCard {
            val track = room?.track
            DebugFieldGrid(
                listOf(
                    stringResource(R.string.ltd_field_title) to (track?.name ?: playState.current?.name ?: "-"),
                    stringResource(R.string.ltd_field_artist) to (track?.artist ?: playState.current?.artist ?: "-"),
                    stringResource(R.string.ltd_field_album) to (track?.album ?: playState.current?.albumName ?: "-"),
                    stringResource(R.string.ltd_field_duration) to (track?.durationMs?.let(::debugDuration) ?: "-"),
                    "stableKey" to (track?.stableKey ?: "-"),
                    "channelId" to (track?.channelId ?: "-"),
                )
            )
            if (showTrackPayload) {
                DebugFieldGrid(
                    listOf(
                        "audioId" to (track?.audioId ?: "-"),
                        "subAudioId" to (track?.subAudioId ?: "-"),
                        "playlistContextId" to (track?.playlistContextId ?: "-"),
                        "mediaUri" to (track?.mediaUri ?: "-"),
                        "streamUrl" to (track?.streamUrl ?: "-"),
                        stringResource(R.string.ltd_field_stream_urls_count) to (track?.streamUrls?.size?.toString() ?: "0"),
                        "coverUrl" to (track?.coverUrl ?: playState.current?.cover ?: "-"),
                        stringResource(R.string.ltd_field_local_playback) to
                            "${if (playState.playing) stringResource(R.string.ltd_playing) else stringResource(R.string.ltd_paused)} @ ${debugDuration(playState.positionMs)}",
                    )
                )
            }
        }

        // ===== 成员列表 =====
        val members = room?.members?.sortedBy { it.joinedAt }.orEmpty()
        DebugSectionHeader(
            title = stringResource(R.string.ltd_section_members),
            expanded = showMembers,
            onToggleExpanded = { showMembers = !showMembers },
            textColor = textColor,
            suffix = members.size.toString(),
        )
        DebugCard {
            if (members.isEmpty()) {
                Text(stringResource(R.string.ltd_no_members), style = MaterialTheme.typography.bodySmall, color = subColor)
            } else {
                val roleCounts = members.groupingBy { it.role.ifBlank { "unknown" } }.eachCount()
                DebugFieldGrid(
                    buildList {
                        add(stringResource(R.string.ltd_field_total_members) to members.size.toString())
                        roleCounts.forEach { (memberRole, count) ->
                            add(
                                when (memberRole) {
                                    "controller" -> stringResource(R.string.ltd_role_host)
                                    "listener" -> stringResource(R.string.ltd_role_listener)
                                    else -> stringResource(R.string.ltd_role_unknown)
                                } to count.toString()
                            )
                        }
                    }
                )
                if (showMembers) {
                    members.forEach { member ->
                        DebugMemberCard(member = member, textColor = textColor, subColor = subColor)
                    }
                }
            }
        }

        // ===== 队列列表 =====
        val roomQueue = room?.queue.orEmpty()
        DebugSectionHeader(
            title = stringResource(R.string.ltd_section_queue),
            expanded = showQueue,
            onToggleExpanded = { showQueue = !showQueue },
            textColor = textColor,
            suffix = if (inRoom) {
                roomQueue.size.toString()
            } else {
                stringResource(R.string.ltd_queue_count_local, playState.queue.size.toString())
            },
        )
        DebugCard {
            if (!inRoom) {
                Text(
                    text = stringResource(
                        R.string.ltd_queue_local_note,
                        playState.queue.size.toString(),
                        playState.index.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                )
            } else if (roomQueue.isEmpty()) {
                Text(stringResource(R.string.ltd_room_queue_empty), style = MaterialTheme.typography.bodySmall, color = subColor)
            } else {
                Text(
                    text = "currentIndex=${room?.currentIndex ?: -1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                )
                val limit = if (showQueue) roomQueue.size else 5
                roomQueue.take(limit).forEachIndexed { index, item ->
                    DebugQueueRow(
                        index = index,
                        currentIndex = room?.currentIndex ?: -1,
                        track = item,
                        textColor = textColor,
                        subColor = subColor,
                    )
                }
                if (!showQueue && roomQueue.size > limit) {
                    Text(
                        text = stringResource(R.string.ltd_queue_more, (roomQueue.size - limit).toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                    )
                }
            }
        }

        // ===== JSON 快照 =====
        DebugSectionHeader(
            title = stringResource(R.string.ltd_section_json_snapshot),
            expanded = showSnapshot,
            onToggleExpanded = { showSnapshot = !showSnapshot },
            textColor = textColor,
        )
        DebugCard {
            val snapshotText = remember(showSnapshot, room, session, playState, roomSettings, userUuidInput, nicknameInput, serverInput) {
                if (showSnapshot) currentSnapshot() else ""
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        debugCopy(context, context.getString(R.string.ltd_debug_snapshot_label), currentSnapshot())
                        toast(context.getString(R.string.ltd_snapshot_copied))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ltd_btn_copy_snapshot))
                }
            }
            if (showSnapshot) {
                Text(
                    text = snapshotText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = subColor,
                )
            } else {
                Text(
                    text = stringResource(R.string.ltd_snapshot_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                )
            }
        }

        // 本页不显示迷你播放条与底栏，只需留少量尾部间距
        Spacer(Modifier.height(24.dp))
    }
}

// ---------- 区块 ----------

/** 可折叠区块标题（点击行或右侧按钮都可切换）。 */
@Composable
private fun DebugSectionHeader(
    title: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    textColor: Color,
    suffix: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = textColor)
        suffix?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) stringResource(R.string.ltd_collapse) else stringResource(R.string.ltd_expand),
            tint = textColor.copy(alpha = 0.7f),
        )
    }
}

/** 统一的内容卡片容器（12/14 圆角，跟随暗色/亮色正文色）。 */
@Composable
private fun DebugCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** 两列字段网格（奇数个时右侧留空）。 */
@Composable
private fun DebugFieldGrid(fields: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (label, value) ->
                    DebugFieldCard(label = label, value = value, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DebugFieldCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DebugFieldRow(label: String, value: String, textColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = textColor)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor,
        )
    }
}

@Composable
private fun DebugBanner(
    label: String,
    value: String,
    textColor: Color,
    highlighted: Boolean = false,
) {
    val background = if (highlighted) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = textColor)
        Text(value, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}

@Composable
private fun DebugErrorText(error: ListenTogetherValidationError) {
    Text(
        text = error.formatForApp(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun DebugSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean,
    textColor: Color,
    subColor: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = textColor)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = subColor)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DebugMemberCard(
    member: ListenTogetherMember,
    textColor: Color,
    subColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = member.nickname.ifBlank { member.userUuid.ifBlank { member.userId.orEmpty() } },
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
        Text(
            text = stringResource(
                R.string.ltd_member_debug_line,
                roleText(member.role),
                member.userId ?: "-",
                member.userUuid.ifBlank { "-" },
                debugEpoch(member.joinedAt),
            ),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = subColor,
        )
    }
}

@Composable
private fun DebugQueueRow(
    index: Int,
    currentIndex: Int,
    track: ListenTogetherTrack,
    textColor: Color,
    subColor: Color,
) {
    val isCurrent = index == currentIndex
    Text(
        text = buildString {
            append(if (isCurrent) "▶ " else "")
            append("$index. ")
            append(track.name)
            append(" - ")
            append(track.artist)
            append(" [")
            append(track.stableKey)
            append("]")
        },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (isCurrent) MaterialTheme.colorScheme.primary else textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (isCurrent) {
        Text(
            text = stringResource(R.string.ltd_current_track_line, track.channelId, debugDuration(track.durationMs)),
            style = MaterialTheme.typography.labelSmall,
            color = subColor,
        )
    }
}

// ---------- 工具 ----------

private val debugSnapshotJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

private fun buildListenTogetherDebugSnapshot(
    session: ListenTogetherSessionState,
    room: ListenTogetherRoomState?,
    role: String?,
    tokenPreview: String,
    effectiveBaseUrl: String,
    serverInput: String,
    identityUuid: String,
    nickname: String,
    settings: ListenTogetherRoomSettings,
    queueSize: Int,
    currentIndex: Int,
    currentSongName: String?,
    isPlaying: Boolean,
    positionMs: Long,
): String {
    val roomJson = room
        ?.let { debugSnapshotJson.parseToJsonElement(debugSnapshotJson.encodeToString(ListenTogetherRoomState.serializer(), it)) }
        ?: JsonNull
    val snapshot = buildJsonObject {
        put(
            "session",
            buildJsonObject {
                put("connectionState", session.connectionState.name)
                put("role", role ?: "-")
                put("roomId", session.roomId ?: "-")
                put("baseUrl", session.baseUrl ?: "-")
                put("userUuid", session.userUuid ?: "-")
                put("nickname", session.nickname ?: "-")
                put("token", tokenPreview)
                put("wsUrl", session.wsUrl ?: "-")
                put("expectedPositionMs", session.expectedPositionMs?.toString() ?: "-")
                put("lastError", session.lastError ?: "-")
                put("roomNotice", session.roomNotice ?: "-")
            },
        )
        put(
            "local",
            buildJsonObject {
                put("serverInput", serverInput)
                put("effectiveBaseUrl", effectiveBaseUrl)
                put("identityUuid", identityUuid)
                put("nickname", nickname)
                put("allowMemberControl", settings.allowMemberControl)
                put("autoPauseOnMemberChange", settings.autoPauseOnMemberChange)
                put("shareAudioLinks", settings.shareAudioLinks)
            },
        )
        put(
            "playback",
            buildJsonObject {
                put("playing", isPlaying)
                put("positionMs", positionMs)
                put("queueSize", queueSize)
                put("currentIndex", currentIndex)
                put("currentSong", currentSongName ?: "-")
            },
        )
        put("room", roomJson)
    }
    return debugSnapshotJson.encodeToString(JsonObject.serializer(), snapshot)
}

@Composable
private fun connectionStateText(state: ListenTogetherConnectionState): String = when (state) {
    ListenTogetherConnectionState.DISCONNECTED -> stringResource(R.string.ltd_conn_disconnected)
    ListenTogetherConnectionState.CONNECTING -> stringResource(R.string.ltd_conn_connecting)
    ListenTogetherConnectionState.CONNECTED -> stringResource(R.string.ltd_conn_connected)
}

@Composable
private fun roleText(role: String?): String = when (role) {
    "controller" -> stringResource(R.string.ltd_role_host)
    "listener" -> stringResource(R.string.ltd_role_listener)
    else -> stringResource(R.string.ltd_role_none)
}

@Composable
private fun roomStatusText(status: String?): String = when (status) {
    ListenTogetherRoomStatuses.CONTROLLER_OFFLINE -> stringResource(R.string.ltd_room_status_offline)
    ListenTogetherRoomStatuses.CLOSED -> stringResource(R.string.ltd_room_status_closed)
    ListenTogetherRoomStatuses.ACTIVE -> stringResource(R.string.ltd_room_status_active)
    else -> stringResource(R.string.ltd_room_status_none)
}

@Composable
private fun playbackStateText(
    room: ListenTogetherRoomState?,
    role: String?,
    isPlaying: Boolean,
): String {
    if (role == "controller" || room == null) {
        return if (isPlaying) {
            stringResource(R.string.ltd_playback_state_local_playing)
        } else {
            stringResource(R.string.ltd_playback_state_local_paused)
        }
    }
    return when (room.playback.state) {
        "playing" -> stringResource(R.string.ltd_playback_state_room_playing)
        else -> stringResource(R.string.ltd_playback_state_room_paused)
    }
}

/** Token / Secret 只展示头尾，避免整串出现在屏幕上。 */
private fun String?.maskedTokenPreview(): String {
    if (this.isNullOrBlank()) return "-"
    if (length <= 10) return this
    return "${take(6)}...${takeLast(4)}"
}

private fun debugEpoch(value: Long): String {
    if (value <= 0L) return "-"
    return runCatching {
        "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))} ($value)"
    }.getOrDefault(value.toString())
}

private fun debugClock(value: Long): String {
    if (value <= 0L) return "-"
    return runCatching {
        "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))} ($value)"
    }.getOrDefault(value.toString())
}

private fun debugDuration(value: Long): String {
    val totalSeconds = value / 1000
    return "%d:%02d (%d ms)".format(totalSeconds / 60, totalSeconds % 60, value)
}

private fun debugCopy(context: Context, label: String, text: String) {
    if (text.isBlank()) {
        Toast.makeText(context, context.getString(R.string.ltd_copy_empty, label), Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun debugReadClipboard(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}
