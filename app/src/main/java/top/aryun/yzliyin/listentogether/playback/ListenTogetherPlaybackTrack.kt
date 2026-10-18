package top.aryun.yzliyin.listentogether.playback

import top.aryun.yzliyin.listentogether.mapping.resolvedAudioId
import top.aryun.yzliyin.listentogether.mapping.resolvedChannelId
import top.aryun.yzliyin.listentogether.mapping.resolvedPlaylistContextId
import top.aryun.yzliyin.listentogether.mapping.resolvedSubAudioId
import top.aryun.yzliyin.listentogether.mapping.toSong
import top.aryun.yzliyin.listentogether.mapping.trustedListenTogetherStreamUrls
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherEvent
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomState
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherTrack
import top.aryun.yzliyin.core.model.Song

internal fun ListenTogetherRoomState.currentStableKey(): String? {
    return currentTrack()?.stableKey
}

internal fun ListenTogetherRoomState.currentTrack(): ListenTogetherTrack? {
    return if (queue.isNotEmpty()) {
        queue[currentIndex.coerceIn(0, queue.lastIndex)]
    } else {
        track
    }
}

internal fun ListenTogetherRoomState.authoritativeStreamUrlForCurrentTrack(): String? {
    return authoritativeStreamUrlsForCurrentTrack().firstOrNull()
}

internal fun ListenTogetherRoomState.authoritativeStreamUrlsForCurrentTrack(): List<String> {
    val targetTrack = currentTrack() ?: return emptyList()
    return sequenceOf(targetTrack, track)
        .filterNotNull()
        .filter { candidate -> candidate.stableKey == targetTrack.stableKey }
        .flatMap { candidate ->
            trustedListenTogetherStreamUrls(
                channelId = candidate.channelId,
                streamUrls = candidate.streamUrls,
                legacyStreamUrl = candidate.streamUrl
            ).asSequence()
        }
        .distinct()
        .toList()
}

internal fun ListenTogetherEvent.requestedStableKey(): String? {
    return requestTrackStableKey
        ?: queue?.getOrNull(currentIndex ?: -1)?.stableKey
        ?: track?.stableKey
}

internal fun resolveListenTogetherQueueIndex(
    queue: List<ListenTogetherTrack>,
    requestedIndex: Int,
    preferredStableKey: String?
): Int {
    if (queue.isEmpty()) return -1
    val indexedPosition = requestedIndex.takeIf { it in queue.indices }
    if (preferredStableKey.isNullOrBlank()) {
        return indexedPosition ?: 0
    }
    if (indexedPosition != null && queue[indexedPosition].stableKey == preferredStableKey) {
        return indexedPosition
    }
    return queue.indexOfFirst { it.stableKey == preferredStableKey }
        .takeIf { it >= 0 }
        ?: indexedPosition
        ?: 0
}

internal fun ListenTogetherRoomState.targetSongItem(): Song? {
    return currentTrack()?.toSong()
}

internal fun Song.sameTrackAs(other: Song): Boolean {
    return resolvedChannelId() == other.resolvedChannelId() &&
        resolvedAudioId() == other.resolvedAudioId() &&
        resolvedSubAudioId() == other.resolvedSubAudioId() &&
        resolvedPlaylistContextId() == other.resolvedPlaylistContextId()
}

internal fun List<Song>.hasSameTrackSequenceAs(other: List<Song>): Boolean {
    if (size != other.size) return false
    return indices.all { index -> this[index].sameTrackAs(other[index]) }
}

private data class ListenTogetherPlaybackIdentity(
    val channelId: String?,
    val audioId: String?,
    val subAudioId: String?,
    val playlistContextId: String?
)

private fun Song.listenTogetherPlaybackIdentity(): ListenTogetherPlaybackIdentity {
    return ListenTogetherPlaybackIdentity(
        channelId = resolvedChannelId(),
        audioId = resolvedAudioId(),
        subAudioId = resolvedSubAudioId(),
        playlistContextId = resolvedPlaylistContextId()
    )
}

internal fun List<Song>.hasSameTrackMultisetAs(other: List<Song>): Boolean {
    if (size != other.size) return false
    return groupingBy { it.listenTogetherPlaybackIdentity() }.eachCount() ==
        other.groupingBy { it.listenTogetherPlaybackIdentity() }.eachCount()
}

internal fun shouldApplyListenTogetherQueueUpdateWithoutReload(
    causeType: String?,
    currentQueue: List<Song>,
    currentSong: Song?,
    incomingQueue: List<Song>,
    incomingCurrentIndex: Int
): Boolean {
    if (!isListenTogetherQueueUpdateCause(causeType)) return false
    val incomingCurrentSong = incomingQueue.getOrNull(incomingCurrentIndex) ?: return false
    if (currentQueue.isEmpty() || currentSong?.sameTrackAs(incomingCurrentSong) != true) {
        return false
    }
    return causeType !in LISTEN_TOGETHER_PLAYBACK_MODE_QUEUE_UPDATE_CAUSES ||
        currentQueue.hasSameTrackMultisetAs(incomingQueue)
}

internal fun isListenTogetherQueueUpdateCause(causeType: String?): Boolean {
    return causeType in LISTEN_TOGETHER_QUEUE_UPDATE_CAUSES ||
        causeType in LISTEN_TOGETHER_PLAYBACK_MODE_QUEUE_UPDATE_CAUSES
}

private val LISTEN_TOGETHER_QUEUE_UPDATE_CAUSES = setOf(
    "SET_QUEUE",
    "REQUEST_SET_QUEUE"
)

private val LISTEN_TOGETHER_PLAYBACK_MODE_QUEUE_UPDATE_CAUSES = setOf(
    "PLAYBACK_MODE",
    "REQUEST_PLAYBACK_MODE"
)

internal fun List<Song>.indexOfTrack(track: Song?): Int {
    track ?: return -1
    return indexOfFirst { candidate -> candidate.sameTrackAs(track) }
}
