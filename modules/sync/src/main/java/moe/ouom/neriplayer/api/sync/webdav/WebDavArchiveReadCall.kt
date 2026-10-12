package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal suspend fun <T> executeWebDavArchiveRead(call: Call, parse: (Response, () -> Unit) -> T): T {
    // 以普通返回值传递异常，避免协程栈恢复复制异常实例
    val result = suspendCancellableCoroutine<Result<T>> { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val failure = e.cause as? CancellationException ?: e
                continuation.resumeWith(Result.success(Result.failure(failure)))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { parse(it, call::cancel) } }
                continuation.resumeWith(Result.success(result))
            }
        })
    }
    return result.getOrThrow()
}
