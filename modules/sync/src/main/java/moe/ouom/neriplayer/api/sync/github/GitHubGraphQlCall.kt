package moe.ouom.neriplayer.api.sync.github

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

internal suspend fun <T> executeGraphQlMutation(call: Call, validate: (Response) -> T): T =
    executeGitHubCall(call) { response ->
        try {
            validate(response)
        } catch (error: IOException) {
            // 取消和超限都先切断请求，关闭正文不再排空尚未读取的数据
            call.cancel()
            throw error
        }
    }

internal suspend fun <T> executeGitHubCall(call: Call, parse: (Response) -> T): T {
    // 以普通返回值传递异常，避免协程栈恢复复制异常实例
    val result = suspendCancellableCoroutine<Result<T>> { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val failure = e.cause as? CancellationException ?: e
                continuation.resumeWith(Result.success(Result.failure(failure)))
            }

            override fun onResponse(call: Call, response: Response) {
                val parsed = runCatching {
                    response.use(parse)
                }
                continuation.resumeWith(Result.success(parsed))
            }
        })
    }
    return result.getOrThrow()
}
