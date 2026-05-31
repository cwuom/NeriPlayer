package moe.ouom.neriplayer.core.player.prefetch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun interface YouTubePrefetchTask {
    suspend fun prefetch(videoId: String)
}

class YouTubePrefetchRunner(
    private val task: YouTubePrefetchTask,
    private val maxConcurrency: Int = 1
) {
    init {
        require(maxConcurrency > 0) { "maxConcurrency must be positive" }
    }

    fun launch(scope: CoroutineScope, videoIds: List<String>): Job = scope.launch {
        val semaphore = Semaphore(maxConcurrency)
        supervisorScope {
            videoIds.forEach { videoId ->
                launch {
                    semaphore.withPermit {
                        try {
                            task.prefetch(videoId)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Individual prefetch failures should not abort the rest of the batch.
                        }
                    }
                }
            }
        }
    }
}
