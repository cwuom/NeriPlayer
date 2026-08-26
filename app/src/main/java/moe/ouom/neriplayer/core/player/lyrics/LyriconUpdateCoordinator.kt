package moe.ouom.neriplayer.core.player.lyrics

import kotlinx.coroutines.Job

internal data class LyriconUpdateRequest(
    val generation: Long,
    val job: Job?,
)

internal class LyriconUpdateCoordinator {
    private val lock = Any()
    private var generation = 0L
    private var activeJob: Job? = null

    fun replace(
        createJob: (generation: Long) -> Job?,
        onPublished: (LyriconUpdateRequest) -> Unit,
    ): LyriconUpdateRequest {
        return synchronized(lock) {
            val updateGeneration = ++generation
            activeJob?.cancel()
            activeJob = null

            val request = LyriconUpdateRequest(
                generation = updateGeneration,
                job = createJob(updateGeneration),
            )
            activeJob = request.job
            onPublished(request)
            request
        }
    }

    fun runIfCurrent(
        generation: Long,
        job: Job,
        block: () -> Unit,
    ): Boolean {
        return synchronized(lock) {
            if (generation != this.generation || activeJob !== job) {
                return@synchronized false
            }
            block()
            true
        }
    }

    fun cancelActive() {
        synchronized(lock) {
            generation += 1L
            activeJob?.cancel()
            activeJob = null
        }
    }

    fun hasPendingJob(): Boolean {
        return synchronized(lock) { activeJob?.isCompleted == false }
    }
}
