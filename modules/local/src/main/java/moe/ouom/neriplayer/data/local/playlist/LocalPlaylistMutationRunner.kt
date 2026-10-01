package moe.ouom.neriplayer.data.local.playlist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.coroutines.runCatchingNonCancellation

suspend fun <T> runLocalPlaylistMutationSafely(
    operation: String,
    mutation: suspend () -> T
): Result<T> {
    return runCatchingNonCancellation(mutation).onFailure { error ->
        NPLogger.e("LocalPlaylistMutation", "$operation failed", error)
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
