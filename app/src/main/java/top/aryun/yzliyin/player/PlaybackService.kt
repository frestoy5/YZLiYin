package top.aryun.yzliyin.player

import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import top.aryun.yzliyin.core.AppGraph

/**
 * 播放服务（Media3 MediaSessionService）。
 * 真实播放地址由上层（PlayerController）在入队前通过 NeteaseApi.songUrl 解析后写入 MediaItem.uri，
 * 服务只负责播放、音效与媒体会话。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var loudness: LoudnessEnhancer? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player = exo
        exo.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = syncEffect(exo)
        })
        syncEffect(exo)

        mediaSession = MediaSession.Builder(this, exo).build()
    }

    /** 依据设置开/关低频增强音效（media3 底层为 ExoPlayer 的音频会话）。 */
    private fun syncEffect(exo: ExoPlayer) {
        val sid = exo.audioSessionId
        if (sid == C.AUDIO_SESSION_ID_UNSET || sid <= 0) return
        val want = runCatching { AppGraph.store.audioEffect }.getOrDefault(false)
        if (want) {
            val cur = loudness
            if (cur != null) {
                runCatching { cur.setTargetGain(EFFECT_GAIN_mB) }
                return
            }
            loudness = runCatching {
                LoudnessEnhancer(sid).apply {
                    setTargetGain(EFFECT_GAIN_mB)
                    setEnabled(true)
                }
            }.getOrNull()
        } else {
            loudness?.let { runCatching { it.setEnabled(false); it.release() } }
            loudness = null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        loudness?.let { runCatching { it.setEnabled(false); it.release() } }
        loudness = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    private companion object {
        /** 低频增强强度（毫贝）。 */
        const val EFFECT_GAIN_mB = 600
    }
}
