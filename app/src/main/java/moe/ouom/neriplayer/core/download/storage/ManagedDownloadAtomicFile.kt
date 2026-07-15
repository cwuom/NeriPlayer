package moe.ouom.neriplayer.core.download.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

internal object ManagedDownloadAtomicFile {
    private val writeIdGenerator = AtomicLong(0L)

    fun writeTextAtomically(target: File, content: String) {
        val parent = target.parentFile
        parent?.mkdirs()
        val tempFile = File(
            parent ?: File("."),
            "${target.name}.tmp.${writeIdGenerator.incrementAndGet()}.${System.nanoTime()}"
        )
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            moveFileAtomically(tempFile, target)
        } catch (error: Exception) {
            runCatching {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
            throw error
        }
    }

    private fun moveFileAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}
