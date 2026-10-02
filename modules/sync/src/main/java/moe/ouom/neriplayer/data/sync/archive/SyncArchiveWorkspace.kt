package moe.ouom.neriplayer.data.sync.archive

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class SyncArchiveWorkspace private constructor(
    val directory: File,
    private val parent: File,
    private val ownerKey: Path,
    private val channel: FileChannel,
    private val lock: FileLock
) : Closeable {
    private var closed = false

    override fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            synchronized(guard) {
                try {
                    channel.use { lock.use { withGuard(parent) { deleteWorkspace(directory.toPath()) } } }
                } finally {
                    activeOwners.remove(ownerKey)
                }
            }
        }
    }

    companion object {
        private val guard = Any()
        private val activeOwners = HashSet<Path>()
        private val stageName = Regex("sync-stage-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun create(parent: File): SyncArchiveWorkspace {
            recoverAbandoned(parent)
            return withGuard(parent) { createLocked(parent.toPath()) }
        }

        private fun createLocked(parent: Path): SyncArchiveWorkspace {
            val directory = parent.resolve("sync-stage-${UUID.randomUUID()}")
            val owner = ownerPath(directory)
            val key = ownerKey(owner)
            val channel = FileChannel.open(owner, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            try {
                val lock = channel.lock()
                Files.createDirectory(directory)
                activeOwners.add(key)
                return SyncArchiveWorkspace(directory.toFile(), parent.toFile(), key, channel, lock)
            } catch (failure: Throwable) {
                try { channel.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { Files.deleteIfExists(owner) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun recoverAbandoned(parent: File) = withGuard(parent) {
            Files.newDirectoryStream(parent.toPath(), "sync-stage-*").use { stages ->
                for (stage in stages) recoverStage(stage)
            }
            Files.newDirectoryStream(parent.toPath(), ".sync-stage-*.owner").use { owners ->
                for (owner in owners) recoverOwner(owner)
            }
        }

        private fun recoverStage(directory: Path) {
            if (!stageName.matches(directory.fileName.toString())) return
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
            val owner = ownerPath(directory)
            // 部分系统关闭同文件的第二个通道会释放本进程原有的锁
            if (ownerKey(owner) in activeOwners) return
            if (Files.exists(owner, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) return
            FileChannel.open(owner, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                tryOwnership(channel)?.use { deleteWorkspace(directory) }
            }
        }

        private fun recoverOwner(owner: Path) {
            val name = owner.fileName.toString().removePrefix(".").removeSuffix(".owner")
            if (!stageName.matches(name)) return
            if (ownerKey(owner) in activeOwners) return
            if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) return
            if (Files.exists(owner.resolveSibling(name), LinkOption.NOFOLLOW_LINKS)) return
            FileChannel.open(owner, StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                tryOwnership(channel)?.use { Files.deleteIfExists(owner) }
            }
        }

        private fun tryOwnership(channel: FileChannel): FileLock? =
            try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }

        private fun ownerPath(directory: Path): Path =
            directory.resolveSibling(".${directory.fileName}.owner")

        private fun ownerKey(owner: Path): Path =
            requireNotNull(owner.parent).toRealPath().resolve(requireNotNull(owner.fileName))

        private fun deleteWorkspace(directory: Path) {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectory(directory)
                Files.walkFileTree(directory, WorkspaceDeletionVisitor)
            }
            // 原始文件删除失败时保留所有权文件，下一次初始化仍可重试
            Files.deleteIfExists(ownerPath(directory))
        }

        private fun <T> withGuard(parent: File, block: () -> T): T = synchronized(guard) {
            requireDirectory(parent.toPath())
            // 根锁覆盖创建与回收，避免目录可见时所有者尚未取得锁
            FileChannel.open(parent.toPath().resolve(".sync-stage.guard"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.lock().use { block() }
            }
        }

        private fun requireDirectory(path: Path) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Sync workspace parent is not a regular directory")
            }
        }
    }
}

private object WorkspaceDeletionVisitor : SimpleFileVisitor<Path>() {
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
