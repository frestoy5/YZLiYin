package top.aryun.yzliyin.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.ResolveResult
import top.aryun.yzliyin.core.model.Song

/** 当前播放状态，供 UI 观察。 */
data class PlayState(
    val queue: List<Song> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val current: Song? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val preparing: Boolean = false,
    val error: String? = null,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    /** 取链中的曲目下标（-1 表示空闲） */
    val resolving: Boolean = false,
    /** 播放意愿（与 [playing] 不同：缓冲中/准备中也可能为 true）。 */
    val playWhenReady: Boolean = false,
    /** media3 播放器状态（[Player.STATE_IDLE] 等），一起听卡顿检测用。 */
    val playbackState: Int = Player.STATE_IDLE,
    /** 当前曲目实际取链的客户端（跨源找音源后可能与曲目自身的源不同）。 */
    val currentSource: top.aryun.yzliyin.core.model.Source? = null,
    /** 当前播放链接是否来自本机缓存文件。 */
    val fromCache: Boolean = false,
)

/**
 * 播放控制器：单例，桥接 Compose 与 Media3 MediaController。
 * 数据源仅网易云：起始曲预先取链，其余曲目在切换/出错时惰性补链。
 *
 * 一起听集成（见 `listentogether` 包）：
 *  - 本地用户操作会以 [PlaybackCommand] 形式经 [playbackCommandFlow] 上抛（由播放状态变化推导）；
 *  - 远端同步通过 [applyRemotePlaylist] / [seekTo] / [play] / [pause] 等带
 *    [PlaybackCommandSource] 参数的入口执行，不会反向产生用户命令；
 *  - 听众端可用 [playerCommandGate] 拦截来自通知栏/系统/自应用的播放命令。
 */
object PlayerController {

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()

    /** 本地用户播放命令流（一起听用来把本机操作转成房间控制事件）。 */
    private val _playbackCommands = MutableSharedFlow<PlaybackCommand>(extraBufferCapacity = 32)
    val playbackCommandFlow: SharedFlow<PlaybackCommand> = _playbackCommands.asSharedFlow()

    private var controller: MediaController? = null
    private lateinit var appContext: Context
    private val pendingQueue = ArrayList<Song>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionJob: Job? = null
    private var resolvingIndex = -1

    /** 已成功写入播放 URL 的曲目（跨源唯一键 → 已解析直链）。 */
    private val resolvedUrls = HashMap<String, String>()

    /**
     * 每首已取链曲目**实际**的播放来源。跨源找音源后可能与曲目自身的
     * [top.aryun.yzliyin.core.model.Song.source] 不同，播放页据此在右下角标注当前客户端。
     */
    private val resolvedSources = HashMap<String, ResolvedSourceInfo>()

    /** 记录某首歌的播放来源。 */
    private fun rememberSource(songKey: String, result: ResolveResult) {
        resolvedSources[songKey] = ResolvedSourceInfo(result.source, result.fromCache)
    }

    /** 一起听权威直链覆盖：曲目键 → 候选直链（听众优先使用房主共享的候选）。 */
    private val streamOverrides = HashMap<String, List<String>>()
    private val streamOverrideCursor = HashMap<String, Int>()
    private val streamOverrideExhausted = HashSet<String>()

    private val _currentMediaUrl = MutableStateFlow<String?>(null)

    /** 当前曲目已解析的媒体直链（未取链时为空）。 */
    val currentMediaUrlFlow: StateFlow<String?> = _currentMediaUrl.asStateFlow()

    /**
     * 播放命令门控：返回 false 表示拒绝该播放器命令（一起听听众端无操作权限时拦截）。
     * 指令常量见 [Player.COMMAND_PLAY_PAUSE] 等。
     */
    @Volatile
    var playerCommandGate: ((Int) -> Boolean)? = null

    /**
     * 程序化控制计数：本机内部搬运（取链、恢复、远端同步）期间不产生用户命令、
     * 也不经过 [playerCommandGate]，避免把同步动作当成用户操作再次上抛。
     */
    @Volatile
    private var programmaticControlDepth = 0

    fun isApplyingProgrammaticControl(): Boolean = programmaticControlDepth > 0

    private inline fun <T> withProgrammaticControl(block: () -> T): T {
        programmaticControlDepth++
        try {
            return block()
        } finally {
            programmaticControlDepth--
        }
    }

    /** MediaSession 回调（PlaybackService）询问是否放行某条播放器命令。 */
    fun shouldAcceptPlayerCommand(playerCommand: Int): Boolean {
        if (programmaticControlDepth > 0) return true
        val gate = playerCommandGate ?: return true
        return gate(playerCommand)
    }

