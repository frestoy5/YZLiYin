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
 * 文案统一硬编码中文（本项目不做多语言），颜色沿用设置页的「暗色纯白 / 亮色纯黑」约定。
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
        runAction("正在更新房间设置") {
            val result = manager.updateRoomSettings(updated)
            if (result.ok) "房间设置已更新" else "房间设置更新失败：${result.error ?: "websocket 未连接"}"
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
                Text("一起听调试", fontWeight = FontWeight.Bold, color = textColor)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = textColor)
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
        SectionHeader("头部状态")
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
                text = session.roomId?.let { "房间 #$it" } ?: "未加入房间",
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "版本 v${room?.version ?: -1}",
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
            )
        }

        // ===== 快捷动作 =====
        SectionHeader("快捷动作")
        DebugCard {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        runAction("正在测服务端可达性") {
                            val result = api.testServerAvailability(effectiveBaseUrl)
                            if (result.ok) {
                                "服务端可达：$effectiveBaseUrl"
                            } else {
                                "服务端不可达：${result.message}"
                            }
                        }
                    },
                    enabled = runningAction == null,
                ) {
                    Icon(Icons.Outlined.NetworkCheck, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("测服务端可达性")
                }
                OutlinedButton(
                    onClick = {
                        toast(if (manager.sendPing()) "ping 已发送" else "ping 发送失败（WS 未连接）")
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
                        toast("已请求连接 WebSocket")
                    },
                    enabled = !session.wsUrl.isNullOrBlank(),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("连接 WS")
                }
                OutlinedButton(
                    onClick = {
                        manager.disconnectWebSocket()
                        toast("已断开 WebSocket")
                    },
                    enabled = session.connectionState != ListenTogetherConnectionState.DISCONNECTED,
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("断开 WS")
                }
                OutlinedButton(
                    onClick = {
                        val wsUrl = session.wsUrl
                        if (wsUrl.isNullOrBlank()) {
                            toast("WS 地址为空")
                        } else {
                            debugCopy(context, "WS 地址", wsUrl)
                            toast("已复制 WS 地址")
                        }
                    },
                    enabled = !session.wsUrl.isNullOrBlank(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("复制 WS 地址")
                }
            }
        }

        // ===== 房间动作 =====
        SectionHeader(if (inRoom) "已连接动作" else "房间动作")
        DebugCard {
            if (!inRoom) {
                DebugFieldRow(
                    label = "创建房间需要当前队列快照",
                    value = "队列 ${playState.queue.size} 首 · 当前下标 ${playState.index}",
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
                            runAction("正在创建房间并连接") {
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
                                "房间已创建：${manager.sessionState.value.roomId ?: "-"}"
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
                        Text("创建并连接")
                    }
                    Button(
                        onClick = {
                            runAction("正在解析邀请并加入") {
                                val invite = parseListenTogetherInvite(debugReadClipboard(context))
                                    ?: return@runAction "剪贴板里没有有效的一起听邀请链接"
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
                                "已加入房间：${invite.roomId}"
                            }
                        },
                        enabled = runningAction == null &&
                            (userUuidInput.isBlank() || userUuidError == null) &&
                            (nicknameInput.isBlank() || nicknameError == null),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Link, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("粘贴邀请并加入")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            runAction("正在刷新房间状态") {
                                prefs.setNickname(nicknameInput)
                                val roomId = session.roomId
                                if (roomId.isNullOrBlank()) {
                                    "当前没有房间"
                                } else {
                                    val response = manager.refreshRoomState(effectiveBaseUrl, roomId)
                                    "房间状态已刷新：ok=${response.ok}，version=${response.state?.version ?: "-"}"
                                }
                            }
                        },
                        enabled = runningAction == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("刷新房间状态")
                    }
                    Button(
                        onClick = {
                            manager.leaveRoom()
                            toast("已离开房间")
                        },
                        enabled = session.connectionState != ListenTogetherConnectionState.CONNECTING,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.StopCircle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("离开房间")
                    }
                }

                HorizontalDivider()
                Text("发送测试事件", style = MaterialTheme.typography.bodyMedium, color = textColor)
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
                            toast(if (sent) "心跳已通过 WS 发送" else "WS 未连接，发送失败")
                        },
                        enabled = session.connectionState == ListenTogetherConnectionState.CONNECTED,
                    ) {
                        Text("心跳 → WS")
                    }
                    OutlinedButton(
                        onClick = {
                            runAction("正在通过 HTTP 发送心跳") {
                                val event = manager.buildHeartbeatEvent(
                                    state = if (playState.playing) "playing" else "paused",
                                    positionMs = playState.positionMs,
                                    includeQueue = false,
                                )
                                val response = manager.sendControlEvent(event)
                                if (response.ok) "心跳已通过 HTTP 发送" else "HTTP 心跳失败：${response.error ?: "-"}"
                            }
                        },
                        enabled = runningAction == null && !session.token.isNullOrBlank(),
                    ) {
                        Text("心跳 → HTTP")
                    }
                    val stableKey = room?.track?.stableKey
                    OutlinedButton(
                        onClick = {
                            if (stableKey.isNullOrBlank()) {
                                toast("当前曲目缺少 stableKey")
                            } else {
                                val event = manager.buildRequestLinkEvent(
                                    stableKey = stableKey,
                                    currentIndex = room?.currentIndex,
                                    track = room?.track,
                                    forceRefresh = true,
                                )
                                val sent = manager.sendControlEventOverWebSocket(event)
                                toast(if (sent) "已请求播放链接（forceRefresh）" else "WS 未连接，发送失败")
                            }
                        },
                        enabled = !stableKey.isNullOrBlank(),
                    ) {
                        Text("请求播放链接 → WS")
                    }
                }
            }
        }

        // ===== 邀请复制 =====
        SectionHeader("邀请与分享")
        DebugCard {
            if (!inRoom) {
                StateTip("未加入房间，没有可分享的邀请信息")
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
                                toast("邀请链接不可用（缺少房间号或密钥）")
                            } else {
                                debugCopy(context, "邀请链接", uri)
                                toast("已复制邀请链接")
                            }
                        },
                        enabled = inviteUri != null,
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("复制邀请链接")
                    }
                    OutlinedButton(
                        onClick = {
                            debugCopy(context, "邀请密钥", session.joinSecret.orEmpty())
                            toast("已复制邀请密钥")
                        },
                        enabled = !session.joinSecret.isNullOrBlank(),
                    ) {
                        Text("复制邀请密钥")
                    }
                    OutlinedButton(
                        onClick = {
                            debugCopy(context, "房间号", session.roomId.orEmpty())
                            toast("已复制房间号")
                        },
                        enabled = !session.roomId.isNullOrBlank(),
                    ) {
                        Text("复制房间号")
                    }
                    OutlinedButton(
                        onClick = {
                            val roomId = session.roomId.orEmpty()
                            val shareText = buildString {
                                append("${session.nickname ?: "听友"} 邀请你一起听（房间 $roomId）")
                                inviteUri?.let {
                                    append("\n")
                                    append(it)
                                }
                            }
                            debugCopy(context, "一起听邀请", shareText)
                            toast("已复制分享文案")
                        },
                        enabled = inviteUri != null,
                    ) {
                        Text("复制分享文案")
                    }
                    OutlinedButton(
                        onClick = {
                            val invite = parseListenTogetherInvite(debugReadClipboard(context))
                            if (invite == null) {
                                toast("剪贴板里没有有效的一起听邀请链接")
                            } else {
                                toast(
                                    buildString {
                                        append("解析成功：房间 ${invite.roomId}")
                                        append("，邀请人 ${invite.inviterNickname ?: "-"}")
                                        append("，服务端 ${invite.baseUrl ?: "默认"}")
                                        if (invite.hasInvalidBaseUrl) append("（baseUrl 非法，已忽略）")
                                    }
                                )
                            }
                        },
                    ) {
                        Text("粘贴解析邀请")
                    }
                }
                Text(
                    text = inviteUri ?: "邀请链接不可用",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = subColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ===== 房间设置 =====
        SectionHeader("房间设置")
        DebugCard {
            Text(
                text = if (inRoom) {
                    if (isController) "房主可修改，改动会立即同步到房间" else "仅房主可修改房间设置"
                } else {
                    "未加入房间：这里保存的是建房默认设置"
                },
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
            )
            val settingsEnabled = runningAction == null && (!inRoom || isController)
            DebugSwitchRow(
                title = "允许听众控制播放",
                desc = "关闭后听众只能跟随房主播放",
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
                title = "成员变动自动暂停",
                desc = "有成员加入或离开时自动暂停本地播放",
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
                title = "分享音频链接",
                desc = "分享可直连的音频地址，减少听众端二次取链",
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
                        toast("本地默认设置已保存")
                    },
                    enabled = runningAction == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存为本地默认")
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
                        Text("同步到房间")
                    }
                }
            }
            Text(
                text = "房间当前设置：允许听众控制=${roomSettings.allowMemberControl}，" +
                    "成员变动暂停=${roomSettings.autoPauseOnMemberChange}，" +
                    "分享音频链接=${roomSettings.shareAudioLinks}",
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
            )
        }

        // ===== 本地身份与服务器 =====
        SectionHeader("本地身份与服务器")
        DebugCard {
            OutlinedTextField(
                value = serverInput,
                onValueChange = { serverInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务端地址（留空用默认）") },
                singleLine = true,
            )
            Text(
                text = "实际生效：$effectiveBaseUrl",
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
            )
            OutlinedTextField(
                value = nicknameInput,
                onValueChange = { nicknameInput = it.trim().take(24) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("昵称") },
                singleLine = true,
                isError = nicknameError != null,
            )
            nicknameError?.let { DebugErrorText(it) }
            OutlinedTextField(
                value = userUuidInput,
                onValueChange = { userUuidInput = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("身份 UUID") },
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
                        toast("本地身份与服务器设置已保存")
                    },
                    enabled = runningAction == null &&
                        (nicknameInput.isBlank() || nicknameError == null) &&
                        (userUuidInput.isBlank() || userUuidError == null),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存到本地")
                }
                OutlinedButton(
                    onClick = {
                        if (inRoom) {
                            toast("需要先离开房间才能重置身份")
                        } else {
                            userUuidInput = prefs.resetUserUuid()
                            toast("已重置身份 UUID")
                        }
                    },
                    enabled = runningAction == null && !inRoom,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("重置身份 UUID")
                }
            }
            Text(
                text = "已保存：服务端=${savedServerInput.ifBlank { "默认" }}，" +
                    "昵称=${savedNickname.ifBlank { "-" }}，" +
                    "UUID=${savedUserUuid.ifBlank { "-" }}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = subColor,
            )
        }

        // ===== 会话状态 =====
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        DebugSectionHeader(
            title = "会话状态",
            expanded = showSessionDetails,
            onToggleExpanded = { showSessionDetails = !showSessionDetails },
            textColor = textColor,
        )
        DebugCard {
            DebugFieldGrid(
                listOf(
                    "连接状态" to connectionStateText(session.connectionState),
                    "角色" to roleText(role),
                    "房间状态" to roomStatusText(room?.roomStatus),
                    "房间号" to (session.roomId ?: "-"),
                    "版本" to (room?.version?.toString() ?: "-"),
                    "成员数" to (room?.members?.size?.toString() ?: "0"),
                    "队列长度" to (room?.queue?.size?.toString() ?: "0"),
                    "播放状态" to playbackStateText(room, role, playState.playing),
                )
            )
            session.lastError?.takeIf { it.isNotBlank() }?.let {
                DebugBanner("最近错误", it, textColor = textColor, highlighted = true)
            }
            session.roomNotice
                ?.takeIf { it.isNotBlank() && !it.startsWith("member_joined:") && !it.startsWith("member_left:") }
                ?.let { DebugBanner("房间通知", it, textColor = textColor) }
            if (showSessionDetails) {
                DebugFieldGrid(
                    listOf(
                        "当前曲目" to (room?.track?.name ?: playState.current?.name ?: "-"),
                        "生效服务端" to effectiveBaseUrl,
                        "WS 地址" to (session.wsUrl ?: "-"),
                        "Token" to tokenPreview,
                        "身份 UUID" to (session.userUuid ?: "-"),
                        "会话昵称" to (session.nickname ?: "-"),
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
            title = "当前曲目（房间 payload）",
            expanded = showTrackPayload,
            onToggleExpanded = { showTrackPayload = !showTrackPayload },
            textColor = textColor,
        )
        DebugCard {
            val track = room?.track
            DebugFieldGrid(
                listOf(
                    "曲名" to (track?.name ?: playState.current?.name ?: "-"),
                    "歌手" to (track?.artist ?: playState.current?.artist ?: "-"),
                    "专辑" to (track?.album ?: playState.current?.albumName ?: "-"),
                    "时长" to (track?.durationMs?.let(::debugDuration) ?: "-"),
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
                        "streamUrls 数量" to (track?.streamUrls?.size?.toString() ?: "0"),
                        "coverUrl" to (track?.coverUrl ?: playState.current?.cover ?: "-"),
                        "本地正在播放" to "${if (playState.playing) "播放中" else "暂停"} @ ${debugDuration(playState.positionMs)}",
                    )
                )
            }
        }

        // ===== 成员列表 =====
        val members = room?.members?.sortedBy { it.joinedAt }.orEmpty()
        DebugSectionHeader(
            title = "成员列表",
            expanded = showMembers,
            onToggleExpanded = { showMembers = !showMembers },
            textColor = textColor,
            suffix = members.size.toString(),
        )
        DebugCard {
            if (members.isEmpty()) {
                Text("暂无成员", style = MaterialTheme.typography.bodySmall, color = subColor)
            } else {
                val roleCounts = members.groupingBy { it.role.ifBlank { "unknown" } }.eachCount()
                DebugFieldGrid(
                    buildList {
                        add("成员总数" to members.size.toString())
                        roleCounts.forEach { (memberRole, count) ->
                            add(
                                when (memberRole) {
                                    "controller" -> "房主"
                                    "listener" -> "听众"
                                    else -> "未知角色"
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
            title = "队列",
            expanded = showQueue,
            onToggleExpanded = { showQueue = !showQueue },
            textColor = textColor,
            suffix = if (inRoom) roomQueue.size.toString() else "${playState.queue.size}（本地）",
        )
        DebugCard {
            if (!inRoom) {
                Text(
                    text = "未加入房间。本地队列 ${playState.queue.size} 首，当前下标 ${playState.index}，" +
                        "创建房间时会作为初始队列上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                )
            } else if (roomQueue.isEmpty()) {
                Text("房间队列为空", style = MaterialTheme.typography.bodySmall, color = subColor)
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
                        text = "还有 ${roomQueue.size - limit} 首，展开查看全部",
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                    )
                }
            }
        }

        // ===== JSON 快照 =====
        DebugSectionHeader(
            title = "JSON 快照",
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
                        debugCopy(context, "一起听调试快照", currentSnapshot())
                        toast("已复制 JSON 快照")
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("复制快照")
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
                    text = "展开查看 session / room / local / playback 的完整 JSON",
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
            contentDescription = if (expanded) "收起" else "展开",
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
            text = "角色=${roleText(member.role)} · userId=${member.userId ?: "-"} · " +
                "uuid=${member.userUuid.ifBlank { "-" }} · 加入于 ${debugEpoch(member.joinedAt)}",
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
            text = "当前曲目 · ${track.channelId} · ${debugDuration(track.durationMs)}",
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

private fun connectionStateText(state: ListenTogetherConnectionState): String = when (state) {
    ListenTogetherConnectionState.DISCONNECTED -> "未连接"
    ListenTogetherConnectionState.CONNECTING -> "连接中"
    ListenTogetherConnectionState.CONNECTED -> "已连接"
}

private fun roleText(role: String?): String = when (role) {
    "controller" -> "房主"
    "listener" -> "听众"
    else -> "无角色"
}

private fun roomStatusText(status: String?): String = when (status) {
    ListenTogetherRoomStatuses.CONTROLLER_OFFLINE -> "房主离线"
    ListenTogetherRoomStatuses.CLOSED -> "已关闭"
    ListenTogetherRoomStatuses.ACTIVE -> "正常"
    else -> "未加入"
}

private fun playbackStateText(
    room: ListenTogetherRoomState?,
    role: String?,
    isPlaying: Boolean,
): String {
    if (role == "controller" || room == null) {
        return if (isPlaying) "播放中（本地）" else "已暂停（本地）"
    }
    return when (room.playback.state) {
        "playing" -> "播放中（房间）"
        else -> "已暂停（房间）"
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
        Toast.makeText(context, "$label 为空", Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun debugReadClipboard(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    return clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}
