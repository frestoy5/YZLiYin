package top.aryun.yzliyin.player

import top.aryun.yzliyin.core.model.Song

/** 播放命令来源：区分用户操作与一起听远端同步，避免远端应用被当成用户操作再次上抛。 */
enum class PlaybackCommandSource {
    LOCAL,
    LOCAL_SAFETY,
    REMOTE_SYNC
}

/**
 * 播放命令（移植自 NeriPlayer 的同名模型）。
 *
 * 一起听通过监听 [ListenTogetherPlayer.playbackCommandFlow] 把本机用户操作
 * （播放/暂停/切歌/seek/模式切换/换队列）转成房间控制事件。
 */
data class PlaybackCommand(
    val type: String,
    val source: PlaybackCommandSource,
    val timestampMs: Long = System.currentTimeMillis(),
    val queue: List<Song>? = null,
    val currentIndex: Int? = null,
    val positionMs: Long? = null,
    val shouldPlay: Boolean? = null,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null,
    val force: Boolean = false
)
