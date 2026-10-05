package top.aryun.yzliyin.util

import android.util.Log

/**
 * 轻量日志封装（接口与移植的 NeriPlayer 代码保持一致：tag + message + 可选 Throwable）。
 */
object NPLogger {

    private const val PREFIX = "YZ-"

    fun d(tag: String, message: String) {
        Log.d(PREFIX + tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(PREFIX + tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(PREFIX + tag, message, throwable) else Log.w(PREFIX + tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(PREFIX + tag, message, throwable) else Log.e(PREFIX + tag, message)
    }
}
