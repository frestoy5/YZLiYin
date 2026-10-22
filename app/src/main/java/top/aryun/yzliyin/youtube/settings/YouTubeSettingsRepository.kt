package top.aryun.yzliyin.youtube.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import top.aryun.yzliyin.core.AppGraph

/**
 * 只暴露 YouTube 播放仓库要用的两项设置（音质、播放源偏好）。
 *
 * fork 版是 SettingsRepository + DataStore flow；本项目设置统一落在 SessionStore，
 * 读取点每次都现取，等价于「改完下一次取链即生效」。
 */
class YouTubeSettingsRepository {

    /** 音质：low / medium / high / very_high，空串表示跟随默认。 */
    val youtubeAudioQualityFlow: Flow<String?>
        get() = flowOf(AppGraph.store.youtubeQuality)

    /** 播放源偏好：automatic / visionos / android_vr / …。 */
    val youtubePlaybackSourceFlow: Flow<YouTubePlaybackSourcePreference?>
        get() = flowOf(
            YouTubePlaybackSourcePreferencePolicy.fromStorage(AppGraph.store.youtubePlaybackSourcePreference),
        )

    /** 功能总开关（设置 → 账号里的 YouTube 开关）。 */
    val youtubeEnabled: Boolean
        get() = AppGraph.store.youtubeEnabled
}
