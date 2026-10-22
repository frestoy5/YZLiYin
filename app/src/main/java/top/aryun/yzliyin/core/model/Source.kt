package top.aryun.yzliyin.core.model

/**
 * 在线音乐数据源。
 *
 * [id] 用于持久化与路由（不要随意改动）。
 *
 * ⚠ [label] 是写死的中文，**只喂日志**（`NPLogger`，本项目日志一律不翻译）。
 * 界面展示请用 `ui/components/Common.kt` 里的 `Source.labelText()`——那才随
 * 中文 / 日本語 / English 切换。别把 [label] 拼进任何面向用户的文案。
 */
enum class Source(val id: String, val label: String) {
    NETEASE("netease", "网易云音乐"),
    KUGOU("kugou", "酷狗音乐"),
    BILIBILI("bilibili", "哔哩哔哩"),
    // 声明顺序即跨源兜底的轮转顺序（网易云 → 酷狗 → B 站 → YouTube），别随意调换
    YOUTUBE("youtube", "YouTube"),
    // 本机扫描出来的音乐（MediaStore），只本地播放、不参与跨源兜底与搜索
    LOCAL("local", "本机音乐");

    companion object {
        fun fromId(id: String): Source = entries.firstOrNull { it.id == id } ?: NETEASE

        /**
         * 能拿去**搜**、也能参与跨源兜底的源。
         *
         * 本机音乐（[LOCAL]）既没有远端曲库、也兜不了底，排掉：否则搜索页会多出
         * 一个永远空着的分组和一个点不动的 chip，兜底链里还会白跑一轮。
         */
        val online: List<Source>
            get() = entries.filter { it != LOCAL }
    }
}
