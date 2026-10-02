package moe.ouom.neriplayer.data.sync.dataset.disk

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal class PlaybackDatasetLease private constructor(private val owner: SharedOwnership) {
    val directory: File get() = owner.directory
    private var closed = false

    fun retain(): PlaybackDatasetLease = synchronized(this) {
        check(!closed) { "Playback lease is closed" }
        owner.retain()
        PlaybackDatasetLease(owner)
    }

    fun closeAndDelete() = synchronized(this) {
        if (!closed) {
            closed = true
            owner.release()
        }
    }

    private class SharedOwnership(val directory: File, private val channel: FileChannel, private val lock: FileLock) {
        private var references = 1
        fun retain() = synchronized(this) {
            check(references > 0) { "Playback lease cannot be retained" }
            references = Math.incrementExact(references)
        }
        fun release() = synchronized(this) {
            check(references > 0) { "Playback lease already released" }
            references--
            if (references == 0) channel.use { lock.use { deleteOwnedPlaybackDirectory(directory) } }
        }
    }
    companion object {
        private val marker = "NERI_SYNC_PLAYBACK_STAGE_1".toByteArray(Charsets.US_ASCII)
        fun create(directory: File): PlaybackDatasetLease {
            requirePlaybackDirectory(directory.toPath())
            val channel = FileChannel.open(File(directory, "owner.lock").toPath(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            try {
                val lock = channel.lock()
                val bytes = ByteBuffer.wrap(marker)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
                return PlaybackDatasetLease(SharedOwnership(directory, channel, lock))
            } catch (error: Exception) {
                try { channel.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                try { deleteOwnedPlaybackDirectory(directory) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                throw error
            }
        }
        fun recover(directory: File) {
            if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            val owner = File(directory, "owner.lock").toPath()
            if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) return
            FileChannel.open(owner, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (!hasPlaybackMarker(channel)) return
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                lock?.use { deleteOwnedPlaybackDirectory(directory) }
            }
        }

        private fun hasPlaybackMarker(channel: FileChannel): Boolean {
            if (channel.size() != marker.size.toLong()) return false
            val bytes = ByteBuffer.allocate(marker.size)
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) <= 0) return false
            }
            return bytes.array().contentEquals(marker)
        }
    }
}

private fun deleteOwnedPlaybackDirectory(directory: File) {
    val path = directory.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    requirePlaybackDirectory(path)
    if (!Files.isRegularFile(path.resolve("owner.lock"), LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Playback staging owner is not a regular file")
    }
    releasePlaybackData(directory)
    releasePlaybackDirectory(directory)
}

private fun releasePlaybackData(directory: File) {
    Files.newDirectoryStream(directory.toPath()).use { files ->
        for (file in files) {
            if (file.fileName.toString() != "owner.lock") deletePlaybackData(file)
        }
    }
}

private fun releasePlaybackDirectory(directory: File) {
    // 数据删除失败时保留所有权标记，下次恢复不能把大文件当作未知来源跳过
    Files.delete(directory.toPath().resolve("owner.lock"))
    Files.delete(directory.toPath())
}

private fun deletePlaybackData(path: Path) {
    // 不跟随链接，未知目标只能作为本目录中的链接条目删除
    Files.walkFileTree(path, PlaybackDataDeletionVisitor)
}

private object PlaybackDataDeletionVisitor : SimpleFileVisitor<Path>() {
    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        Files.delete(file)
        return FileVisitResult.CONTINUE
    }

    override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
        if (failure != null) throw failure
        Files.delete(directory)
        return FileVisitResult.CONTINUE
    }
}

internal fun requirePlaybackDirectory(path: Path) {
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Playback staging directory is not a regular directory")
    }
}
