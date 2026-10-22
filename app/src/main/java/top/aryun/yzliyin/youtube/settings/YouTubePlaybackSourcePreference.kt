package top.aryun.yzliyin.youtube.settings

import java.util.Locale

/** 播放 /youtubei/v1/player 时优先用哪个客户端，取自 NeriPlayer-fork 的同名枚举。 */
enum class YouTubePlaybackSourcePreference(
    val storageValue: String,
) {
    Automatic("automatic"),
    VisionOs("visionos"),
    AndroidVr("android_vr"),
    WebRemix("web_remix"),
    TvHtml5("tv_html5"),
    WebCreator("web_creator"),
}

const val DEFAULT_YOUTUBE_PLAYBACK_SOURCE = "automatic"

object YouTubePlaybackSourcePreferencePolicy {
    fun normalize(value: String): String = fromStorage(value).storageValue

    fun fromStorage(value: String?): YouTubePlaybackSourcePreference {
        return when (value?.trim()?.lowercase(Locale.ROOT)) {
            YouTubePlaybackSourcePreference.Automatic.storageValue ->
                YouTubePlaybackSourcePreference.Automatic
            YouTubePlaybackSourcePreference.VisionOs.storageValue,
            "vision_os" -> YouTubePlaybackSourcePreference.VisionOs
            YouTubePlaybackSourcePreference.AndroidVr.storageValue,
            "androidvr" -> YouTubePlaybackSourcePreference.AndroidVr
            YouTubePlaybackSourcePreference.TvHtml5.storageValue ->
                YouTubePlaybackSourcePreference.TvHtml5
            YouTubePlaybackSourcePreference.WebCreator.storageValue,
            "creator" -> YouTubePlaybackSourcePreference.WebCreator
            YouTubePlaybackSourcePreference.WebRemix.storageValue ->
                YouTubePlaybackSourcePreference.WebRemix
            else -> YouTubePlaybackSourcePreference.Automatic
        }
    }
}
