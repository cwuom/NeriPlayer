package moe.ouom.neriplayer.data.local.media.curation

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.net.toUri
import com.kyant.taglib.TagLib
import java.io.File
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.io.isFileInsideDirectory
import moe.ouom.neriplayer.data.local.media.LOCAL_METADATA_SUFFIX
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.coverSidecarNameMatches
import moe.ouom.neriplayer.data.local.media.decodeTextBytes
import moe.ouom.neriplayer.data.local.media.documentChildrenContainSource
import moe.ouom.neriplayer.data.local.media.isMediaStoreCoverReference
import moe.ouom.neriplayer.data.local.media.isMediaStoreUri
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.local.media.openTagLibDescriptor
import moe.ouom.neriplayer.data.local.media.queryContentInfo
import moe.ouom.neriplayer.data.local.media.queryDocumentChildrenDirect
import moe.ouom.neriplayer.data.local.media.readLimitedTextStream
import moe.ouom.neriplayer.data.local.media.resolveInspectableLocalMedia
import moe.ouom.neriplayer.data.local.media.resolveLocalDocumentNavigation
import moe.ouom.neriplayer.data.local.media.resolveSidecarSourceDocumentId
import moe.ouom.neriplayer.data.model.SongItem

object LocalCoverBaselineInspector {
    /** 只有完整读取文件周边和原始图片列表后才能确认无封面，未知状态保持可重试 */
    @JvmStatic
    fun hasConfirmedNoOriginalCover(context: Context, song: SongItem): Boolean = try {
        val source = song.localMediaUri()
        val hasRetainedCover = listOf(song.originalCoverUrl, song.customCoverUrl, song.coverUrl).any {
            !it.isNullOrBlank() && !isMediaStoreCoverReference(it.trim())
        }
        val noSidecarCover = when {
            source == null || hasRetainedCover -> false
            source.scheme.equals("content", ignoreCase = true) -> noDocumentCover(context, source, song)
            source.scheme.equals("file", ignoreCase = true) -> noFileCover(context, source)
            else -> false
        }
        noSidecarCover && readRawLocalCoverPicturesEmpty(context, song) == true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun noFileCover(context: Context, source: Uri): Boolean {
        val audio = source.path?.let(::File) ?: return false
        if (!audio.isFile || !audio.canRead()) return false
        val parent = audio.parentFile ?: return false
        val children = completeDirectory(context, parent) ?: return false
        if (children.none { it.name == audio.name }) return false
        val baseName = audio.nameWithoutExtension
        if (children.any { isCoverCandidate(it.name, baseName, includeFolderCover = true) }) return false
        val metadataName = audio.name + LOCAL_METADATA_SUFFIX
        if (children.filter { LocalMediaSupport.sidecarNameMatches(it.name, metadataName) }
                .any { !metadataHasNoCover(context, it.absolutePath) }
        ) return false
        return children.filter { LocalMediaSupport.isManagedSidecarDirectoryName(it.name, "Covers") }
            .all { directory ->
                completeDirectory(context, directory)?.none {
                    isCoverCandidate(it.name, baseName, includeFolderCover = false)
                } == true
            }
    }

    private fun completeDirectory(context: Context, directory: File): Array<File>? {
        if (!directory.isDirectory || !directory.canRead() || !directory.canExecute()) return null
        // scoped storage 可以隐藏相邻图片，即使 listFiles 成功也不能据此确认目录为空
        if (!hasCompleteFileVisibility(context, directory)) return null
        return directory.listFiles()
    }

    private fun hasCompleteFileVisibility(context: Context, directory: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val privateRoots = listOfNotNull(context.filesDir, context.cacheDir, context.noBackupFilesDir,
            context.codeCacheDir) + context.getExternalFilesDirs(null).filterNotNull() +
            context.externalCacheDirs.filterNotNull()
        if (privateRoots.any { isFileInsideDirectory(directory, it) }) return true
        if (Environment.isExternalStorageLegacy()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Environment.isExternalStorageManager()) return false
        return directory.canonicalPath.split(File.separatorChar).windowed(2).none {
            it[0] == "Android" && it[1] in setOf("data", "obb")
        }
    }

    private fun noDocumentCover(context: Context, source: Uri, song: SongItem): Boolean {
        val navigation = LocalMediaSupport.resolveLocalDocumentNavigation(context, source)
        if (navigation == null) {
            if (!isMediaStoreUri(source)) return false
            val file = LocalMediaSupport.resolveInspectableLocalMedia(context, source, allowDescriptorFallback = false).file
                ?: return false
            return noFileCover(context, file.toUri())
        }
        val parentId = navigation.parentDocumentId ?: return false
        val baseUri = navigation.treeUri ?: navigation.baseUri
        val children = completeDocumentChildren(context, baseUri, parentId) ?: return false
        val sourceName = song.localFileName ?: LocalMediaSupport.queryContentInfo(context, source).displayName
            ?: return false
        val sourceId = LocalMediaSupport.resolveSidecarSourceDocumentId(source, parentId, sourceName) ?: return false
        val audio = children.singleOrNull { !it.isDirectory && it.documentId == sourceId } ?: return false
        if (!LocalMediaSupport.documentChildrenContainSource(children, source, audio.displayName, parentId)) return false
        val baseName = audio.displayName.substringBeforeLast('.', audio.displayName)
        if (children.any { isCoverCandidate(it.displayName, baseName, includeFolderCover = true) }) return false
        val metadataName = audio.displayName + LOCAL_METADATA_SUFFIX
        if (children.filter { LocalMediaSupport.sidecarNameMatches(it.displayName, metadataName) }
                .any { it.isDirectory || !metadataHasNoCover(context, it.uri) }
        ) return false
        return children.filter { LocalMediaSupport.isManagedSidecarDirectoryName(it.displayName, "Covers") }
            .all { directory ->
                directory.isDirectory && completeDocumentChildren(context, baseUri, directory.documentId)?.none {
                    isCoverCandidate(it.displayName, baseName, includeFolderCover = false)
                } == true
            }
    }

    private fun completeDocumentChildren(context: Context, baseUri: Uri, parentId: String) =
        LocalMediaSupport.queryDocumentChildrenDirect(context, baseUri, parentId, maxChildren = null)
            ?.takeIf { it.isComplete }?.children

    private fun isCoverCandidate(name: String, baseName: String, includeFolderCover: Boolean): Boolean =
        LocalMediaSupport.imageExtensions.any { extension ->
            LocalMediaSupport.coverSidecarNameMatches(name, baseName, extension) ||
                (includeFolderCover && LocalMediaSupport.coverFileNames.any {
                    name.equals("$it.$extension", ignoreCase = true)
                })
        }

    private fun metadataHasNoCover(context: Context, reference: String): Boolean {
        val stream = if (reference.startsWith('/')) File(reference).inputStream()
            else context.contentResolver.openInputStream(reference.toUri()) ?: return false
        val raw = stream.use { LocalMediaSupport.decodeTextBytes(LocalMediaSupport.readLimitedTextStream(it)) }
            ?: return false
        val metadata = LocalMediaSupport.parseLocalMetadataSidecar(reference, raw) ?: return false
        return listOf(metadata.coverPath, metadata.coverUrl, metadata.originalCoverUrl, metadata.customCoverUrl)
            .all { it.isNullOrBlank() }
    }
}

internal fun readRawLocalCoverPicturesEmpty(context: Context, song: SongItem): Boolean? = try {
    song.localMediaUri()?.let { source ->
        val resolved = LocalMediaSupport.resolveInspectableLocalMedia(context, source, allowDescriptorFallback = false)
        LocalMediaSupport.openTagLibDescriptor(context, resolved.playableUri, resolved.file)?.use { descriptor ->
            TagLib.getMetadata(descriptor.dup().detachFd(), true)?.pictures?.isEmpty()
        }
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    null
} catch (_: LinkageError) {
    null
}
