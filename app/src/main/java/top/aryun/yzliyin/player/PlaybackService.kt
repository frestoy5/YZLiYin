package top.aryun.yzliyin.player

import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import top.aryun.yzliyin.core.AppGraph
import top.aryun.yzliyin.core.model.Source
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.youtube.platform.isYouTubeGoogleVideoHost

/**
 * 播放服务（Media3 MediaSessionService）。
 * 真实播放地址由上层（PlayerController）在入队前通过 PlaybackResolver 解析后写入 MediaItem.uri，
 * 服务负责播放、音效、媒体会话，以及按源给数据源补请求头
 * （B 站 CDN 要求 `Referer` + 浏览器 UA，否则 403）。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var loudness: LoudnessEnhancer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this),
        ) { spec ->
            val host = spec.uri.host.orEmpty()
            when {
                BILI_CDN_HOSTS.any { host.endsWith(it) } ->
                    spec.withRequestHeaders(BILI_REQUEST_HEADERS)

                isYouTubeGoogleVideoHost(host) ->
                    spec.withRequestHeaders(YOUTUBE_REQUEST_HEADERS)

                else -> spec
            }
        }

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player = exo
        exo.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = syncEffect(exo)
        })
        syncEffect(exo)

        mediaSession = MediaSession.Builder(this, exo)
            .setCallback(object : MediaSession.Callback {
                /**
                 * 播放命令（来自应用内 MediaController、通知栏、系统媒体键）在执行前询问一次。
                 * 一起听听众端在房主关闭成员控制时借此拦截本地操作；其余情况保持默认放行。
                 */
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    return if (PlayerController.shouldAcceptPlayerCommand(playerCommand)) {
                        SessionResult.RESULT_SUCCESS
                    } else {
                        SessionResult.RESULT_ERROR_INVALID_STATE
                    }
                }
            })
            .build()
    }

    /** 依据当前曲目所属源的开/关设置，切换低频增强音效（媒体会话的音频会话）。 */
    private fun syncEffect(exo: ExoPlayer) {
        val sid = exo.audioSessionId
        if (sid == C.AUDIO_SESSION_ID_UNSET || sid <= 0) return
        val want = runCatching {
            val mediaId = exo.currentMediaItem?.mediaId.orEmpty()
            mediaId.isNotBlank() &&
                AppGraph.store.effectEnabled(Source.fromId(mediaId.substringBefore(":")))
        }.getOrDefault(false)
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

    /** 设置变化后立即重算音效（不必等播放器事件）。 */
    fun refreshEffect() {
        player?.let { syncEffect(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
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

    companion object {
        /** 当前服务实例（同进程），设置页切换音效时用它即时刷新。 */
        @Volatile
        var instance: PlaybackService? = null
            private set

        /** 播放服务是否已在运行（一起听进房时用它判断是否需要拉起保活）。 */
        fun isReadyForPassiveLocalPlaybackSync(): Boolean = instance != null

        /**
         * 一起听进房时拉起播放服务，让进程与媒体会话在后台存活。
         *
         * 只用普通 startService（App 在前台时可用）；不强制前台化，避免在无播放时
         * 因 startForegroundService 的 5 秒限期而崩溃。失败由调用方兜底。
         */
        fun startSyncService(context: Context, source: String, forceForeground: Boolean) {
            NPLogger.d(
                "PlaybackService",
                "startSyncService(): source=$source, forceForeground=$forceForeground"
            )
            runCatching {
                context.startService(Intent(context, PlaybackService::class.java))
            }.onFailure { error ->
                NPLogger.w(
                    "PlaybackService",
                    "startSyncService(): failed, source=$source, error=${error.message}",
                    error
                )
            }
        }

        /** 低频增强强度（毫贝）。 */
        private const val EFFECT_GAIN_mB = 600

        /** B 站音视频 CDN 域名后缀。 */
        val BILI_CDN_HOSTS = listOf(
            "bilivideo.com",
            "bilivideo.cn",
            "biliapi.net",
            "hdslb.com",
            "mountaintoys.cn",
            "akamaized.net",
        )

        val BILI_REQUEST_HEADERS = mapOf(
            "Referer" to "https://www.bilibili.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        )

        /**
         * YouTube googlevideo CDN 的请求头。
         * 直链本身已带签名参数，这里补浏览器 UA 与 Origin/Referer 即可（与移植栈的 stream 头一致）。
         */
        val YOUTUBE_REQUEST_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/",
        )
    }
}
