package moe.ouom.neriplayer.core.player.lan

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.data.model.lan.LanFileResource

internal class LanGuestCoverReceiver(private val scope: CoroutineScope) {
    private data class Ready(val resource: LanFileResource, val file: File)

    private var ready: Ready? = null
    private var requested: LanFileResource? = null
    private var download: Job? = null

    fun show(resource: LanFileResource, fetch: suspend () -> File, apply: (File) -> Boolean) {
        if (resource == requested && download?.isActive == true) return
        download?.cancel()
        requested = resource
        val cached = ready?.takeIf { it.resource == resource && it.file.isFile }
        if (cached != null) {
            apply(cached.file)
            return
        }
        download = scope.launch {
            val file = fetchWithRetry(fetch) ?: return@launch
            var retained = false
            try {
                ensureActive()
                if (!apply(file)) return@launch
                ready?.file?.takeUnless { it == file }?.delete()
                ready = Ready(resource, file)
                retained = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
            } finally {
                if (!retained) file.delete()
            }
        }
    }

    fun cancelDownload() {
        download?.cancel()
        download = null
        requested = null
    }

    fun clear(): File? {
        cancelDownload()
        val file = ready?.file
        ready = null
        file?.delete()
        return file
    }

    private suspend fun fetchWithRetry(fetch: suspend () -> File): File? {
        repeat(2) { attempt ->
            val result = try { fetchOnce(fetch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (result != null) return result
            if (attempt == 0) delay(500)
        }
        return null
    }

    private suspend fun fetchOnce(fetch: suspend () -> File): File? {
        var completed: File? = null
        var result: File? = null
        try {
            result = withTimeoutOrNull(15_000) { fetch().also { completed = it } }
            return result
        } finally {
            if (result == null) completed?.delete()
        }
    }
}
