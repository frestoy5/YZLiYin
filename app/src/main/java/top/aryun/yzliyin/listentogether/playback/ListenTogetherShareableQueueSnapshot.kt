package top.aryun.yzliyin.listentogether.playback

import top.aryun.yzliyin.listentogether.player.ListenTogetherPlayer
import top.aryun.yzliyin.listentogether.mapping.toListenTogetherTrackOrNull
import top.aryun.yzliyin.listentogether.mapping.withStreamUrls
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherRoomSettings
import top.aryun.yzliyin.listentogether.protocol.ListenTogetherTrack
import top.aryun.yzliyin.listentogether.session.normalized
import top.aryun.yzliyin.core.model.Song

internal fun List<Song>.toShareableQueueSnapshot(
    currentIndex: Int,
    roomSettings: ListenTogetherRoomSettings? = null,
    includeResolvedStreamUrl: Boolean = true,
    resolvedCurrentStreamUrls: List<String>? = null
): Pair<List<ListenTogetherTrack>, Int> {
    if (isEmpty()) return emptyList<ListenTogetherTrack>() to 0

    val targetSong = getOrNull(currentIndex.coerceIn(0, lastIndex))
    val targetStableKey = targetSong?.toListenTogetherTrackOrNull()?.stableKey
    val canShareResolvedStreamUrls = includeResolvedStreamUrl &&
        roomSettings.normalized().shareAudioLinks
    val currentStreamUrls = if (canShareResolvedStreamUrls) {
        resolvedCurrentStreamUrls ?: ListenTogetherPlayer.currentListenTogetherShareableStreamUrls()
    } else {
        emptyList()
    }
    val shareableQueue = mapNotNull { song ->
        song.toListenTogetherTrackOrNull()?.let { track ->
            val trackWithoutRawStreamUrls = track.withStreamUrls(emptyList())
            if (canShareResolvedStreamUrls && track.stableKey == targetStableKey) {
                trackWithoutRawStreamUrls.withStreamUrls(currentStreamUrls)
            } else {
                trackWithoutRawStreamUrls
            }
        }
    }.boundedAroundStableKey(targetStableKey)
    if (shareableQueue.isEmpty()) return shareableQueue to 0

    val resolvedCurrentIndex = targetStableKey?.let { stableKey ->
        shareableQueue.indexOfFirst { it.stableKey == stableKey }.takeIf { it >= 0 }
    } ?: 0

    return shareableQueue to resolvedCurrentIndex
}

internal fun List<Song>.toShareableShuffleRestoreQueueSnapshot(
    activeQueue: List<ListenTogetherTrack>
): List<ListenTogetherTrack> {
    if (isEmpty() || activeQueue.isEmpty()) return emptyList()
    val remainingCounts = activeQueue.groupingBy { it.stableKey }.eachCount().toMutableMap()
    val restoreQueue = mutableListOf<ListenTogetherTrack>()
    for (song in this) {
        val track = song.toListenTogetherTrackOrNull()?.withStreamUrls(emptyList()) ?: continue
        val remaining = remainingCounts[track.stableKey] ?: continue
        if (remaining == 1) {
            remainingCounts.remove(track.stableKey)
        } else {
            remainingCounts[track.stableKey] = remaining - 1
        }
        restoreQueue += track
    }
    return restoreQueue.takeIf { it.size == activeQueue.size }.orEmpty()
}

internal fun List<ListenTogetherTrack>.mergeCurrentTrack(
    currentIndex: Int,
    currentTrack: ListenTogetherTrack?
): List<ListenTogetherTrack> {
    val replacement = currentTrack ?: return this
    if (currentIndex !in indices) return this
    if (this[currentIndex].stableKey != replacement.stableKey) return this
    if (this[currentIndex] == replacement) return this
    return toMutableList().also { it[currentIndex] = replacement }
}
