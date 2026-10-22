package top.aryun.yzliyin.listentogether.validation

import android.content.Context
import androidx.annotation.StringRes
import top.aryun.yzliyin.R
import top.aryun.yzliyin.util.AppLocale

/**
 * 校验错误：messageFormat 为最终展示文案（可含 %1$d 等占位符），args 为格式化参数。
 *
 * [messageFormat] 保留 `LtMessages` 的中文常量作为兜底与身份标识（无比较依赖，但留着最安全）；
 * 提供了 [messageRes] 时，[formatForApp] 经 [AppLocale] 按**当前界面语言**优先取字符串资源，
 * 取到空串（理论上不会）才回落到中文。
 *
 * [context] 参数保留是为兼容上游调用点签名；本地化不依赖它。
 */
data class ListenTogetherValidationError(
    val messageFormat: String,
    val args: List<Any> = emptyList(),
    @StringRes val messageRes: Int? = null
) {
    fun format(context: Context): String = formatForApp()

    fun formatForApp(): String {
        val res = messageRes
        if (res != null) {
            val localized =
                if (args.isEmpty()) AppLocale.string(res)
                else AppLocale.string(res, *args.toTypedArray())
            if (localized.isNotEmpty()) return localized
        }
        return if (args.isEmpty()) messageFormat else String.format(messageFormat, *args.toTypedArray())
    }
}