    // ---------- 命令上抛（由播放状态变化推导，覆盖 UI / 通知栏 / 系统媒体键） ----------

    private var lastQueueTransitionAtMs = 0L
    private var lastKnownMediaItemCount = 0
    private var lastKnownMediaItemIndex = -1

    private fun emitLocalCommand(command: PlaybackCommand) {
        _playbackCommands.tryEmit(command)
    }

    private fun emitPlaybackCommand(type: String, positionMs: Long? = null, shouldPlay: Boolean? = null) {
        if (programmaticControlDepth > 0) return
        val c = controller ?: return
        emitLocalCommand(
            PlaybackCommand(
                type = type,
                source = PlaybackCommandSource.LOCAL,
                queue = pendingQueue.toList(),
                currentIndex = c.currentMediaItemIndex.takeIf { it >= 0 },
                positionMs = positionMs ?: currentPosition(c),
                shouldPlay = shouldPlay,
            )
        )
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                /** 服务被系统/划掉回收后控制器会断开，置空以便下次交互时重连。 */
                override fun onDisconnected(controller: MediaController) {
                    if (this@PlayerController.controller !== controller) return
                    this@PlayerController.controller = null
                    resolvedUrls.clear()
                    resolvedSources.clear()
                    resolvingIndex = -1
                    _state.value = _state.value.copy(playing = false, playWhenReady = false)
                }
            })
            .buildAsync()
        future.addListener({
            runCatching {
                val c = future.get()
                controller = c
                c.addListener(listener)
                lastKnownMediaItemCount = c.mediaItemCount
                lastKnownMediaItemIndex = c.currentMediaItemIndex
                startPositionLoop()
            }
        }, MoreExecutors.directExecutor())
    }

    /** 等待控制器连接（冷启动/服务重建后可能需要重连）。 */
    private suspend fun awaitController(timeoutMs: Long = 3000L): MediaController? {
        if (controller == null) init(appContext)
        var waited = 0L
        while (controller == null && waited < timeoutMs) {
            delay(50)
            waited += 50
        }
        return controller
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: androidx.media3.common.Player.Events) {
            syncFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 关闭「自动连播」时，播完自动跳到下一首就地暂停
            val autoPlayNext = AppGraph.store.autoPlayNext
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !autoPlayNext) {
                controller?.let { withProgrammaticControl { it.pause() } }
            }
            val idx = controller?.currentMediaItemIndex ?: -1
            if (idx >= 0) ensureUrl(idx)
            // 跟随源开着时，后台去目标源匹配这首歌的封面 / 歌手（不阻塞切歌）
            pendingQueue.getOrNull(idx)?.let { playing ->
                scope.launch { runCatching { CrossSourceMatch.refreshFollow(playing) } }
            }
            persistProgress(force = true)
            if (programmaticControlDepth > 0) {
                lastKnownMediaItemIndex = idx
                return
            }
            val previousIndex = lastKnownMediaItemIndex
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                    lastQueueTransitionAtMs = System.currentTimeMillis()
                    emitLocalCommand(
                        PlaybackCommand(
                            type = "TRACK_FINISHED",
                            source = PlaybackCommandSource.LOCAL,
                            queue = pendingQueue.toList(),
                            currentIndex = idx.takeIf { it >= 0 },
                            positionMs = 0L,
                            shouldPlay = autoPlayNext,
                        )
                    )
                }

                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> {
                    lastQueueTransitionAtMs = System.currentTimeMillis()
                    // 等 prepare/play 生效后再取播放意愿，避免把「即将播放」误报成「暂停」
                    scope.launch {
                        delay(80)
                        emitPlaybackCommand(
                            "PLAY_PLAYLIST",
                            positionMs = 0L,
                            shouldPlay = controller?.playWhenReady
                        )
                    }
                }

                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                    val type = when {
                        idx == previousIndex -> "SET_TRACK"
                        idx == previousIndex + 1 -> "NEXT"
                        idx == previousIndex - 1 -> "PREVIOUS"
                        else -> "PLAY_FROM_QUEUE"
                    }
                    emitPlaybackCommand(type, positionMs = 0L, shouldPlay = controller?.playWhenReady)
                }
            }
            lastKnownMediaItemIndex = idx
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (programmaticControlDepth > 0) return
            if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
            // 切队列/切曲瞬间的播放状态由对应命令一并表达，避免重复上抛
            if (System.currentTimeMillis() - lastQueueTransitionAtMs < 800L) return
            emitPlaybackCommand(if (playWhenReady) "PLAY" else "PAUSE")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (programmaticControlDepth > 0) return
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
            emitPlaybackCommand("SEEK", positionMs = newPosition.positionMs)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (programmaticControlDepth > 0) return
            emitPlaybackCommand("PLAYBACK_MODE")
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (programmaticControlDepth > 0) return
            emitPlaybackCommand("PLAYBACK_MODE")
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val c = controller ?: return
            val count = c.mediaItemCount
            val countChanged = count != lastKnownMediaItemCount
            lastKnownMediaItemCount = count
            if (!countChanged || programmaticControlDepth > 0) return
            if (c.currentMediaItemIndex < 0) return
            // 队列增删（append 等）与整体换队列都会改条目数：整体换队列会另有
            // 曲目切换事件负责上抛，延后确认一次避免同一次操作发出两条命令。
            scope.launch {
                delay(80)
                if (programmaticControlDepth > 0) return@launch
                if (System.currentTimeMillis() - lastQueueTransitionAtMs < 100L) return@launch
                emitPlaybackCommand(
                    "SET_QUEUE",
                    positionMs = currentPosition(c),
                    shouldPlay = c.playWhenReady,
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = "播放失败（${error.errorCodeName}）")
            // 多半是 URL 缺失/过期，惰性补链后重试
            val c = controller ?: return
            val idx = c.currentMediaItemIndex
            if (idx >= 0 && idx != resolvingIndex) {
                // 这条链接已证实放不出来：把它真正的来源记为失效，
                // 重取时才会跳过它去别的源找，而不是拿同一条坏链接反复重试
                pendingQueue.getOrNull(idx)?.let { song ->
                    SourceFallback.markDead(song.key, resolvedSources[song.key]?.source ?: song.source)
                }
                resolvingIndex = idx
                // 按出错前的播放意愿续播：暂停中（含 App 启动恢复）只补链，不擅自开始放
                val resume = c.playWhenReady
                scope.launch {
                    delay(300)
                    resolveAndRetry(idx, PlaybackCommandSource.LOCAL_SAFETY, resume = resume)
                    resolvingIndex = -1
                }
            }
        }
    }

    private fun startPositionLoop() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                _state.value = _state.value.copy(
                    positionMs = currentPosition(c),
                    durationMs = c.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                    playing = c.isPlaying,
                    playWhenReady = c.playWhenReady,
                    playbackState = c.playbackState,
                )
                persistProgress(force = false)
            }
        }
    }

    /**
     * 当前播放位置。恢复会话的曲目尚未取链（URI 为空），
     * 播放器读数不可靠，沿用状态里已恢复的进度，避免界面与存档被清零。
     */
    private fun currentPosition(c: MediaController): Long =
        if (currentHasUrl(c) || c.isPlaying) c.currentPosition else _state.value.positionMs

    private fun currentHasUrl(c: MediaController): Boolean =
        !c.currentMediaItem?.localConfiguration?.uri?.toString().isNullOrBlank()

    /** 持久化「上次播放进度」（节流 3 秒；force 用于切歌/暂停等关键点）。 */
    private var lastPersistAt = 0L

    private fun persistProgress(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < 3000L) return
        lastPersistAt = now
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        if (idx < 0 || pendingQueue.isEmpty()) return
        val duration = _state.value.durationMs
        val position = currentPosition(c)
        // 播放到结尾（含循环重播前一刻）存 0，下次从头开始
        val start = if (duration > 0L && position >= duration - 2000L) 0L else position
        AppGraph.store.savePlaybackProgress(idx, start)
    }

    private fun syncFromController() {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        val song = pendingQueue.getOrNull(idx)
        _currentMediaUrl.value = song?.let { resolvedUrls[it.key] }
        val sourceInfo = song?.let { resolvedSources[it.key] }
        _state.value = _state.value.copy(
            index = idx,
            // 跟随源的封面 / 歌手在这层套上（没开跟随就原样返回，队列里存的仍是源站信息）
            current = (song ?: _state.value.current)?.let { AppGraph.store.applyFollowMeta(it) },
            currentSource = sourceInfo?.source,
            fromCache = sourceInfo?.fromCache == true,
            playing = c.isPlaying,
            positionMs = currentPosition(c),
            durationMs = c.duration.takeIf { it > 0 } ?: (song?.durationMs ?: 0L),
            repeatMode = c.repeatMode,
            shuffle = c.shuffleModeEnabled,
            playWhenReady = c.playWhenReady,
            playbackState = c.playbackState,
            error = null,
        )
    }

    /**
     * 跨源跟随切换后立刻刷新展示的封面 / 歌手。
     * 队列里存的始终是源站信息，这里只重算展示层，不开跟随就原样还原。
     */
    fun refreshDisplayMetadata() {
        val song = pendingQueue.getOrNull(controller?.currentMediaItemIndex ?: -1)
            ?: _state.value.current
            ?: return
        _state.value = _state.value.copy(current = AppGraph.store.applyFollowMeta(song))
    }

    /** 播放一首歌（从头）。 */
    suspend fun playNow(context: Context, song: Song) {
        playQueue(context, listOf(song), startIndex = 0)
    }

    /** 播放整个队列；仅预先解析起始曲的 URL，其余惰性补链。 */
    suspend fun playQueue(context: Context, songs: List<Song>, startIndex: Int = 0) {
        // 播放页手工改过的歌名/歌手/封面，入队前统一套用
        val songs = songs.map { AppGraph.store.applySongInfoEdit(it) }
        if (songs.isEmpty()) return
        init(context)
        _state.value = _state.value.copy(preparing = true, error = null)

        // 等待 MediaController 连接（App 冷启动后立即点播放时可能是 null）
        val c = awaitController()
        if (c == null) {
            _state.value = _state.value.copy(preparing = false, error = "播放器初始化失败，请重试")
            return
        }

        _state.value = _state.value.copy(resolving = true)
        val first = resolveForPlayback(songs[startIndex], advanceOverride = false)
        _state.value = _state.value.copy(resolving = false)
        if (!first.result.ok) {
            _state.value = _state.value.copy(preparing = false, error = first.result.reason)
            return
        }
        if (!first.fromOverride) {
            AudioCache.cacheInBackground(songs[startIndex].key, first.result.url)
            rememberLocalCopy(songs[startIndex])
        }

        val items = ArrayList<MediaItem>(songs.size)
        for ((i, s) in songs.withIndex()) {
            items.add(toMediaItem(s, if (i == startIndex) first.result.url else resolvedUrls[s.key].orEmpty()))
        }
        pendingQueue.clear()
        pendingQueue.addAll(songs)
        resolvedUrls.clear()
        resolvedSources.clear()
        resolvedUrls[songs[startIndex].key] = first.result.url
        rememberSource(songs[startIndex].key, first.result)
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
        rememberRecent(songs[startIndex])
        _state.value = _state.value.copy(
            preparing = false,
            queue = songs,
            index = startIndex,
            current = songs[startIndex],
            currentSource = first.result.source,
            fromCache = first.result.fromCache,
        )
        AppGraph.store.savePlaybackQueue(pendingQueue)
        persistProgress(force = true)
    }

    /** 为 index 处的曲目补链（若该曲目尚未解析过 URL）。 */
    private fun ensureUrl(index: Int) {
        if (index < 0 || index >= pendingQueue.size || index == resolvingIndex) return
        val c = controller ?: return
        val song = pendingQueue[index]
        if (resolvedUrls.containsKey(song.key)) return
        resolvingIndex = index
        _state.value = _state.value.copy(resolving = true)
        scope.launch {
            val resolved = resolveForPlayback(song, advanceOverride = false)
            resolvingIndex = -1
            _state.value = _state.value.copy(resolving = false)
            if (!resolved.result.ok) {
                _state.value = _state.value.copy(error = resolved.result.reason)
                return@launch
            }
            resolvedUrls[song.key] = resolved.result.url
            rememberSource(song.key, resolved.result)
            if (!resolved.fromOverride) {
                AudioCache.cacheInBackground(song.key, resolved.result.url)
                rememberLocalCopy(song)
            }
            val isCurrent = c.currentMediaItemIndex == index
            val position = if (isCurrent) c.currentPosition else 0L
            withProgrammaticControl {
                c.replaceMediaItem(index, toMediaItem(song, resolved.result.url))
                if (isCurrent) c.seekTo(index, position)
                c.prepare()
                c.play()
            }
            _state.value = _state.value.copy(error = null)
        }
    }

    /**
     * 补链并重播 index 处的曲目；一起听候选直链失败时自动换下一个候选，用尽后退回本机取链。
     * [commandSource] 非 LOCAL（播放失败自动恢复等）不会把这次播放上抛成用户命令。
     *
     * [resume] 决定取链成功后要不要接着放：补链本身不改变播放意愿，
     * 默认只在用户主动点播时播放；失败自动恢复、换音质这类场景要传入**出错/改动前**的
     * 播放意愿，否则暂停中的曲目（含 App 启动时的恢复）会被悄悄播起来。
     */
    suspend fun resolveAndRetry(
        index: Int,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
        resume: Boolean = commandSource == PlaybackCommandSource.LOCAL
    ) {
        val c = controller ?: return
        val song = pendingQueue.getOrNull(index) ?: return
        _state.value = _state.value.copy(resolving = true)
        val resolved = resolveForPlayback(song, advanceOverride = true)
        _state.value = _state.value.copy(resolving = false)
        if (!resolved.result.ok) {
            _state.value = _state.value.copy(error = resolved.result.reason)
            return
        }
        resolvedUrls[song.key] = resolved.result.url
        rememberSource(song.key, resolved.result)
        if (!resolved.fromOverride) {
            AudioCache.cacheInBackground(song.key, resolved.result.url)
            rememberLocalCopy(song)
        }
        // 播放器里还没装回队列（服务重启/恢复未完成）时先补上，避免 replaceMediaItem 越界
        if (c.mediaItemCount <= index) {
            withProgrammaticControl {
                c.setMediaItems(pendingQueue.map { toMediaItem(it, resolvedUrls[it.key].orEmpty()) }, index, _state.value.positionMs)
            }
        }
        val isCurrent = c.currentMediaItemIndex == index
        // 恢复会话后首次播放时播放器可能还没就绪，取状态里的进度兜底
        val position = if (isCurrent) maxOf(c.currentPosition, _state.value.positionMs) else 0L
        withProgrammaticControl {
            c.replaceMediaItem(index, toMediaItem(song, resolved.result.url))
            if (isCurrent) c.seekTo(index, position)
            c.prepare()
        }
        if (resume) {
            if (commandSource == PlaybackCommandSource.LOCAL) {
                c.play()
            } else {
                // 程序化补链引发的续播不算用户操作，不向一起听上抛
                withProgrammaticControl { c.play() }
            }
        }
        _currentMediaUrl.value = resolved.result.url
        // 换到能放的链接了，清掉失效判定，避免一次网络抖动永久拉黑某个源
        SourceFallback.clearDead(song.key)
        _state.value = _state.value.copy(error = null)
    }

    /** 音质/音效设置变化后，按新设置重取当前曲目链接（保持当前的播放/暂停状态）。 */
    suspend fun reapplyQuality() {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        if (index < 0) return
        resolvingIndex = -1
        pendingQueue.getOrNull(index)?.let { resolvedUrls.remove(it.key) }
        resolveAndRetry(index, PlaybackCommandSource.LOCAL_SAFETY, resume = c.playWhenReady)
    }

    // ---------- 一起听远端同步入口 ----------

    /**
     * 一起听远端同步：整体替换队列并从 [index] 播放/暂停，不产生用户命令。
     * 目标曲目优先使用 [setStreamOverride] 设置的权威直链，没有时才本机取链。
     */
    suspend fun applyRemotePlaylist(
        songs: List<Song>,
        index: Int,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        if (songs.isEmpty()) return
        init(appContext)
        val c = awaitController() ?: return
        val startIndex = index.coerceIn(0, songs.lastIndex)
        _state.value = _state.value.copy(preparing = true, error = null)

        val resolved = resolveForPlayback(songs[startIndex], advanceOverride = false)
        if (!resolved.result.ok) {
            _state.value = _state.value.copy(preparing = false, error = resolved.result.reason)
            return
        }
        if (!resolved.fromOverride) {
            AudioCache.cacheInBackground(songs[startIndex].key, resolved.result.url)
            rememberLocalCopy(songs[startIndex])
        }

        val items = songs.mapIndexed { i, s ->
            toMediaItem(s, if (i == startIndex) resolved.result.url else resolvedUrls[s.key].orEmpty())
        }
        pendingQueue.clear()
        pendingQueue.addAll(songs)
        resolvedUrls.clear()
        resolvedSources.clear()
        resolvedUrls[songs[startIndex].key] = resolved.result.url
        rememberSource(songs[startIndex].key, resolved.result)
        val startPosition = positionMs.coerceAtLeast(0L)
        withProgrammaticControl {
            c.setMediaItems(items, startIndex, startPosition)
            c.prepare()
            if (playWhenReady) c.play() else c.pause()
        }
        _currentMediaUrl.value = resolved.result.url
        _state.value = _state.value.copy(
            preparing = false,
            queue = songs,
            index = startIndex,
            current = songs[startIndex],
            positionMs = startPosition,
        )
        AppGraph.store.savePlaybackQueue(pendingQueue)
        AppGraph.store.savePlaybackProgress(startIndex, startPosition)
    }

    /**
     * 一起听远端同步：原地更新队列（重排/追加），尽量不打断当前曲目。
     * 已解析直链按曲目键保留。
     */
    fun applyRemoteQueueUpdate(songs: List<Song>, index: Int) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val targetIndex = index.coerceIn(0, songs.lastIndex)
        val position = c.currentPosition.coerceAtLeast(0L)
        pendingQueue.clear()
        pendingQueue.addAll(songs)
        val items = songs.map { s -> toMediaItem(s, resolvedUrls[s.key].orEmpty()) }
        withProgrammaticControl {
            c.setMediaItems(items, targetIndex, if (c.currentMediaItemIndex == targetIndex) position else 0L)
        }
        _state.value = _state.value.copy(
            queue = songs,
            index = targetIndex,
            current = songs[targetIndex],
        )
        AppGraph.store.savePlaybackQueue(pendingQueue)
    }

    /**
     * 用户在播放页改了曲目信息：把编辑结果就地写回队列与状态，
     * 并重建 MediaItem 让锁屏/通知栏的标题封面同步。不重新取链、不打断播放。
     */
    fun applySongInfoEdit(song: Song) {
        val c = controller ?: return
        val index = pendingQueue.indexOfFirst { it.key == song.key }
        if (index < 0) return
        pendingQueue[index] = song
        val queue = pendingQueue.toList()
        _state.value = _state.value.copy(
            queue = queue,
            current = if (_state.value.current?.key == song.key) song else _state.value.current,
        )
        AppGraph.store.savePlaybackQueue(pendingQueue)
        // 画面（迷你条 / 播放页）读的是 StateFlow，到这里已经生效；
        // 下面只为了让通知栏/锁屏的元数据也换过来，播放位置原样保留
        if (c.mediaItemCount != pendingQueue.size) return
        val position = c.currentPosition.coerceAtLeast(0L)
        val wasPlaying = c.playWhenReady
        withProgrammaticControl {
            c.setMediaItems(
                queue.map { toMediaItem(it, resolvedUrls[it.key].orEmpty()) },
                c.currentMediaItemIndex,
                position,
            )
            c.prepare()
            if (wasPlaying) c.play() else c.pause()
        }
    }

    /** 一起听权威直链：设置/更新某曲目的候选直链（听众优先使用）。 */
    fun setStreamOverride(songKey: String, urls: List<String>) {
        val normalized = urls.filter { it.isNotBlank() }
        if (normalized.isEmpty()) {
            clearStreamOverride(songKey)
            return
        }
        val previous = streamOverrides[songKey]
        streamOverrides[songKey] = normalized
        if (previous != normalized) {
            streamOverrideCursor[songKey] = 0
            streamOverrideExhausted.remove(songKey)
        }
    }

    fun clearStreamOverride(songKey: String) {
        streamOverrides.remove(songKey)
        streamOverrideCursor.remove(songKey)
        streamOverrideExhausted.remove(songKey)
    }

    fun streamOverrideUrls(songKey: String): List<String> = streamOverrides[songKey].orEmpty()

    /** 取链：有权威直链（且未用尽）时优先用候选，否则走本机解析。 */
    private suspend fun resolveForPlayback(song: Song, advanceOverride: Boolean): ResolvedPlaybackUrl {
        overrideUrlFor(song.key, advanceOverride)?.let { url ->
            return ResolvedPlaybackUrl(ResolveResult(url), fromOverride = true)
        }
        return ResolvedPlaybackUrl(PlaybackResolver.resolve(song), fromOverride = false)
    }

    private fun overrideUrlFor(songKey: String, advance: Boolean): String? {
        val urls = streamOverrides[songKey] ?: return null
        if (urls.isEmpty() || songKey in streamOverrideExhausted) return null
        var index = streamOverrideCursor[songKey] ?: 0
        if (advance) {
            if (index >= urls.lastIndex) {
                streamOverrideExhausted.add(songKey)
                return null
            }
            index++
        }
        streamOverrideCursor[songKey] = index.coerceIn(0, urls.lastIndex)
        return urls[streamOverrideCursor[songKey] ?: 0]
    }

    private data class ResolvedPlaybackUrl(val result: ResolveResult, val fromOverride: Boolean)

    /** 一条播放链接的实际出处。 */
    private data class ResolvedSourceInfo(
        val source: top.aryun.yzliyin.core.model.Source?,
        val fromCache: Boolean,
    )

    /** 一起听同步倍速（软同步微调，1.0 为常态）。 */
    fun setPlaybackRate(rate: Float) {
        val c = controller ?: return
        val normalized = rate.coerceIn(0.5f, 2.0f)
        if (kotlin.math.abs(c.playbackParameters.speed - normalized) < 0.001f) return
        withProgrammaticControl { c.playbackParameters = PlaybackParameters(normalized) }
    }

    fun resetPlaybackRate() {
        val c = controller ?: return
        if (kotlin.math.abs(c.playbackParameters.speed - 1f) < 0.001f) return
        withProgrammaticControl { c.playbackParameters = PlaybackParameters(1f) }
    }

    fun currentPlaybackRate(): Float = controller?.playbackParameters?.speed ?: 1f

    /** 是否处于取链等待（一起听判定本地解析 pending）。 */
    fun isPendingMediaLoadActive(): Boolean = resolvingIndex >= 0 || _state.value.resolving

    /** 本机传输是否活跃（播放中或已开始播放）。 */
    fun isTransportActive(): Boolean {
        val c = controller ?: return false
        return c.isPlaying || c.playWhenReady
    }

    private fun rememberRecent(song: Song) {
        runCatching { AppGraph.store.pushRecentSong(song) }
    }

    /**
     * 记录「这首已经落盘」，供曲库「本地」页列出。
     * 只在真的开始写盘时调用，文件被淘汰后由曲库页按 [AudioCache.cached] 复核并清理。
     */
    private fun rememberLocalCopy(song: Song) {
        runCatching { AppGraph.store.pushDownloadedSong(song) }
    }

    private fun toMediaItem(s: Song, url: String): MediaItem {
        val meta = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(s.name)
            .setArtist(s.artist)
            .setAlbumTitle(s.albumName)
            .setArtworkUri(s.cover.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) })
            .build()
        return MediaItem.Builder()
            .setMediaId(s.key)
            .setUri(url)
            .setMediaMetadata(meta)
            .build()
    }

    fun togglePlay() {
        val c = controller
        if (c == null) {
            // 服务被回收后重连：恢复队列后取链继续播放
            if (pendingQueue.isEmpty() && _state.value.current == null) return
            scope.launch { resumeAfterReconnect() }
            return
        }
        if (c.isPlaying) {
            pause()
            return
        }
        val idx = c.currentMediaItemIndex
        val song = pendingQueue.getOrNull(idx)
        // 恢复会话/补链丢链的曲目：先取链再播，避免播放器直接报错
        if (idx >= 0 && song != null && !resolvedUrls.containsKey(song.key) && !currentHasUrl(c)) {
            scope.launch { resolveAndRetry(idx) }
            return
        }
        play()
    }

    /** 播放（一起听远端同步传 [PlaybackCommandSource.REMOTE_SYNC]）。 */
    fun play(commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) {
        val c = controller ?: return
        if (commandSource == PlaybackCommandSource.REMOTE_SYNC) {
            withProgrammaticControl { c.play() }
        } else {
            c.play()
        }
    }

    /** 暂停（一起听远端同步传 [PlaybackCommandSource.REMOTE_SYNC]）。 */
    fun pause(
        forcePersist: Boolean = true,
        commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
    ) {
        val c = controller ?: return
        if (commandSource == PlaybackCommandSource.REMOTE_SYNC) {
            withProgrammaticControl { c.pause() }
        } else {
            c.pause()
        }
        if (forcePersist) persistProgress(force = true)
    }

    /** 播放服务断开后重连：重建队列（服务内为空时）并按当前进度继续播放。 */
    private suspend fun resumeAfterReconnect() {
        val songs = pendingQueue.toList()
            .ifEmpty { AppGraph.store.loadPlaybackSession()?.songs ?: return }
        val c = awaitController() ?: return
        val index = _state.value.index.coerceIn(0, songs.size - 1)
        if (pendingQueue.isEmpty()) pendingQueue.addAll(songs)
        resolvedUrls.clear()
        resolvedSources.clear()
        if (c.mediaItemCount == 0) {
            withProgrammaticControl {
                c.setMediaItems(songs.map { toMediaItem(it, "") }, index, _state.value.positionMs)
            }
        }
        resolveAndRetry(index)
    }

    fun next() {
        controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    }

    fun prev() {
        controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
    }

    fun seekTo(positionMs: Long, commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL) {
        val c = controller ?: return
        if (commandSource == PlaybackCommandSource.REMOTE_SYNC) {
            withProgrammaticControl { c.seekTo(positionMs) }
        } else {
            c.seekTo(positionMs)
        }
        _state.value = _state.value.copy(positionMs = positionMs)
        val idx = c.currentMediaItemIndex
        if (idx >= 0 && pendingQueue.isNotEmpty()) {
            AppGraph.store.savePlaybackProgress(idx, positionMs)
        }
    }

    fun cycleRepeatMode() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        syncFromController()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        syncFromController()
    }

    /** 一起听远端同步：应用房间的重复/随机模式，不产生用户命令。 */
    fun applyPlaybackMode(repeatMode: Int?, shuffleEnabled: Boolean?) {
        val c = controller ?: return
        withProgrammaticControl {
            repeatMode?.let { c.repeatMode = it }
            shuffleEnabled?.let { c.shuffleModeEnabled = it }
        }
        syncFromController()
    }

    /** 音效设置变化后立即生效（不重新取链）。 */
    fun refreshAudioEffect() {
        PlaybackService.instance?.refreshEffect()
    }

    /** 把歌曲追加到队列末尾。 */
    fun append(songs: List<Song>) {
        if (songs.isEmpty()) return
        // 与 playQueue 一致：手工编辑过的曲目信息在这里套用
        val songs = songs.map { AppGraph.store.applySongInfoEdit(it) }
        val c = controller ?: return
        if (pendingQueue.isEmpty()) {
            scope.launch { playQueue(appContext, songs, 0) }
            return
        }
        pendingQueue.addAll(songs)
        c.addMediaItems(songs.map { toMediaItem(it, resolvedUrls[it.key].orEmpty()) })
        _state.value = _state.value.copy(queue = pendingQueue.toList())
        AppGraph.store.savePlaybackQueue(pendingQueue)
        persistProgress(force = true)
    }

    /**
     * 冷启动恢复上次播放：先还原界面状态（迷你播放条立即出现），
     * 再把队列装回播放器并保持暂停；用户点播放时才取链。
     * 设置里开了「启动时自动播放」时，装回队列后直接取链续播。
     *
     * 注意不能用 `controller != null` 判断“已有播放”：Application 启动时就会
     * 预连接 MediaController，该守卫会让恢复永远被跳过。
     */
    fun restoreLastSession(context: Context) {
        if (_state.value.queue.isNotEmpty() || pendingQueue.isNotEmpty()) return
        val session = AppGraph.store.loadPlaybackSession() ?: return
        val songs = session.songs
        if (songs.isEmpty()) return
        val index = session.index.coerceIn(0, songs.size - 1)

        pendingQueue.addAll(songs)
        _state.value = _state.value.copy(
            queue = songs,
            index = index,
            current = songs[index],
            positionMs = session.positionMs,
            durationMs = songs[index].durationMs,
            playing = false,
        )

        init(context)
        scope.launch {
            val c = awaitController()
            if (c == null) return@launch
            // 等连接期间用户已点播：状态被替换或已开始播放，放弃装回旧队列
            if (_state.value.queue.size != songs.size ||
                _state.value.current?.key != songs[index].key ||
                _state.value.playing ||
                _state.value.preparing
            ) {
                return@launch
            }
            // 服务里已有队列（后台暂停中）：以服务为准，不覆盖
            if (c.mediaItemCount > 0) {
                val liveIndex = c.currentMediaItemIndex.coerceIn(0, c.mediaItemCount - 1)
                pendingQueue.getOrNull(liveIndex)?.let {
                    _state.value = _state.value.copy(index = liveIndex, current = it)
                }
                if (AppGraph.store.autoPlayOnLaunch) {
                    // 队列还在、只是停在暂停态：开了「启动时自动播放」就直接续播
                    withProgrammaticControl {
                        c.prepare()
                        c.play()
                    }
                } else {
                    // 没开就停在暂停态：服务若还挂着上次的播放意愿，打开 App 时收住，
                    // 免得「打开软件就自己响」
                    withProgrammaticControl { c.pause() }
                    _state.value = _state.value.copy(playing = false, playWhenReady = false)
                }
                return@launch
            }
            resolvedUrls.clear()
            resolvedSources.clear()
            withProgrammaticControl {
                c.setMediaItems(songs.map { toMediaItem(it, "") }, index, session.positionMs)
                c.pause()
            }
            // 设置里开了「启动时自动播放」：装回队列后直接取链续播
            if (AppGraph.store.autoPlayOnLaunch) {
                resolveAndRetry(index, PlaybackCommandSource.LOCAL_SAFETY, resume = true)
            }
        }
    }
}
