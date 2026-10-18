package top.aryun.yzliyin.listentogether.control

import top.aryun.yzliyin.util.NPLogger
import top.aryun.yzliyin.listentogether.player.ListenTogetherPlayer
import top.aryun.yzliyin.player.PlaybackCommand
import top.aryun.yzliyin.listentogether.compat.resolveListenTogetherPlaybackCommandShouldPlay
import top.aryun.yzliyin.listentogether.compat.resolveListenTogetherLinkReadyState
import top.aryun.yzliyin.listentogether.mapping.toListenTogetherTrackOrNull
import top.aryun.yzliyin.listentogether.mapping.withStreamUrls
import top.aryun.yzliyin.listentogether.playback.hasShareableListenTogetherTrackAt
import top.aryun.yzliyin.listentogether.playback.indexOfTrack
import top.aryun.yzliyin.listentogether.playback.isShareableForListenTogether
import top.aryun.yzliyin.listentogether.playback.currentTrack
import top.aryun.yzliyin.listentogether.playback.mergeCurrentTrack
import top.aryun.yzliyin.listentogether.playback.normalizedDirectStreamUrl
import top.aryun.yzliyin.listentogether.playback.sameTrackAs
import top.aryun.yzliyin.listentogether.playback.toShareableQueueSnapshot
import top.aryun.yzliyin.listentogether.playback.wrapListenTogetherSingleTrackRepeatPosition
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherEvent
import top.aryun.yzliyin.listentogether.protocol.LISTEN_TOGETHER_QUEUE_MUTATION_SCHEMA_VERSION
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherSocketEnvelope
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherTrack
import top.aryun.yzliyin.core.model.Song
import java.util.UUID

internal fun nextListenTogetherEventId(): String {
    return "evt-${System.currentTimeMillis()}-${UUID.randomUUID()}"
}

