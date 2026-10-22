package top.aryun.yzliyin.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Song
import kotlin.random.Random

/**
 * 长时长播放确认闸门。
 *
 * 设置里开了「长时长播放确认」之后，网易云 / 酷狗 / 哔哩哔哩 / YouTube 里时长达到
 * 阈值的曲目（超长音频、电台回放、直播录像等）先拦下来，由 App 根部（ui/App.kt）
 * 弹出两段式对话框：**确定播放 → 算一道数学题**，两步都过了才真正开播。
 *
 * 三处入口全都过这道闸，缺一处就能被绕过去：
 *
 * 1. [PlayerController.playQueue] —— 点歌入队（首页、搜索、歌单、曲库、追加队列）。
 * 2. [PlayerController.onMediaItemTransition] —— 上一首 / 下一首 / 自动连播 / 队列内跳曲。
 *    以前只拦 1，切到超长歌、或切走再切回来都能绕过。
 * 3. [PlayerController.togglePlay] —— 停在超长歌上再按播放。
 *
 * 冷启动恢复（restoreLastSession）在 setMediaItems 时播放器尚未 playWhenReady，
 * 那次过渡会被放行，免得刚打开 App 就糊一个弹框上来。
 */
object PlaybackGate {

    /**
     * 确认后怎么续上：点播要整队重装，切歌 / 恢复只需把已就位的曲目放行。
     */
    enum class Mode {
        /** 点歌入队：确认后由 [proceed] 带着同一份队列回 [PlayerController.playQueue]。 */
        QUEUE,

        /** 切歌 / 恢复：播放器已停在目标曲上，确认后直接恢复播放。 */
        RESUME,
    }

    /**
     * 一次待确认的请求。整个队列一起存下来，确认后原样回放，
     * 这样「确定播放」之后放的仍然是用户点的那一整列，而不是只剩一首。
     *
     * [context] 存 applicationContext：弹框期间用户可能切页面，握着 Activity 会漏。
     */
    data class Request(
        val context: Context,
        val songs: List<Song>,
        val startIndex: Int,
        val song: Song,
        val durationMs: Long,
        val limitMs: Long,
        val mode: Mode = Mode.QUEUE,
        /** 第二步算式：`$left $op $right`，正确答案是 [answer]。 */
        val left: Int = 0,
        val op: Char = '+',
        val right: Int = 0,
        val answer: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _pending = MutableStateFlow<Request?>(null)

    /** 待确认的请求；null 表示没有弹框在等。App 据此渲染对话框。 */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /**
     * 放行窗口：确认过的曲目 key 在 [APPROVED_WINDOW_MS] 内不再二次拦。
     *
     * 点播确认后要连过两道闸（playQueue 装队 → setMediaItems 触发的切歌过渡），
     * 用「按 key + 限时」而不是「只放行一次」，免得第二道又把刚确认的那首拦下来。
     */
    private const val APPROVED_WINDOW_MS = 5_000L

    @Volatile
    private var approvedKey: String? = null

    @Volatile
    private var approvedAtMs = 0L

    private fun isApproved(key: String): Boolean =
        approvedKey == key && System.currentTimeMillis() - approvedAtMs < APPROVED_WINDOW_MS

    /**
     * 点歌入队前的闸门。返回 true 表示已经拦下（请求已挂起、等用户决定），
     * 调用方应立刻停下、不要继续开播；返回 false 表示照常放行。
     */
    fun intercept(context: Context, songs: List<Song>, startIndex: Int): Boolean =
        gate(context, songs, startIndex, Mode.QUEUE)

    /**
     * 切歌 / 恢复播放前的闸门。拦下时调用方**必须先停住播放器**，
     * 否则弹框还没出来歌已经在响了。
     *
     * 返回 true 表示已拦下（[proceed] 时走 [Mode.RESUME]），false 表示放行。
     */
    fun interceptResume(context: Context, queue: List<Song>, index: Int): Boolean =
        gate(context, queue, index, Mode.RESUME)

    private fun gate(
        context: Context,
        queue: List<Song>,
        index: Int,
        mode: Mode,
    ): Boolean {
        if (!enabled()) return false
        // 弹框已经在等了：别把用户正要答的那道算题覆盖掉
        if (_pending.value != null) return true
        val song = queue.getOrNull(index) ?: return false
        // 刚确认过的那首（或紧接着的二次过渡）直接放行
        if (isApproved(song.key)) return false
        val limitMs = limitMs()
        // 时长未知（0）的曲目无从判断，一律放行，免得把所有歌都拦一遍
        val durationMs = song.durationMs
        if (durationMs <= 0L || durationMs < limitMs) return false

        _pending.value = Request(
            context = context.applicationContext,
            songs = queue,
            startIndex = index,
            song = song,
            durationMs = durationMs,
            limitMs = limitMs,
            mode = mode,
        ).withQuestion()
        return true
    }

    /** 用户取消播放（或点掉弹框）：收起，什么都不做。 */
    fun cancel() {
        _pending.value = null
    }

    /** 两步都过了：放行并按当初的入口继续。 */
    fun proceed() {
        val request = _pending.value ?: return
        _pending.value = null
        approvedKey = request.song.key
        approvedAtMs = System.currentTimeMillis()
        scope.launch {
            when (request.mode) {
                Mode.QUEUE -> PlayerController.playQueue(
                    request.context,
                    request.songs,
                    request.startIndex,
                )

                Mode.RESUME -> PlayerController.resumeAfterGate()
            }
        }
    }

    private fun enabled(): Boolean = runCatching { AppGraph.store.durationWarnEnabled }
        .getOrDefault(false)

    private fun limitMs(): Long =
        (runCatching { AppGraph.store.durationWarnMinutes }.getOrDefault(0) * 60_000L)
            .coerceAtLeast(60_000L)

    private fun Request.withQuestion(): Request {
        // 加、减、乘轮着来：全是加法的话记住几道题就能无脑过
        val (a, op, b) = when (Random.nextInt(3)) {
            0 -> Triple(Random.nextInt(3, 31), '+', Random.nextInt(3, 31))
            1 -> Triple(Random.nextInt(2, 13), '×', Random.nextInt(2, 13))
            else -> {
                val minuend = Random.nextInt(10, 41)
                Triple(minuend, '-', Random.nextInt(1, minuend))
            }
        }
        val answer = when (op) {
            '+' -> a + b
            '-' -> a - b
            else -> a * b
        }
        return copy(left = a, op = op, right = b, answer = answer)
    }
}
