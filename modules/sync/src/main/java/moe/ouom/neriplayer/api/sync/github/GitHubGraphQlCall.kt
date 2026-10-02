package moe.ouom.neriplayer.api.sync.github

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

internal suspend fun executeGraphQlMutation(call: Call, validate: (Response) -> Unit) {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        try {
                            validate(it)
                        } catch (error: IOException) {
                            // 取消和超限都先切断请求，关闭正文不再排空尚未读取的数据
                            call.cancel()
                            throw error
                        }
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }
}