internal class ListenTogetherEventFactory(
    private val roomStateProvider: () -> ListenTogetherRoomState?,
    private val isControllerProvider: () -> Boolean,
    private val eventIdFactory: () -> String,
    private val clientInstanceIdProvider: () -> String,
    private val clientSequenceFactory: () -> Long,
    private val localPlaybackStateNameProvider: () -> String,
    private val localTransportActiveProvider: () -> Boolean
) {
    fun buildSetTrackEvent(
        queue: List<Song>,
        currentIndex: Int,
        positionMs: Long,
        shouldPlay: Boolean
    ): ListenTogetherEvent {
        val roomState = roomStateProvider()
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = currentIndex,
            roomSettings = roomState?.settings,
            includeResolvedStreamUrl = false
        )
        val queueMutationPlan = roomState?.queueMutationPlanFor(
            targetQueue = shareableQueue,
            targetCurrentIndex = resolvedCurrentIndex
        )
        val useQueueMutation = queueMutationPlan?.requiresSnapshotFallback == false
        return ListenTogetherEvent(
            type = "SET_TRACK",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = resolvedCurrentIndex,
            track = shareableQueue.getOrNull(resolvedCurrentIndex),
            queue = shareableQueue.takeUnless { useQueueMutation },
            queueMutation = queueMutationPlan?.mutation.takeIf { useQueueMutation },
            legacyQueueSnapshot = shareableQueue.takeIf { useQueueMutation },
            shouldPlay = shouldPlay
        )
    }

    fun buildSetQueueEvent(
        queue: List<Song>,
        currentIndex: Int,
        positionMs: Long,
        commandShouldPlay: Boolean? = null
    ): ListenTogetherEvent? {
        val eventType = if (isControllerProvider()) "SET_QUEUE" else "REQUEST_SET_QUEUE"
        val roomState = roomStateProvider()
        if (queue.isEmpty()) {
            val queueMutationPlan = roomState?.queueMutationPlanFor(
                targetQueue = emptyList(),
                targetCurrentIndex = -1
            )
            val useQueueMutation = queueMutationPlan?.requiresSnapshotFallback == false
            return ListenTogetherEvent(
                type = eventType,
                eventId = eventIdFactory(),
                clientTimeMs = System.currentTimeMillis(),
                clientInstanceId = clientInstanceIdProvider(),
                clientSequence = clientSequenceFactory(),
                positionMs = 0L,
                currentIndex = -1,
                queue = emptyList<ListenTogetherTrack>().takeUnless { useQueueMutation },
                queueMutation = queueMutationPlan?.mutation.takeIf { useQueueMutation },
                legacyQueueSnapshot = emptyList<ListenTogetherTrack>().takeIf { useQueueMutation },
                shouldPlay = false,
                state = "paused",
                repeatMode = ListenTogetherPlayer.repeatModeFlow.value,
                shuffleEnabled = ListenTogetherPlayer.shuffleModeFlow.value
            )
        }
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = currentIndex,
            roomSettings = roomState?.settings,
            includeResolvedStreamUrl = false
        )
        val currentTrack = shareableQueue.getOrNull(resolvedCurrentIndex) ?: run {
            NPLogger.w(
                TAG,
                "buildSetQueueEvent(): current track missing, resolvedCurrentIndex=$resolvedCurrentIndex, queueSize=${shareableQueue.size}"
            )
            return null
        }
        val shouldPlay = commandShouldPlay ?: (
            localTransportActiveProvider() || ListenTogetherPlayer.isPlayingFlow.value
        )
        val queueMutationPlan = roomState?.queueMutationPlanFor(
            targetQueue = shareableQueue,
            targetCurrentIndex = resolvedCurrentIndex
        )
        val useQueueMutation = queueMutationPlan?.requiresSnapshotFallback == false
        return ListenTogetherEvent(
            type = eventType,
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = resolvedCurrentIndex,
            track = currentTrack,
            queue = shareableQueue.takeUnless { useQueueMutation },
            queueMutation = queueMutationPlan?.mutation.takeIf { useQueueMutation },
            legacyQueueSnapshot = shareableQueue.takeIf { useQueueMutation },
            shouldPlay = shouldPlay,
            state = if (shouldPlay) "playing" else "paused",
            repeatMode = ListenTogetherPlayer.repeatModeFlow.value,
            shuffleEnabled = ListenTogetherPlayer.shuffleModeFlow.value,
            requestTrackStableKey = currentTrack.stableKey
        )
    }

    fun buildPlayEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("PLAY", positionMs)
    }

    fun buildPauseEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("PAUSE", positionMs)
    }

    fun buildSeekEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("SEEK", positionMs)
    }

    fun buildRequestPlayEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("REQUEST_PLAY", positionMs)
    }

    fun buildRequestPauseEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("REQUEST_PAUSE", positionMs)
    }

    fun buildRequestSeekEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("REQUEST_SEEK", positionMs)
    }

    fun buildPlaybackModeEvent(
        repeatMode: Int,
        shuffleEnabled: Boolean
    ): ListenTogetherEvent {
        val positionMs = playbackModePositionSnapshot(
            ListenTogetherPlayer.playbackPositionFlow.value.coerceAtLeast(0L)
        )
        return playbackSnapshotEvent(
            type = if (isControllerProvider()) "PLAYBACK_MODE" else "REQUEST_PLAYBACK_MODE",
            positionMs = positionMs,
            includeQueueMutation = true
        ).copy(
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled
        )
    }

    fun buildHeartbeatEvent(
        state: String,
        positionMs: Long,
        includeQueue: Boolean = true
    ): ListenTogetherEvent {
        val roomState = roomStateProvider()
        val queue = ListenTogetherPlayer.currentQueueFlow.value
        val currentSong = ListenTogetherPlayer.currentSongFlow.value
        val rawIndex = queue.indexOfFirst { song ->
            currentSong != null && song.sameTrackAs(currentSong)
        }
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = rawIndex.takeIf { it >= 0 } ?: 0,
            roomSettings = roomState?.settings,
            includeResolvedStreamUrl = false
        )
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
        return ListenTogetherEvent(
            type = "HEARTBEAT",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            currentIndex = resolvedCurrentIndex,
            track = shareableTrack,
            queue = shareableQueue.takeIf {
                includeQueue && shouldIncludeLegacyQueueSnapshot(roomState)
            },
            state = state,
            positionMs = positionMs.coerceAtLeast(0L)
        )
    }

    fun buildRequestLinkEvent(
        stableKey: String,
        currentIndex: Int? = null,
        track: ListenTogetherTrack? = null,
        forceRefresh: Boolean = false
    ): ListenTogetherEvent {
        return ListenTogetherEvent(
            type = "REQUEST_LINK",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            currentIndex = currentIndex,
            track = track,
            requestTrackStableKey = stableKey,
            forceRefresh = forceRefresh.takeIf { it }
        )
    }

    fun buildLinkReadyEvent(
        stableKey: String,
        positionMs: Long,
        streamUrlOverride: String? = null,
        streamUrlsOverride: List<String> = emptyList()
    ): ListenTogetherEvent? {
        val queue = ListenTogetherPlayer.currentQueueFlow.value
        val currentSong = ListenTogetherPlayer.currentSongFlow.value ?: run {
            NPLogger.w(TAG, "buildLinkReadyEvent(): currentSong missing, stableKey=$stableKey")
            return null
        }
        val currentTrack = currentSong.toListenTogetherTrackOrNull() ?: run {
            NPLogger.w(TAG, "buildLinkReadyEvent(): current song is not shareable, stableKey=$stableKey")
            return null
        }
        if (currentTrack.stableKey != stableKey) {
            NPLogger.d(
                TAG,
                "buildLinkReadyEvent(): current stableKey mismatch, expected=$stableKey, actual=${currentTrack.stableKey}"
            )
            return null
        }
        val rawIndex = queue.indexOfFirst { song -> song.sameTrackAs(currentSong) }
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = rawIndex.takeIf { it >= 0 } ?: 0,
            roomSettings = roomStateProvider()?.settings,
            includeResolvedStreamUrl = true
        )
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex) ?: run {
            NPLogger.w(
                TAG,
                "buildLinkReadyEvent(): shareableTrack missing, stableKey=$stableKey, resolvedCurrentIndex=$resolvedCurrentIndex, queueSize=${shareableQueue.size}"
            )
            return null
        }
        if (shareableTrack.stableKey != stableKey) {
            NPLogger.d(
                TAG,
                "buildLinkReadyEvent(): stableKey mismatch, expected=$stableKey, actual=${shareableTrack.stableKey}, resolvedCurrentIndex=$resolvedCurrentIndex"
            )
            return null
        }
        val resolvedStreamUrls = streamUrlsOverride.takeIf { it.isNotEmpty() }
            ?: buildList {
                streamUrlOverride?.let(::add)
                addAll(ListenTogetherPlayer.currentListenTogetherShareableStreamUrls())
            }
        val trustedTrack = shareableTrack.withStreamUrls(resolvedStreamUrls)
        if (trustedTrack.streamUrls.isEmpty()) {
            NPLogger.w(
                TAG,
                "buildLinkReadyEvent(): direct stream urls missing, stableKey=$stableKey, track=${shareableTrack.name}"
            )
            return null
        }
        NPLogger.d(
            TAG,
            "buildLinkReadyEvent(): stableKey=$stableKey, resolvedCurrentIndex=$resolvedCurrentIndex, queueSize=${shareableQueue.size}, positionMs=$positionMs"
        )
        return ListenTogetherEvent(
            type = "LINK_READY",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            currentIndex = resolvedCurrentIndex,
            track = trustedTrack,
            queue = shareableQueue.mergeCurrentTrack(
                currentIndex = resolvedCurrentIndex,
                currentTrack = trustedTrack
            ),
            state = resolveListenTogetherLinkReadyState(
                roomPlaybackState = roomStateProvider()?.playback?.state,
                localTransportActive = localTransportActiveProvider(),
                localPlaying = ListenTogetherPlayer.isPlayingFlow.value
            ),
            positionMs = positionMs.coerceAtLeast(0L),
            requestTrackStableKey = stableKey
        )
    }

    fun buildLinkUnavailableEvent(stableKey: String): ListenTogetherEvent? {
        val currentSong = ListenTogetherPlayer.currentSongFlow.value ?: return null
        val currentTrack = currentSong.toListenTogetherTrackOrNull() ?: return null
        if (currentTrack.stableKey != stableKey) return null
        val currentIndex = ListenTogetherPlayer.currentQueueFlow.value
            .indexOfFirst { song -> song.sameTrackAs(currentSong) }
            .takeIf { it >= 0 }
        return ListenTogetherEvent(
            type = "LINK_UNAVAILABLE",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            currentIndex = currentIndex,
            track = currentTrack.withStreamUrls(emptyList()),
            requestTrackStableKey = stableKey
        )
    }

    fun buildRequestSetTrackEvent(
        queue: List<Song>,
        currentIndex: Int,
        positionMs: Long,
        shouldPlay: Boolean
    ): ListenTogetherEvent {
        return buildSetTrackEvent(
            queue = queue,
            currentIndex = currentIndex,
            positionMs = positionMs,
            shouldPlay = shouldPlay
        ).copy(type = "REQUEST_SET_TRACK")
    }

    fun buildTrackFinishedEvent(
        command: PlaybackCommand,
        queue: List<Song>,
        currentSong: Song?,
        positionMs: Long
    ): ListenTogetherEvent? {
        if (queue.isEmpty() || currentSong == null) return null
        val finishedTrack = currentSong.toListenTogetherTrackOrNull() ?: return null
        val proposedNextIndex = command.currentIndex?.coerceIn(0, queue.lastIndex)
            ?: queue.indexOfTrack(currentSong).takeIf { it >= 0 }
            ?: 0
        if (!queue.hasShareableListenTogetherTrackAt(proposedNextIndex)) return null
        val isController = isControllerProvider()
        val roomState = roomStateProvider()
        val (shareableQueue, resolvedNextIndex) = queue.toShareableQueueSnapshot(
            currentIndex = proposedNextIndex,
            roomSettings = roomState?.settings,
            includeResolvedStreamUrl = false
        )
        val shouldAdvance = command.shouldPlay == true
        val queueMutationPlan = roomState?.takeIf { isController }?.queueMutationPlanFor(
            targetQueue = shareableQueue,
            targetCurrentIndex = resolvedNextIndex
        )
        val useQueueMutation = queueMutationPlan?.requiresSnapshotFallback == false
        return ListenTogetherEvent(
            type = "TRACK_FINISHED",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = if (isController) resolvedNextIndex else null,
            nextIndex = if (isController) resolvedNextIndex else null,
            track = if (isController && shouldAdvance) shareableQueue.getOrNull(resolvedNextIndex) else null,
            queue = if (isController && !useQueueMutation) shareableQueue else null,
            queueMutation = queueMutationPlan?.mutation.takeIf { useQueueMutation },
            legacyQueueSnapshot = shareableQueue.takeIf { useQueueMutation },
            shouldPlay = if (isController) shouldAdvance else null,
            finishedTrackStableKey = finishedTrack.stableKey
        )
    }

    fun buildControllerCommitEventFromForwardedRequest(
        message: ListenTogetherSocketEnvelope
    ): ListenTogetherEvent? {
        val requestType = message.causedBy?.type ?: return null
        val commitType = requestType.removePrefix("REQUEST_")
        if (commitType == requestType) return null
        val rawPositionMs = message.positionMs ?: message.expectedPositionMs ?: 0L
        val positionMs = if (commitType == "PLAYBACK_MODE") {
            playbackModePositionSnapshot(rawPositionMs)
        } else {
            rawPositionMs
        }
        return ListenTogetherEvent(
            type = commitType,
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = message.currentIndex,
            track = message.track,
            queue = message.queue,
            shouldPlay = message.shouldPlay,
            state = message.stateName,
            repeatMode = message.repeatMode,
            shuffleEnabled = message.shuffleEnabled,
            queueMutation = message.queueMutation,
            requestTrackStableKey = message.requestTrackStableKey
        )
    }

    private fun playbackModePositionSnapshot(positionMs: Long): Long {
        val roomState = roomStateProvider()
        val currentTrack = roomState?.currentTrack()
        val durationMs = currentTrack?.durationMs
            ?: ListenTogetherPlayer.currentSongFlow.value?.durationMs
            ?: 0L
        val previousRepeatMode = roomState?.playback?.repeatMode
            ?: ListenTogetherPlayer.repeatModeFlow.value
        return wrapListenTogetherSingleTrackRepeatPosition(
            positionMs = positionMs,
            repeatMode = previousRepeatMode,
            durationMs = durationMs
        )
    }

    fun buildEventForPlaybackCommand(
        command: PlaybackCommand
    ): ListenTogetherEvent? {
        val commandSnapshot = resolveListenTogetherPlaybackCommandSnapshot(
            commandQueue = command.queue,
            commandPositionMs = command.positionMs,
            currentQueue = ListenTogetherPlayer.currentQueueFlow.value,
            currentPositionMs = ListenTogetherPlayer.playbackPositionFlow.value
        )
        val queue = if (command.type == "SET_QUEUE" && command.queue != null) {
            command.queue
        } else {
            commandSnapshot.queue
        }
        val currentSong = ListenTogetherPlayer.currentSongFlow.value
        val currentIndex = command.currentIndex
            ?: queue.indexOfFirst { song ->
                currentSong != null && song.sameTrackAs(currentSong)
            }.takeIf { it >= 0 }
            ?: 0
        val positionMs = commandSnapshot.positionMs
        val shouldPlay = resolveListenTogetherPlaybackCommandShouldPlay(
            commandType = command.type,
            commandShouldPlay = command.shouldPlay,
            localTransportActive = localTransportActiveProvider(),
            localPlaying = ListenTogetherPlayer.isPlayingFlow.value
        )

        return when (command.type) {
            "PLAY_PLAYLIST",
            "PLAY_FROM_QUEUE",
            "NEXT",
            "PREVIOUS" -> {
                if (!queue.hasShareableListenTogetherTrackAt(currentIndex)) return null
                if (isControllerProvider()) {
                    buildSetTrackEvent(
                        queue = queue,
                        currentIndex = currentIndex,
                        positionMs = positionMs,
                        shouldPlay = shouldPlay
                    )
                } else {
                    buildRequestSetTrackEvent(
                        queue = queue,
                        currentIndex = currentIndex,
                        positionMs = positionMs,
                        shouldPlay = shouldPlay
                    )
                }
            }

            "PLAY" -> {
                if (!currentSong.isShareableForListenTogether()) return null
                if (isControllerProvider()) buildPlayEvent(positionMs) else buildRequestPlayEvent(positionMs)
            }

            "PAUSE" -> {
                if (!currentSong.isShareableForListenTogether()) return null
                if (isControllerProvider()) buildPauseEvent(positionMs) else buildRequestPauseEvent(positionMs)
            }

            "PLAYBACK_MODE" -> buildPlaybackModeEvent(
                repeatMode = command.repeatMode ?: ListenTogetherPlayer.repeatModeFlow.value,
                shuffleEnabled = command.shuffleEnabled ?: ListenTogetherPlayer.shuffleModeFlow.value
            )
            "SET_QUEUE" -> buildSetQueueEvent(
                queue = queue,
                currentIndex = currentIndex,
                positionMs = positionMs,
                commandShouldPlay = command.shouldPlay
            )
            "TRACK_FINISHED" -> buildTrackFinishedEvent(command, queue, currentSong, positionMs)
            "SEEK" -> {
                if (!currentSong.isShareableForListenTogether()) return null
                if (!queue.hasShareableListenTogetherTrackAt(currentIndex)) return null
                val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
                    currentIndex = currentIndex,
                    roomSettings = roomStateProvider()?.settings,
                    includeResolvedStreamUrl = false
                )
                val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
                val event = if (isControllerProvider()) buildSeekEvent(positionMs) else buildRequestSeekEvent(positionMs)
                event.copy(
                    currentIndex = resolvedCurrentIndex,
                    track = shareableTrack
                )
            }
            else -> null
        }
    }

    private fun playbackSnapshotEvent(
        type: String,
        positionMs: Long,
        includeQueueMutation: Boolean = false
    ): ListenTogetherEvent {
        val roomState = roomStateProvider()
        val queue = ListenTogetherPlayer.currentQueueFlow.value
        val currentSong = ListenTogetherPlayer.currentSongFlow.value
        val rawIndex = queue.indexOfFirst { song ->
            currentSong != null && song.sameTrackAs(currentSong)
        }
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = rawIndex.takeIf { it >= 0 } ?: 0,
            roomSettings = roomState?.settings,
            includeResolvedStreamUrl = false
        )
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
        val queueMutationPlan = roomState?.takeIf { includeQueueMutation }?.queueMutationPlanFor(
            targetQueue = shareableQueue,
            targetCurrentIndex = resolvedCurrentIndex
        )
        val useQueueMutation = queueMutationPlan?.requiresSnapshotFallback == false
        val resolvedState = when (type.removePrefix("REQUEST_")) {
            "PLAY" -> "playing"
            "PAUSE" -> "paused"
            else -> localPlaybackStateNameProvider()
        }
        return ListenTogetherEvent(
            type = type,
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = resolvedCurrentIndex,
            track = shareableTrack,
            queue = shareableQueue.takeIf {
                !useQueueMutation && (
                    queueMutationPlan?.requiresSnapshotFallback == true ||
                        shouldIncludeLegacyQueueSnapshot(roomState)
                    )
            },
            queueMutation = queueMutationPlan?.mutation.takeIf { useQueueMutation },
            legacyQueueSnapshot = shareableQueue.takeIf { useQueueMutation },
            shouldPlay = resolvedState == "playing",
            state = resolvedState,
            repeatMode = ListenTogetherPlayer.repeatModeFlow.value,
            shuffleEnabled = ListenTogetherPlayer.shuffleModeFlow.value
        )
    }

    private fun ListenTogetherRoomState.queueMutationPlanFor(
        targetQueue: List<ListenTogetherTrack>,
        targetCurrentIndex: Int
    ): ListenTogetherQueueMutationPlan? {
        if (schemaVersion < LISTEN_TOGETHER_QUEUE_MUTATION_SCHEMA_VERSION) return null
        return buildListenTogetherQueueMutationPlan(
            baseState = this,
            targetQueue = targetQueue,
            targetCurrentIndex = targetCurrentIndex
        )
    }

    private fun shouldIncludeLegacyQueueSnapshot(roomState: ListenTogetherRoomState?): Boolean {
        return (roomState?.schemaVersion ?: 1) < LISTEN_TOGETHER_QUEUE_MUTATION_SCHEMA_VERSION
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
    }
}
