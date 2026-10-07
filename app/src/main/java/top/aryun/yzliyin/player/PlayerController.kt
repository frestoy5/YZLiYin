package top.aryun.yzliyin.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
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
)

/**
 * 播放控制器：单例，桥接 Compose 与 Media3 MediaController。
 * 数据源仅网易云：起始曲预先取链，其余曲目在切换/出错时惰性补链。
 */
object PlayerController {

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private lateinit var appContext: Context
    private val pendingQueue = ArrayList<Song>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionJob: Job? = null
    private var resolvingIndex = -1

    /** 已成功写入播放 URL 的曲目（跨源唯一键，避免重复取链）。 */
    private val resolvedIds = HashSet<String>()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching {
                val c = future.get()
                controller = c
                c.addListener(listener)
                startPositionLoop()
            }
        }, MoreExecutors.directExecutor())
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: androidx.media3.common.Player.Events) {
            syncFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 关闭「自动连播」时，播完自动跳到下一首就地暂停
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                !AppGraph.store.autoPlayNext
            ) {
                controller?.pause()
            }
            val idx = controller?.currentMediaItemIndex ?: -1
            if (idx >= 0) ensureUrl(idx)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = "播放失败（${error.errorCodeName}）")
            // 多半是 URL 缺失/过期，惰性补链后重试
            val c = controller ?: return
            val idx = c.currentMediaItemIndex
            if (idx >= 0 && idx != resolvingIndex) {
                resolvingIndex = idx
                scope.launch {
                    delay(300)
                    resolveAndRetry(idx)
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
                    positionMs = c.currentPosition,
                    durationMs = c.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                    playing = c.isPlaying,
                )
            }
        }
    }

    private fun syncFromController() {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        val song = pendingQueue.getOrNull(idx)
        _state.value = _state.value.copy(
            index = idx,
            current = song ?: _state.value.current,
            playing = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it > 0 } ?: (song?.durationMs ?: 0L),
            repeatMode = c.repeatMode,
            shuffle = c.shuffleModeEnabled,
            error = null,
        )
    }

    /** 播放一首歌（从头）。 */
    suspend fun playNow(context: Context, song: Song) {
        playQueue(context, listOf(song), startIndex = 0)
    }

    /** 播放整个队列；仅预先解析起始曲的 URL，其余惰性补链。 */
    suspend fun playQueue(context: Context, songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        init(context)
        _state.value = _state.value.copy(preparing = true, error = null)

        // 等待 MediaController 连接（App 冷启动后立即点播放时可能是 null）
        var c = controller
        var waited = 0L
        while (c == null && waited < 3000L) {
            delay(50)
            waited += 50
            c = controller
        }
        if (c == null) {
            _state.value = _state.value.copy(preparing = false, error = "播放器初始化失败，请重试")
            return
        }

        _state.value = _state.value.copy(resolving = true)
        val first = PlaybackResolver.resolve(songs[startIndex])
        _state.value = _state.value.copy(resolving = false)
        if (!first.ok) {
            _state.value = _state.value.copy(preparing = false, error = first.reason)
            return
        }

        val items = ArrayList<MediaItem>(songs.size)
        for ((i, s) in songs.withIndex()) {
            items.add(toMediaItem(s, if (i == startIndex) first.url else ""))
        }
        pendingQueue.clear()
        pendingQueue.addAll(songs)
        resolvedIds.clear()
        resolvedIds.add(songs[startIndex].key)
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
        rememberRecent(songs[startIndex])
        _state.value = _state.value.copy(
            preparing = false, queue = songs, index = startIndex, current = songs[startIndex]
        )
    }

    /** 为 index 处的曲目补链（若该曲目尚未解析过 URL）。 */
    private fun ensureUrl(index: Int) {
        if (index < 0 || index >= pendingQueue.size || index == resolvingIndex) return
        val c = controller ?: return
        val song = pendingQueue[index]
        if (song.key in resolvedIds) return
        resolvingIndex = index
        _state.value = _state.value.copy(resolving = true)
        scope.launch {
            val result = PlaybackResolver.resolve(song)
            resolvingIndex = -1
            _state.value = _state.value.copy(resolving = false)
            if (!result.ok) {
                _state.value = _state.value.copy(error = result.reason)
                return@launch
            }
            resolvedIds.add(song.key)
            val isCurrent = c.currentMediaItemIndex == index
            val position = if (isCurrent) c.currentPosition else 0L
            c.replaceMediaItem(index, toMediaItem(song, result.url))
            if (isCurrent) c.seekTo(index, position)
            c.prepare()
            c.play()
            _state.value = _state.value.copy(error = null)
        }
    }

    /** 补链并重播 index 处的曲目。 */
    suspend fun resolveAndRetry(index: Int) {
        val c = controller ?: return
        val song = pendingQueue.getOrNull(index) ?: return
        _state.value = _state.value.copy(resolving = true)
        val result = PlaybackResolver.resolve(song)
        _state.value = _state.value.copy(resolving = false)
        if (!result.ok) {
            _state.value = _state.value.copy(error = result.reason)
            return
        }
        val isCurrent = c.currentMediaItemIndex == index
        val position = if (isCurrent) c.currentPosition else 0L
        c.replaceMediaItem(index, toMediaItem(song, result.url))
        if (isCurrent) c.seekTo(index, position)
        c.prepare()
        c.play()
        resolvedIds.add(song.key)
        _state.value = _state.value.copy(error = null)
    }

    /** 音质/音效设置变化后，按新设置重取当前曲目链接。 */
    suspend fun reapplyQuality() {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        if (index < 0) return
        resolvingIndex = -1
        pendingQueue.getOrNull(index)?.let { resolvedIds.remove(it.key) }
        resolveAndRetry(index)
    }

    private fun rememberRecent(song: Song) {
        runCatching {
            val store = AppGraph.store
            val ids = song.key + "," + store.recentSongIds
            store.recentSongIds = ids.split(",").distinct().take(30).joinToString(",")
        }
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
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    }

    fun prev() {
        controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
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

    /** 把歌曲追加到队列末尾。 */
    fun append(songs: List<Song>) {
        if (songs.isEmpty()) return
        val c = controller ?: return
        if (pendingQueue.isEmpty()) {
            scope.launch { playQueue(appContext, songs, 0) }
            return
        }
        pendingQueue.addAll(songs)
        c.addMediaItems(songs.map { toMediaItem(it, "") })
        _state.value = _state.value.copy(queue = pendingQueue.toList())
    }
}
