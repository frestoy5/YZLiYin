package top.aryun.yzliyin.listentogether.playback

import android.os.SystemClock
import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.listentogether.player.ListenTogetherPlayer
import top.aryun.yzliyin.player.PlaybackCommandSource
import top.aryun.yzliyin.listentogether.mapping.toSong
import top.aryun.yzliyin.listentogether.playback.sync.ListenTogetherPlayerSyncContext
import top.aryun.yzliyin.listentogether.playback.sync.ListenTogetherPlayerSyncPlan
import top.aryun.yzliyin.listentogether.playback.sync.resolveListenTogetherPlayerSyncPlan
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState
import top.aryun.yzliyin.listentogether.session.normalized
import top.aryun.yzliyin.core.model.Song

internal data class ListenTogetherPlayerStateApplierConfig(
    val tag: String,
    val trackSwitchForceSyncMs: Long,
    val heartbeatDriftForceSyncMs: Long,
    val playingDriftForceSyncMs: Long,
    val pausedDriftForceSyncMs: Long,
    val softSyncMinDriftMs: Long,
    val softSyncFastDriftMs: Long,
    val trackSwitchGracePeriodMs: Long,
    val zeroPositionRollbackGuardMs: Long
)

internal class ListenTogetherPlayerStateApplier(
    private val config: ListenTogetherPlayerStateApplierConfig,
    private val roomStateProvider: () -> ListenTogetherRoomState?,
    private val isControllerProvider: () -> Boolean,
    private val serverClockOffsetProvider: () -> Long
) {
    private var lastTrackSwitchAtElapsedMs: Long = 0L
    private var pendingAuthoritativeStreamStableKey: String? = null
    private var pendingAuthoritativeStreamUrl: String? = null
    private var lastUnavailableAuthoritativeStreamReloadStableKey: String? = null

    fun apply(
        state: ListenTogetherRoomState,
        causeType: String?,
        expectedPositionMs: Long?
    ): Boolean {
        val latestRoomVersion = roomStateProvider()?.version ?: state.version
        if (state.version < latestRoomVersion) {
            NPLogger.d(
                config.tag,
                "applyRoomStateToPlayer(): skip stale state, roomId=${state.roomId}, version=${state.version}, latest=$latestRoomVersion, causeType=$causeType"
            )
            return false
        }
        if (ListenTogetherPlayer.shouldHoldListenTogetherPlaybackForSafetyPause(causeType)) {
            NPLogger.d(
                config.tag,
                "applyRoomStateToPlayer(): hold listener safety pause, roomId=${state.roomId}, version=${state.version}, causeType=$causeType"
            )
            return false
        }
        val queue = state.toSongQueue()
        if (queue.isEmpty()) {
            if (isListenTogetherQueueUpdateCause(causeType)) {
                ListenTogetherPlayer.applyRemoteQueueUpdate(emptyList(), -1)
                return true
            }
            NPLogger.w(
                config.tag,
                "applyRoomStateToPlayer(): skip empty queue, roomId=${state.roomId}, version=${state.version}, causeType=$causeType"
            )
            return false
        }
        NPLogger.d(
            config.tag,
            "applyRoomStateToPlayer(): roomId=${state.roomId}, version=${state.version}, queueSize=${queue.size}, currentIndex=${state.currentIndex}, playback=${state.playback.state}"
        )

        val targetIndex = state.currentIndex.coerceIn(0, queue.lastIndex)
        val targetSong = queue[targetIndex]
        val currentQueue = ListenTogetherPlayer.currentQueueFlow.value
        val currentSong = ListenTogetherPlayer.currentSongFlow.value
        val queueUpdatedWithoutReload = shouldApplyListenTogetherQueueUpdateWithoutReload(
            causeType = causeType,
            currentQueue = currentQueue,
            currentSong = currentSong,
            incomingQueue = queue,
            incomingCurrentIndex = targetIndex
        )
        if (queueUpdatedWithoutReload) {
            ListenTogetherPlayer.applyRemoteQueueUpdate(queue, targetIndex)
        }
        val synchronizedQueue = if (queueUpdatedWithoutReload) {
            ListenTogetherPlayer.currentQueueFlow.value
        } else {
            currentQueue
        }
        val localCurrentIndex = if (queueUpdatedWithoutReload) {
            targetIndex
        } else {
            synchronizedQueue.indexOfTrack(currentSong)
        }
        val targetStableKey = state.currentStableKey()
        val authoritativeStreamUrls = state.authoritativeStreamUrlsForCurrentTrack()
        val authoritativeStreamUrl = authoritativeStreamUrls.firstOrNull()
        val localResolvedStreamUrl = ListenTogetherPlayer.currentMediaUrlFlow.value
        val localPlaybackResolutionPending =
            ListenTogetherPlayer.isListenTogetherLocalResolutionPendingFor(targetSong)
        if (pendingAuthoritativeStreamStableKey != targetStableKey) {
            pendingAuthoritativeStreamStableKey = null
            pendingAuthoritativeStreamUrl = null
        }
        if (lastUnavailableAuthoritativeStreamReloadStableKey != targetStableKey) {
            lastUnavailableAuthoritativeStreamReloadStableKey = null
        }
        val localUsesAuthoritativeStream = hasListenTogetherAuthoritativeStreamUrl(
            authoritativeStreamUrls = authoritativeStreamUrls,
            localResolvedStreamUrl = localResolvedStreamUrl
        )
        if (localUsesAuthoritativeStream) {
            pendingAuthoritativeStreamStableKey = null
            pendingAuthoritativeStreamUrl = null
        }
        val needsAuthoritativeStreamReload = shouldReloadForAuthoritativeStreamUrl(
            targetSong = targetSong,
            currentSong = currentSong,
            remoteStreamUrl = authoritativeStreamUrl,
            localResolvedStreamUrl = localResolvedStreamUrl,
            localUsesAuthoritativeStream = localUsesAuthoritativeStream,
            localPlaybackResolutionPending = localPlaybackResolutionPending,
            pendingAuthoritativeStreamUrl = pendingAuthoritativeStreamUrl
        )
        val controllerLinkConfirmedUnavailable =
            causeType == "LINK_UNAVAILABLE" &&
                ListenTogetherPlayer.isListenTogetherAuthoritativeStreamConfirmedUnavailable(targetSong)
        val shouldClearUnavailableAuthoritativeStream =
            controllerLinkConfirmedUnavailable && !isControllerProvider()
        if (shouldClearUnavailableAuthoritativeStream) {
            pendingAuthoritativeStreamStableKey = null
            pendingAuthoritativeStreamUrl = null
        }
        val forcePlaybackStallReload =
            causeType == "WATCHDOG_STALL" &&
                state.playback.state == "playing" &&
                currentSong?.sameTrackAs(targetSong) == true
        val shouldReloadForUnavailableAuthoritativeStream =
            shouldClearUnavailableAuthoritativeStream &&
                shouldReloadForListenTogetherLinkUnavailable(
                    isController = isControllerProvider(),
                    localPlaybackRequiresAuthoritativeStream =
                        ListenTogetherPlayer.currentPlaybackRequiresListenTogetherAuthoritativeStream(),
                    controllerLinkConfirmedUnavailable = controllerLinkConfirmedUnavailable,
                    alreadyReloadedForStableKey =
                        lastUnavailableAuthoritativeStreamReloadStableKey == targetStableKey
                )
        if (shouldReloadForUnavailableAuthoritativeStream) {
            lastUnavailableAuthoritativeStreamReloadStableKey = targetStableKey
        }
        if (!ListenTogetherPlayer.currentPlaybackRequiresListenTogetherAuthoritativeStream()) {
            lastUnavailableAuthoritativeStreamReloadStableKey = null
        }
        val playbackContextChanged =
            !synchronizedQueue.hasSameTrackSequenceAs(queue) ||
                needsAuthoritativeStreamReload ||
                shouldReloadForUnavailableAuthoritativeStream ||
                forcePlaybackStallReload
        val targetIndexChanged = localCurrentIndex != targetIndex
        val trackSwitchGracePeriodActive = isTrackSwitchGracePeriodActive()

        if (playbackContextChanged || targetIndexChanged) {
            if (needsAuthoritativeStreamReload) {
                pendingAuthoritativeStreamStableKey = targetStableKey
                pendingAuthoritativeStreamUrl = authoritativeStreamUrl
            }
            lastTrackSwitchAtElapsedMs = SystemClock.elapsedRealtime()
            ListenTogetherPlayer.resetListenTogetherSyncPlaybackRate()
            ListenTogetherPlayer.playPlaylist(queue, targetIndex, commandSource = PlaybackCommandSource.REMOTE_SYNC)
        }
        ListenTogetherPlayer.applyListenTogetherPlaybackMode(
            repeatMode = state.playback.repeatMode,
            shuffleEnabled = state.playback.shuffleEnabled
        )

        val rawExpectedPositionMs = expectedPositionMs
            ?: state.playback.expectedPositionMs(
                serverClockOffsetMs = serverClockOffsetProvider(),
                durationMs = targetSong.durationMs
            )
        val repeatAwareExpectedPositionMs = wrapListenTogetherSingleTrackRepeatPosition(
            positionMs = rawExpectedPositionMs,
            repeatMode = state.playback.repeatMode,
            durationMs = targetSong.durationMs
        )
        // 用目标曲目时长钳上限, 防止异常大的 position 把播放器推到曲末造成误切/卡顿
        val resolvedExpectedPositionMs = clampListenTogetherPositionMs(
            positionMs = repeatAwareExpectedPositionMs,
            durationMs = targetSong.durationMs
        )
        val localPositionMs = ListenTogetherPlayer.playbackPositionFlow.value.coerceAtLeast(0L)
        val desiredPlaying = state.playback.state == "playing"
        val localPlaying = ListenTogetherPlayer.isPlayingFlow.value
        val localPlaybackAlreadyStarting = ListenTogetherPlayer.playWhenReadyFlow.value
        val awaitingAuthoritativeStream = shouldWaitForListenTogetherAuthoritativeStreamPlayback(
            playerWaitingForAuthoritativeStream =
                !localPlaybackResolutionPending &&
                    ListenTogetherPlayer.shouldWaitForListenTogetherAuthoritativeStream(targetSong),
            localTrackMatchesTarget = currentSong?.sameTrackAs(targetSong) == true,
            localTrackStreamUrl = ListenTogetherPlayer.currentMediaUrlFlow.value,
            localResolvedStreamUrl = ListenTogetherPlayer.currentMediaUrlFlow.value
        )
        val ignoreUnexpectedZeroPositionRollback = shouldIgnoreUnexpectedZeroPositionRollback(
            causeType = causeType,
            desiredPlaying = desiredPlaying,
            expectedPositionMs = resolvedExpectedPositionMs,
            localPositionMs = localPositionMs,
            playbackContextChanged = playbackContextChanged,
            targetIndexChanged = targetIndexChanged
        )
        val syncPlan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = playbackContextChanged,
                targetIndexChanged = targetIndexChanged,
                desiredPlaying = desiredPlaying,
                localPlaying = localPlaying,
                localPlaybackAlreadyStarting = localPlaybackAlreadyStarting,
                awaitingAuthoritativeStream = awaitingAuthoritativeStream,
                expectedPositionMs = resolvedExpectedPositionMs,
                localPositionMs = localPositionMs,
                ignoreUnexpectedZeroPositionRollback = ignoreUnexpectedZeroPositionRollback,
                trackSwitchGracePeriodActive = trackSwitchGracePeriodActive,
                causeType = causeType,
                trackSwitchForceSyncMs = config.trackSwitchForceSyncMs,
                heartbeatDriftForceSyncMs = config.heartbeatDriftForceSyncMs,
                playingDriftForceSyncMs = config.playingDriftForceSyncMs,
                pausedDriftForceSyncMs = config.pausedDriftForceSyncMs,
                forcePositionSync = causeType == LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE
            )
        )
        NPLogger.d(
            config.tag,
            "applyRoomStateToPlayer(): causeType=$causeType, desiredPlaying=$desiredPlaying, localPlaying=$localPlaying, localPlaybackAlreadyStarting=$localPlaybackAlreadyStarting, awaitingAuthoritativeStream=$awaitingAuthoritativeStream, localCurrentIndex=$localCurrentIndex, targetIndex=$targetIndex, playbackContextChanged=$playbackContextChanged, targetIndexChanged=$targetIndexChanged, trackSwitchGracePeriodActive=$trackSwitchGracePeriodActive, shouldReloadPlaylist=${syncPlan.shouldReloadPlaylist}, effectiveExpectedPositionMs=${syncPlan.effectiveExpectedPositionMs}, driftMs=${syncPlan.driftMs}, signedDriftMs=${syncPlan.signedDriftMs}, shouldSeek=${syncPlan.shouldSeek}, shouldIssuePlay=${syncPlan.shouldIssuePlay}, shouldIssuePause=${syncPlan.shouldIssuePause}, hasAuthoritativeStream=${authoritativeStreamUrl != null}, needsAuthoritativeStreamReload=$needsAuthoritativeStreamReload, controllerLinkConfirmedUnavailable=$controllerLinkConfirmedUnavailable, shouldClearUnavailableAuthoritativeStream=$shouldClearUnavailableAuthoritativeStream, shouldReloadForUnavailableAuthoritativeStream=$shouldReloadForUnavailableAuthoritativeStream, forcePlaybackStallReload=$forcePlaybackStallReload, ignoreUnexpectedZeroPositionRollback=$ignoreUnexpectedZeroPositionRollback, shouldForcePauseAfterRemoteLoad=${syncPlan.shouldForcePauseAfterRemoteLoad}"
        )
        applySyncPlan(syncPlan)
        return true
    }

    private fun isTrackSwitchGracePeriodActive(): Boolean {
        val switchedAt = lastTrackSwitchAtElapsedMs
        if (switchedAt <= 0L) return false
        return SystemClock.elapsedRealtime() - switchedAt < config.trackSwitchGracePeriodMs
    }

    private fun ListenTogetherRoomState.toSongQueue(): List<Song> {
        return when {
            queue.isNotEmpty() -> queue
                .mergeCurrentTrack(currentIndex, track)
                .map { it.toSong() }
            track != null -> listOf(track.toSong())
            else -> emptyList()
        }
    }

    private fun applySyncPlan(syncPlan: ListenTogetherPlayerSyncPlan) {
        if (syncPlan.desiredPlaying) {
            applyPlayingSyncPlan(syncPlan)
            return
        }
        ListenTogetherPlayer.resetListenTogetherSyncPlaybackRate()
        if (syncPlan.shouldSeek) {
            ListenTogetherPlayer.seekTo(
                syncPlan.effectiveExpectedPositionMs,
                commandSource = PlaybackCommandSource.REMOTE_SYNC
            )
        }
        if (syncPlan.shouldIssuePause) {
            ListenTogetherPlayer.pauseImpl(
                forcePersist = true,
                commandSource = PlaybackCommandSource.REMOTE_SYNC,
                allowFadeOut = false,
                debugReason = "listen_together_remote_pause"
            )
        }
    }

    private fun applyPlayingSyncPlan(syncPlan: ListenTogetherPlayerSyncPlan) {
        if (syncPlan.shouldSeek) {
            ListenTogetherPlayer.resetListenTogetherSyncPlaybackRate()
            ListenTogetherPlayer.seekTo(
                syncPlan.effectiveExpectedPositionMs,
                commandSource = PlaybackCommandSource.REMOTE_SYNC
            )
        } else {
            applySoftDriftCorrection(
                driftMs = syncPlan.driftMs,
                signedDriftMs = syncPlan.signedDriftMs,
                allowSoftSync = true
            )
        }
        if (syncPlan.shouldIssuePlay) {
            ListenTogetherPlayer.resetListenTogetherSyncPlaybackRate()
            ListenTogetherPlayer.play(commandSource = PlaybackCommandSource.REMOTE_SYNC)
        }
    }

    private fun applySoftDriftCorrection(
        driftMs: Long,
        signedDriftMs: Long,
        allowSoftSync: Boolean
    ) {
        val rate = resolveListenTogetherSoftSyncPlaybackRate(
            driftMs = driftMs,
            signedDriftMs = signedDriftMs,
            allowSoftSync = allowSoftSync,
            isController = isControllerProvider(),
            softSyncMinDriftMs = config.softSyncMinDriftMs,
            softSyncFastDriftMs = config.softSyncFastDriftMs,
            playingDriftForceSyncMs = config.playingDriftForceSyncMs
        )
        if (rate == null) {
            NPLogger.d(
                config.tag,
                "applySoftDriftCorrection(): reset sync rate, allowSoftSync=$allowSoftSync, isController=${isControllerProvider()}, driftMs=$driftMs, signedDriftMs=$signedDriftMs"
            )
            ListenTogetherPlayer.resetListenTogetherSyncPlaybackRate()
            return
        }
        NPLogger.d(
            config.tag,
            "applySoftDriftCorrection(): driftMs=$driftMs, signedDriftMs=$signedDriftMs, applyRate=$rate"
        )
        ListenTogetherPlayer.setListenTogetherSyncPlaybackRate(rate)
    }

    private fun shouldIgnoreUnexpectedZeroPositionRollback(
        causeType: String?,
        desiredPlaying: Boolean,
        expectedPositionMs: Long,
        localPositionMs: Long,
        playbackContextChanged: Boolean,
        targetIndexChanged: Boolean
    ): Boolean {
        val shouldIgnore = shouldIgnoreListenTogetherUnexpectedZeroPositionRollback(
            causeType = causeType,
            desiredPlaying = desiredPlaying,
            expectedPositionMs = expectedPositionMs,
            localPositionMs = localPositionMs,
            playbackContextChanged = playbackContextChanged,
            targetIndexChanged = targetIndexChanged,
            zeroPositionRollbackGuardMs = config.zeroPositionRollbackGuardMs
        )
        if (!shouldIgnore) return false
        NPLogger.w(
            config.tag,
            "shouldIgnoreUnexpectedZeroPositionRollback(): ignore suspicious rollback, causeType=$causeType, expectedPositionMs=$expectedPositionMs, localPositionMs=$localPositionMs"
        )
        return true
    }

    private fun shouldReloadForAuthoritativeStreamUrl(
        targetSong: Song,
        currentSong: Song?,
        remoteStreamUrl: String?,
        localResolvedStreamUrl: String?,
        localUsesAuthoritativeStream: Boolean,
        localPlaybackResolutionPending: Boolean,
        pendingAuthoritativeStreamUrl: String?
    ): Boolean {
        if (isControllerProvider()) return false
        if (!roomStateProvider()?.settings.normalized().shareAudioLinks) return false
        if (currentSong?.sameTrackAs(targetSong) != true) return false
        if (localUsesAuthoritativeStream) return false
        return shouldReloadListenTogetherAuthoritativeStream(
            remoteStreamUrl = remoteStreamUrl,
            localResolvedStreamUrl = localResolvedStreamUrl,
            localPlaybackRequiresAuthoritativeStream =
                ListenTogetherPlayer.currentPlaybackRequiresListenTogetherAuthoritativeStream(),
            localPlaybackResolutionPending = localPlaybackResolutionPending,
            pendingAuthoritativeStreamUrl = pendingAuthoritativeStreamUrl
        )
    }
}
