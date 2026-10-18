package top.aryun.yzliyin.listentogether.playback

import top.aryun.yzliyin.core.model.Song
import top.aryun.yzliyin.listentogether.mapping.toListenTogetherTrackOrNull

internal fun Song?.isShareableForListenTogether(): Boolean {
    return this?.toListenTogetherTrackOrNull() != null
}

internal fun List<Song>.hasShareableListenTogetherTrackAt(index: Int): Boolean {
    return getOrNull(index).isShareableForListenTogether()
}
