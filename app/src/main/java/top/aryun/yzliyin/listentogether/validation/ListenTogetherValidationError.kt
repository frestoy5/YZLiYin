package top.aryun.yzliyin.listentogether.validation

import android.content.Context

/**
 * 校验错误：messageFormat 为最终展示文案（可含 %1$d 等占位符），args 为格式化参数。
 *
 * [context] 参数保留是为兼容上游调用点签名；音之理音不做多语言，无需再取资源。
 */
data class ListenTogetherValidationError(
    val messageFormat: String,
    val args: List<Any> = emptyList()
) {
    fun format(context: Context): String = formatForApp()

    fun formatForApp(): String =
        if (args.isEmpty()) messageFormat else String.format(messageFormat, *args.toTypedArray())
}
