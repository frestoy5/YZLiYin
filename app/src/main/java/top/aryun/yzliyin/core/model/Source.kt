package top.aryun.yzliyin.core.model

/**
 * 在线音乐数据源。
 *
 * [id] 用于持久化与路由（不要随意改动），[label] 用于界面展示。
 */
enum class Source(val id: String, val label: String) {
    NETEASE("netease", "网易云音乐"),
    KUGOU("kugou", "酷狗音乐"),
    BILIBILI("bilibili", "哔哩哔哩");

    companion object {
        fun fromId(id: String): Source = entries.firstOrNull { it.id == id } ?: NETEASE
    }
}
