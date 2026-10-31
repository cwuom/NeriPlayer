package moe.ouom.neriplayer.data.local.media.cache

import android.content.Context
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem

internal object EmbeddedCoverCacheUsage {
    private val retainedPaths = HashSet<String>()
    private val generatedCoverName = Regex("[0-9a-f]{64}\\.jpg")

    fun retain(context: Context, reference: String?): String? {
        val file = localReferenceFile(reference) ?: return reference
        if (!generatedCoverName.matches(file.name)) return reference
        val filesDirectory = context.filesDir ?: return reference
        return synchronized(LocalMediaSupport.embeddedCoverCacheLock) {
            val directory = File(filesDirectory, "local_audio_covers")
            val canonicalFile = file.canonicalFile
            val canonicalDirectory = directory.canonicalFile
            if (file.parentFile?.canonicalFile != canonicalDirectory && canonicalFile.parentFile != canonicalDirectory) {
                return@synchronized reference
            }
            if (Files.isSymbolicLink(directory.toPath()) ||
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() <= 0L
            ) {
                return@synchronized null
            }
            // 扫描预览和异步补全可能晚于文件提取才发布引用，本次启动用过的封面留到下次启动再回收
            retainedPaths += canonicalFile.path
            reference
        }
    }

    fun isInUse(file: File): Boolean = synchronized(LocalMediaSupport.embeddedCoverCacheLock) {
        file.canonicalPath in retainedPaths
    }

    fun retainSong(context: Context, song: SongItem): SongItem {
        val cover = retain(context, song.coverUrl)
        val customCover = retain(context, song.customCoverUrl)
        val originalCover = retain(context, song.originalCoverUrl)
        if (cover == song.coverUrl && customCover == song.customCoverUrl && originalCover == song.originalCoverUrl) return song
        return song.copy(coverUrl = cover, customCoverUrl = customCover, originalCoverUrl = originalCover)
    }

    private fun localReferenceFile(reference: String?): File? {
        val normalized = reference?.trim() ?: return null
        if (normalized.startsWith('/')) return File(normalized)
        if (!normalized.startsWith("file:", ignoreCase = true)) return null
        return runCatching { URI(normalized) }.getOrNull()
            ?.path?.let(::File)
    }
}
