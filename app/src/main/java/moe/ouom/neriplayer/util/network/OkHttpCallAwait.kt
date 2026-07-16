package moe.ouom.neriplayer.util.network

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
