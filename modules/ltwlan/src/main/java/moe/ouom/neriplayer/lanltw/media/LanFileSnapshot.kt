package moe.ouom.neriplayer.lanltw.media

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.lan.LanLocalMedia

/** Immutable private copy. The owner releases it after all active readers close. */
data class LanFileSnapshot(val file: File, val sha256: String, val byteLength: Long) {
    companion object {
        suspend fun create(directory: File, open: () -> InputStream): LanFileSnapshot {
            val id = UUID.randomUUID().toString()
            val partial = File(directory, "$id.part")
            val complete = File(directory, "$id.media")
            try {
                return withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs()) { "LOCAL_STORAGE" }
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                open().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            length += count
                            check(length <= LanLocalMedia.MAX_BYTES) { "MEMORY_LIMIT" }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                check(length > 0) { "AUDIO_FORMAT" }
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(complete)) { "LOCAL_STORAGE" }
                LanFileSnapshot(complete, digest.digest().joinToString("") { "%02x".format(it) }, length)
                }
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { partial.delete(); complete.delete() }
                throw error
            }
        }
    }
}
