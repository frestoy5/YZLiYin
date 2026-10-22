package top.aryun.yzliyin.youtube.auth

// 移植自 fork util.network/OkHttpCallAwait.kt（实现逐字保留）。
// fork 里是 util.network 公共扩展，本项目只有 YouTube 轮换请求用到，就近放进 youtube.auth。

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T> Call.awaitResponse(transform: (Response) -> T): T {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runCatching {
                    response.use(transform)
                }.onSuccess { value ->
                    if (continuation.isActive) {
                        continuation.resume(value)
                    }
                }.onFailure { error ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        })
    }
}
