package top.aryun.yzliyin.listentogether.player

import android.content.Context
import android.widget.Toast
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.listentogether.ListenTogetherSessionManager
import top.aryun.yzliyin.listentogether.mapping.toListenTogetherTrackOrNull
import top.aryun.yzliyin.listentogether.playback.authoritativeStreamUrlsForCurrentTrack
import top.aryun.yzliyin.listentogether.playback.currentTrack
import top.aryun.yzliyin.listentogether.playback.shouldHoldListenTogetherPlaybackForSafetyPause as shouldHoldSafetyPausePolicy
import top.aryun.yzliyin.listentogether.session.normalized
import top.aryun.yzliyin.listentogether.session.resolveListenTogetherControlBlockReason
import top.aryun.yzliyin.listentogether.session.resolveListenTogetherSessionRole
import top.aryun.yzliyin.player.PlaybackCommand
import top.aryun.yzliyin.player.PlaybackCommandSource
import top.aryun.yzliyin.player.PlaybackResolver
import top.aryun.yzliyin.player.PlayerController
import top.aryun.yzliyin.util.NPLogger

/** 房主共享的候选直链解析结果。 */
data class ShareableListenTogetherStreamResolution(
    val streamUrls: List<String>,
    val isPreviewOnly: Boolean = false
)

/**
 * 本机是否已有可用的直链可以分享 / 直接播放（对齐上游判定）。
 * 音之理音的取链不会产生试听片段，[currentPlaybackCandidateIsPreview] 恒为 false。
 */
fun hasUsableListenTogetherLocalDirectStream(
    currentSongMatchesTarget: Boolean,
    currentSongHasDirectStream: Boolean,
    currentMediaHasDirectStream: Boolean,
    currentPlaybackCandidateIsPreview: Boolean
): Boolean {
    if (!currentSongMatchesTarget || currentPlaybackCandidateIsPreview) return false
    return currentSongHasDirectStream || currentMediaHasDirectStream
}

/**
 * 一起听与本地播放器的适配层。
 *
 * 对齐上游 NeriPlayer 的 PlayerManager 一起听 API：状态以 StateFlow 暴露给会话管理器，
 * 远端同步入口带命令来源标记，避免把同步动作当成本机用户操作再次上抛。
 */
object ListenTogetherPlayer {

    private const val TAG = "LT-Player"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前正在进行的远端整表加载（含取链），后续同步操作需要排在其后。 */
    private var remotePlaylistLoadJob: Job? = null

    @Volatile
    private var manager: ListenTogetherSessionManager? = null

    private var initialized = false

    // ---------- 播放状态（由 PlayerController.state 派生） ----------

    private val _currentSongFlow = MutableStateFlow<Song?>(null)
    val currentSongFlow: StateFlow<Song?> = _currentSongFlow.asStateFlow()

    private val _currentQueueFlow = MutableStateFlow<List<Song>>(emptyList())
    val currentQueueFlow: StateFlow<List<Song>> = _currentQueueFlow.asStateFlow()

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow.asStateFlow()

    private val _playWhenReadyFlow = MutableStateFlow(false)
    val playWhenReadyFlow: StateFlow<Boolean> = _playWhenReadyFlow.asStateFlow()

    private val _playbackPositionFlow = MutableStateFlow(0L)
    val playbackPositionFlow: StateFlow<Long> = _playbackPositionFlow.asStateFlow()

    private val _playerPlaybackStateFlow = MutableStateFlow(Player.STATE_IDLE)
    val playerPlaybackStateFlow: StateFlow<Int> = _playerPlaybackStateFlow.asStateFlow()

    private val _repeatModeFlow = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatModeFlow: StateFlow<Int> = _repeatModeFlow.asStateFlow()

    private val _shuffleModeFlow = MutableStateFlow(false)
    val shuffleModeFlow: StateFlow<Boolean> = _shuffleModeFlow.asStateFlow()

    /** 当前曲目已解析的媒体直链。 */
    val currentMediaUrlFlow: StateFlow<String?> = PlayerController.currentMediaUrlFlow

