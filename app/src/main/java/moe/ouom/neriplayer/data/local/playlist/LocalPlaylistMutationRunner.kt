package moe.ouom.neriplayer.data.local.playlist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger

suspend fun <T> runLocalPlaylistMutationSafely(
    operation: String,
    mutation: suspend () -> T
): Result<T> {
    return try {
        Result.success(mutation())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.e("LocalPlaylistMutation", "$operation failed", error)
        Result.failure(error)
    }
}

fun <T> CoroutineScope.launchLocalPlaylistMutation(
    operation: String,
    onResult: (Result<T>) -> Unit = {},
    mutation: suspend () -> T
): Job {
    return launch {
        onResult(runLocalPlaylistMutationSafely(operation, mutation))
    }
}
