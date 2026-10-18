package top.aryun.yzliyin.listentogether.protocol

/**
 * 一起听渠道标识（协议字段 channelId 的取值）。
 *
 * 服务端把 channelId 当不透明字符串转发，这里保留 NeriPlayer 的既有取值以保持互通；
 * 音之理音自身只会产生 netease / kugou / bilibili 三种。
 */
object ListenTogetherChannels {
    const val NETEASE = "netease"
    const val KUGOU = "kugou"
    const val BILIBILI = "bilibili"
    const val YOUTUBE_MUSIC = "youtubeMusic"
    const val LOCAL = "local"
}
