package moe.ouom.neriplayer.data.local.playlist

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface LocalPlaylistStorage {
    fun readPrimary(): String?

    fun readBackup(): String?

    fun commit(text: String, rotateBackup: Boolean = true)

    fun quarantinePrimary(): File?
}

internal class LocalPlaylistFileStorage(
    private val file: File,
    fallbackParent: File
) : LocalPlaylistStorage {
    private val parent = file.parentFile ?: fallbackParent
    private val backup = File(parent, "${file.name}.bak")

    override fun readPrimary(): String? = file.takeIf(File::exists)?.readText()

    override fun readBackup(): String? = backup.takeIf(File::exists)?.readText()

    override fun commit(text: String, rotateBackup: Boolean) {
        ensureParentExists()
        val pending = writeSyncedTempFile("${file.name}.", ".tmp", text)
        try {
            if (rotateBackup && file.exists()) {
                replaceBackup(file)
            }
            moveIntoPlace(pending, file)
            fsyncDirectory()
        } catch (error: Exception) {
            pending.delete()
            throw error
        }
    }

    override fun quarantinePrimary(): File? {
        if (!file.exists()) return null

        ensureParentExists()
        val quarantine = nextQuarantineFile()
        moveIntoPlace(file, quarantine)
        fsyncDirectory()
        return quarantine
    }

    private fun replaceBackup(source: File) {
        val pendingBackup = File.createTempFile("${backup.name}.", ".tmp", parent)
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(pendingBackup).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            moveIntoPlace(pendingBackup, backup)
            fsyncDirectory()
        } catch (error: Exception) {
            pendingBackup.delete()
            throw error
        }
    }

    private fun writeSyncedTempFile(prefix: String, suffix: String, text: String): File {
        val pending = File.createTempFile(prefix, suffix, parent)
        try {
            FileOutputStream(pending).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            return pending
        } catch (error: Exception) {
            pending.delete()
            throw error
        }
    }

    private fun ensureParentExists() {
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create playlist storage directory: ${parent.absolutePath}")
        }
        if (!parent.isDirectory) {
            throw IOException("Playlist storage parent is not a directory: ${parent.absolutePath}")
        }
    }

    private fun nextQuarantineFile(): File {
        val timestamp = System.currentTimeMillis()
        var candidate = File(parent, "${file.name}.corrupt-$timestamp")
        var collisionIndex = 2
        while (candidate.exists()) {
            candidate = File(parent, "${file.name}.corrupt-$timestamp-$collisionIndex")
            collisionIndex++
        }
        return candidate
    }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            return
        } catch (_: Exception) {
            // 部分文件系统不支持原子移动，同目录替换仍可避免截断正式文件
        }

        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun fsyncDirectory() {
        runCatching {
            FileInputStream(parent).use { input ->
                input.fd.sync()
            }
        }
    }
}
