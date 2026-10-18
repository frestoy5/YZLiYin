package top.aryun.yzliyin.listentogether.playback

import androidx.media3.common.Player
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.listentogether.player.ListenTogetherPlayer
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState

internal class ListenTogetherListenerStallRecovery(
    private val stallTimeoutMs: Long,
    private val recoveryCooldownMs: Long
) {
    private var bufferingStartedAtElapsedMs: Long = 0L
    private var bufferingTrackStableKey: String? = null
    private var lastRecoveryAtElapsedMs: Long = 0L

    fun shouldRecover(
        state: ListenTogetherRoomState,
        nowElapsedMs: Long
    ): Boolean {
        if (state.playback.state != "playing") {
            resetBufferingState()
            return false
        }
        if (ListenTogetherPlayer.isPlayingFlow.value) {
            resetBufferingState()
            return false
        }
        val targetSong = state.targetSongItem() ?: run {
            resetBufferingState()
            return false
        }
        val currentSong = ListenTogetherPlayer.currentSongFlow.value
        if (currentSong?.sameTrackAs(targetSong) != true) {
            resetBufferingState()
            return false
        }
        val playerState = ListenTogetherPlayer.playerPlaybackStateFlow.value
        val looksStalled =
            ListenTogetherPlayer.playWhenReadyFlow.value ||
                ListenTogetherPlayer.isPendingMediaLoadActive() ||
                playerState == Player.STATE_BUFFERING ||
                playerState == Player.STATE_IDLE
        if (!looksStalled) {
            resetBufferingState()
            return false
        }
        val stableKey = state.currentStableKey() ?: run {
            resetBufferingState()
            return false
        }
        if (bufferingTrackStableKey != stableKey) {
            bufferingTrackStableKey = stableKey
            bufferingStartedAtElapsedMs = nowElapsedMs
            return false
        }
        if (nowElapsedMs - bufferingStartedAtElapsedMs < stallTimeoutMs) {
            return false
        }
        if (nowElapsedMs - lastRecoveryAtElapsedMs < recoveryCooldownMs) {
            return false
        }
        lastRecoveryAtElapsedMs = nowElapsedMs
        bufferingStartedAtElapsedMs = nowElapsedMs
        NPLogger.w(
            TAG,
            "shouldRecover(): stableKey=$stableKey, playerState=$playerState, playWhenReady=${ListenTogetherPlayer.playWhenReadyFlow.value}"
        )
        return true
    }

    fun reset() {
        lastRecoveryAtElapsedMs = 0L
        resetBufferingState()
    }

    private fun resetBufferingState() {
        bufferingStartedAtElapsedMs = 0L
        bufferingTrackStableKey = null
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
    }
}
