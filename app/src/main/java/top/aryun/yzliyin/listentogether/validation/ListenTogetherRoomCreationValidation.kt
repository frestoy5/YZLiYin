package top.aryun.yzliyin.listentogether.validation

import top.aryun.yzliyin.R
import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.listentogether.LtMessages
import top.aryun.yzliyin.listentogether.mapping.toListenTogetherTrackOrNull

internal fun validateListenTogetherRoomCreation(
    queue: List<Song>,
    currentIndex: Int,
    currentSong: Song?
): ListenTogetherValidationError? {
    val currentSongValue = currentSong
        ?: return ListenTogetherValidationError(
            messageFormat = LtMessages.listen_together_error_current_track_missing,
            messageRes = R.string.ltm_validate_current_track_missing
        )
    val currentTrack = currentSongValue.toListenTogetherTrackOrNull()
        ?: return ListenTogetherValidationError(
            messageFormat = LtMessages.listen_together_error_current_track_not_shareable,
            messageRes = R.string.ltm_validate_current_track_not_shareable
        )
    val queueSong = queue.getOrNull(currentIndex)
        ?: return ListenTogetherValidationError(
            messageFormat = LtMessages.listen_together_error_current_track_unavailable,
            messageRes = R.string.ltm_validate_current_track_unavailable
        )
    val queueTrack = queueSong.toListenTogetherTrackOrNull()
        ?: return ListenTogetherValidationError(
            messageFormat = LtMessages.listen_together_error_current_track_not_shareable,
            messageRes = R.string.ltm_validate_current_track_not_shareable
        )
    return if (queueTrack.stableKey == currentTrack.stableKey) {
        null
    } else {
        ListenTogetherValidationError(
            messageFormat = LtMessages.listen_together_error_current_track_unavailable,
            messageRes = R.string.ltm_validate_current_track_unavailable
        )
    }
}

internal fun requireValidListenTogetherRoomCreation(
    queue: List<Song>,
    currentIndex: Int,
    currentSong: Song?
) {
    validateListenTogetherRoomCreation(queue, currentIndex, currentSong)
        ?.let { error(it.formatForApp()) }
}