    /** 本机用户播放命令流。 */
    val playbackCommandFlow: SharedFlow<PlaybackCommand> = PlayerController.playbackCommandFlow

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 一起听的用户可见提示（无操作权限被拦截等）。 */
    val noticeFlow: SharedFlow<String> = _notices.asSharedFlow()

    /** 当前软同步倍速（1.0 表示未微调）。 */
    val listenTogetherSyncPlaybackRate: Float
        get() = PlayerController.currentPlaybackRate()

    /** 应用启动时接线：订阅播放状态并安装播放命令门控。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        scope.launch {
            PlayerController.state.collect { state ->
                _currentSongFlow.value = state.current
                _currentQueueFlow.value = state.queue
                _isPlayingFlow.value = state.playing
                _playWhenReadyFlow.value = state.playWhenReady
                _playbackPositionFlow.value = state.positionMs
                _playerPlaybackStateFlow.value = state.playbackState
                _repeatModeFlow.value = state.repeatMode
                _shuffleModeFlow.value = state.shuffle
            }
        }
        PlayerController.playerCommandGate = ::gatePlayerCommand
    }

    fun attachManager(sessionManager: ListenTogetherSessionManager) {
        manager = sessionManager
    }

    // ---------- 播放命令门控 ----------

    /**
     * 播放命令门控：房间内听众在房主关闭成员控制（或房主离线）时不能操作本机播放。
     * 返回 false 会拒绝该命令（通知栏、应用内控制都会经过这里）。
     */
    private fun gatePlayerCommand(playerCommand: Int): Boolean {
        val commandType = when (playerCommand) {
            Player.COMMAND_PLAY_PAUSE -> "PLAY"
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "NEXT"
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> "PREVIOUS"
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> "SEEK"

            Player.COMMAND_CHANGE_MEDIA_ITEMS -> "PLAY_PLAYLIST"
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE -> "PLAYBACK_MODE"
            else -> return true
        }
        val session = manager?.sessionState?.value ?: return true
        if (session.roomId.isNullOrBlank()) return true
        val room = manager?.roomState?.value
        val role = resolveListenTogetherSessionRole(
            sessionUserId = session.userUuid,
            fallbackRole = session.role,
            state = room
        )
        val blockReason = resolveListenTogetherControlBlockReason(
            sessionRole = role,
            roomState = room,
            commandType = commandType
        )
        if (blockReason != null) {
            NPLogger.d(TAG, "gatePlayerCommand(): blocked type=$commandType, reason=$blockReason")
            notifyUser(blockReason)
            return false
        }
        if (commandType == "PLAY" || commandType == "PLAY_PLAYLIST") {
            requestListenTogetherSafetyPauseResume()
        }
        return true
    }

