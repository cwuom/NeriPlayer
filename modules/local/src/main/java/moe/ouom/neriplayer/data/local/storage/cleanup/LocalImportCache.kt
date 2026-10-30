package moe.ouom.neriplayer.data.local.storage.cleanup

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.local.storage.source.LocalImportCacheAccess
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.FileStats

internal class LocalImportCache(
    private val directory: File,
    private val readReferences: suspend () -> Set<String>,
    private val referenceSnapshotValid: () -> Boolean = { true },
    private val fileAccessLock: Any = Any(),
    private val isCoverInUse: (File) -> Boolean = { false },
    private val clearReadCaches: () -> Unit
) : LocalImportCacheAccess {
    override suspend fun scan(): FileStats = try {
        unreferencedCandidates().fold(FileStats.Empty) { total, candidate -> total + FileStats(candidate.size, 1) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        FileStats.Empty
    }

    override suspend fun clear(): ExtraCacheClearResult {
        var freedBytes = 0L
        var deletedFiles = 0
        var success = true
        try {
            val candidates = unreferencedCandidates()
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                val deleted = synchronized(fileAccessLock) {
                    check(referenceSnapshotValid()) { "Local cover references changed during cleanup" }
                    if (candidate(candidate.file) != candidate) null else candidate.file.delete()
                }
                when (deleted) {
                    true -> {
                        freedBytes += candidate.size
                        deletedFiles++
                    }
                    false -> success = false
                    null -> Unit
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            success = false
        } finally {
            clearReadCaches()
        }
        return ExtraCacheClearResult(success, freedBytes, 0, deletedFiles)
    }

    private suspend fun unreferencedCandidates(): List<Candidate> {
        currentCoroutineContext().ensureActive()
        val root = attributes(directory) ?: return emptyList()
        if (!root.isDirectory || root.isSymbolicLink) throw IOException("Local cover cache is not an ordinary directory")
        val candidates = (directory.listFiles() ?: throw IOException("Local cover cache cannot be listed"))
            .mapNotNull(::candidate)
        if (candidates.isEmpty()) return emptyList()
        // 先固定候选，再读取完整引用，扫描或导入期间新生成的图片留到下一次清理
        val referencedPaths = readReferences().mapNotNull(::localReferencePath).toSet()
        return candidates.filter { it.file.canonicalPath !in referencedPaths }
    }

    private fun candidate(file: File): Candidate? = synchronized(fileAccessLock) {
        if (!GENERATED_COVER_NAME.matches(file.name) || isCoverInUse(file)) return@synchronized null
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("Local cover cache root changed")
        val attributes = attributes(file) ?: return@synchronized null
        if (!attributes.isRegularFile || attributes.isSymbolicLink) return@synchronized null
        Candidate(file, attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey())
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun localReferencePath(reference: String): String? {
        val normalized = reference.trim()
        val file = when {
            normalized.startsWith('/') -> File(normalized)
            normalized.startsWith("file:", ignoreCase = true) -> URI(normalized).path?.let(::File)
            else -> null
        }
        return file?.canonicalPath
    }

    private data class Candidate(val file: File, val size: Long, val modifiedAt: Long, val fileKey: Any?)

    private companion object {
        val GENERATED_COVER_NAME = Regex("[0-9a-f]{64}\\.jpg")
    }
}