    private fun notifyUser(message: String) {
        if (!AppGraph.initialized) return
        runCatching {
            Toast.makeText(AppGraph.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
        _notices.tryEmit(message)
    }

    // ---------- 远端同步入口 ----------

    /** 整体替换队列并从 [index] 开始播放（远端同步，不上抛用户命令）。 */
    fun playPlaylist(
        queue: List<Song>,
        index: Int,
        @Suppress("UNUSED_PARAMETER") commandSource: PlaybackCommandSource = PlaybackCommandSource.REMOTE_SYNC
    ) {
        queue.getOrNull(index)?.let(::syncAuthoritativeStreamOverride)
        remotePlaylistLoadJob = scope.launch {
            PlayerController.applyRemotePlaylist(queue, index, positionMs = 0L, playWhenReady = true)
        }
    }

    /** 原地更新队列（重排/追加），尽量不打断当前曲目。 */
    fun applyRemoteQueueUpdate(queue: List<Song>, index: Int) {
        scope.launch {
            awaitRemotePlaylistLoad()
            PlayerController.applyRemoteQueueUpdate(queue, index)
        }
    }

    fun seekTo(
        positionMs: Long,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        scope.launch {
            awaitRemotePlaylistLoad()
            PlayerController.seekTo(positionMs, commandSource)
        }
    }

    fun play(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) {
        scope.launch {
            awaitRemotePlaylistLoad()
            PlayerController.play(commandSource)
        }
    }

    fun pauseImpl(
        forcePersist: Boolean = true,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
        @Suppress("UNUSED_PARAMETER") allowFadeOut: Boolean = false,
        @Suppress("UNUSED_PARAMETER") debugReason: String = ""
    ) {
        scope.launch {
            awaitRemotePlaylistLoad()
            PlayerController.pause(forcePersist = forcePersist, commandSource = commandSource)
        }
    }

    /** 远端整表加载是挂起操作（含取链）；后续 seek/pause/play 必须排在它后面，避免作用在空播放器上。 */
    private suspend fun awaitRemotePlaylistLoad() {
        remotePlaylistLoadJob?.join()
    }

    fun applyListenTogetherPlaybackMode(repeatMode: Int?, shuffleEnabled: Boolean?) {
        PlayerController.applyPlaybackMode(repeatMode, shuffleEnabled)
    }

    fun setListenTogetherSyncPlaybackRate(rate: Float) {
        PlayerController.setPlaybackRate(rate.coerceIn(0.95f, 1.05f))
    }

    fun resetListenTogetherSyncPlaybackRate() {
        PlayerController.resetPlaybackRate()
    }

    // ---------- 权威直链（房主共享直链给听众） ----------

    /** 房间当前曲目与本机 [song] 相同且开启共享时，设置候选直链覆盖。 */
    private fun syncAuthoritativeStreamOverride(song: Song) {
        val urls = authoritativeStreamUrlsFor(song)
        if (urls.isNotEmpty()) PlayerController.setStreamOverride(song.key, urls)
    }

    private fun authoritativeStreamUrlsFor(song: Song): List<String> {
        val room = manager?.roomState?.value ?: return emptyList()
        if (!room.settings.normalized().shareAudioLinks) return emptyList()
        val track = room.currentTrack() ?: return emptyList()
        val target = song.toListenTogetherTrackOrNull() ?: return emptyList()
        if (track.stableKey != target.stableKey) return emptyList()
        return room.authoritativeStreamUrlsForCurrentTrack()
    }

    /** 本机当前曲目的直链（可用于分享给听众）。 */
    fun currentListenTogetherShareableStreamUrls(): List<String> {
        val url = PlayerController.currentMediaUrlFlow.value ?: return emptyList()
        return if (isDirectStreamUrl(url)) listOf(url) else emptyList()
    }

    /** 房主解析可分享直链：优先复用当前已解析链接，再走本机取链。 */
    suspend fun resolveShareableListenTogetherStreamUrls(
        song: Song
    ): ShareableListenTogetherStreamResolution {
        val currentSong = PlayerController.state.value.current
        val currentUrl = PlayerController.currentMediaUrlFlow.value
        val urls = buildList {
            if (currentSong?.key == song.key && isDirectStreamUrl(currentUrl)) add(currentUrl!!)
            val resolved = PlaybackResolver.resolveOwn(song)
            if (resolved.ok && isDirectStreamUrl(resolved.url)) add(resolved.url)
        }.distinct()
        return ShareableListenTogetherStreamResolution(streamUrls = urls, isPreviewOnly = false)
    }

    /** 音之理音的取链不会产生试听片段，恒为 false。 */
    fun isCurrentPlaybackPreviewOnly(): Boolean = false

    // ---------- 权威流判定（对齐上游语义，按目标播放器能力简化） ----------

    fun isPendingMediaLoadActive(): Boolean = PlayerController.isPendingMediaLoadActive()

    fun isTransportActive(): Boolean = PlayerController.isTransportActive()

    fun isListenTogetherLocalResolutionPendingFor(song: Song): Boolean {
        if (!PlayerController.isPendingMediaLoadActive()) return false
        return PlayerController.state.value.current?.key == song.key
    }

    /** 听众是否必须等待房主下发的权威直链（本机还没有可用直链时）。 */
    fun shouldWaitForListenTogetherAuthoritativeStream(song: Song): Boolean {
        if (!isListenTogetherAuthoritativeStreamTarget(song)) return false
        if (isListenTogetherAuthoritativeStreamConfirmedUnavailable(song)) return false
        return !isDirectStreamUrl(currentListenTogetherTargetStreamUrl())
    }

    /**
     * 本机播放是否必须依赖房主共享的权威直链（本机还没有可用直链时为 true）。
     * 用于 LINK_UNAVAILABLE 后决定是否回退到本机取链。
     */
    fun currentPlaybackRequiresListenTogetherAuthoritativeStream(): Boolean {
        return PlayerController.currentMediaUrlFlow.value.isNullOrBlank()
    }

    /** 房主的共享直链已确认不可用（本机候选已用尽）。 */
    fun isListenTogetherAuthoritativeStreamConfirmedUnavailable(song: Song): Boolean {
        if (!isListenTogetherAuthoritativeStreamTarget(song)) return false
        val room = manager?.roomState?.value ?: return false
        val stableKey = song.toListenTogetherTrackOrNull()?.stableKey ?: return false
        return manager?.isControllerAudioLinkUnavailable(
            roomId = room.roomId,
            stableKey = stableKey
        ) == true
    }

    private fun isListenTogetherAuthoritativeStreamTarget(song: Song): Boolean {
        if (!isListenTogetherAudioLinkFallbackEnabled()) return false
        val targetStableKey = manager?.roomState?.value?.currentTrack()?.stableKey ?: return false
        val songStableKey = song.toListenTogetherTrackOrNull()?.stableKey ?: return false
        return songStableKey == targetStableKey
    }

    private fun isListenTogetherAudioLinkFallbackEnabled(): Boolean {
        if (!isListenTogetherActive()) return false
        if (isCurrentUserController()) return false
        val room = manager?.roomState?.value ?: return false
        return room.settings.normalized().shareAudioLinks &&
            room.roomStatus == "active"
    }

    private fun currentListenTogetherTargetStreamUrl(): String? {
        val room = manager?.roomState?.value ?: return null
        return room.authoritativeStreamUrlsForCurrentTrack().firstOrNull()
    }

    private fun isListenTogetherActive(): Boolean =
        !manager?.sessionState?.value?.roomId.isNullOrBlank()

    private fun isCurrentUserController(): Boolean {
        val session = manager?.sessionState?.value ?: return false
        return resolveListenTogetherSessionRole(
            sessionUserId = session.userUuid,
            fallbackRole = session.role,
            state = manager?.roomState?.value
        ) == "controller"
    }

    private fun isDirectStreamUrl(url: String?): Boolean {
        val normalized = url?.trim().orEmpty()
        return normalized.startsWith("https://", ignoreCase = true) ||
            normalized.startsWith("http://", ignoreCase = true)
    }

    // ---------- 听众安全暂停（输出设备丢失等） ----------

    @Volatile
    private var safetyPausePendingResume = false

    @Volatile
    private var safetyResumeInFlight = false

    fun markListenTogetherSafetyPausePendingResume() {
        safetyPausePendingResume = true
        safetyResumeInFlight = false
    }

    fun shouldHoldListenTogetherPlaybackForSafetyPause(causeType: String?): Boolean =
        shouldHoldSafetyPausePolicy(
            safetyPausePendingResume = safetyPausePendingResume,
            causeType = causeType
        )

    /** 听众安全暂停后请求恢复同步；返回是否已发起。 */
    fun requestListenTogetherSafetyPauseResume(): Boolean {
        if (!safetyPausePendingResume) return false
        if (!isListenTogetherActive() || isCurrentUserController()) {
            clearListenTogetherSafetyPause()
            return false
        }
        if (safetyResumeInFlight) return true
        safetyResumeInFlight = true
        manager?.resumeListenerAfterSafetyPause()
        return true
    }

    fun completeListenTogetherSafetyPauseResume() {
        safetyPausePendingResume = false
        safetyResumeInFlight = false
    }

    fun retryListenTogetherSafetyPauseResume() {
        safetyResumeInFlight = false
    }

    fun clearListenTogetherSafetyPause() {
        safetyPausePendingResume = false
        safetyResumeInFlight = false
    }
}
