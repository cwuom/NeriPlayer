package moe.ouom.neriplayer.core.download.storage.tree

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.core.download.storage.NO_MEDIA_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentMap

internal object ManagedDownloadMediaScanIsolation {
    private const val MARKER_CREATION_ATTEMPTS = 3

    fun ensureFileDirectory(
        subdirectory: String,
        directory: File,
        ensuredMarkers: ConcurrentMap<String, Boolean>
    ) {
        if (!ManagedDownloadTreeNaming.shouldCreateNoMediaMarker(subdirectory)) return
        val cacheKey = directory.absolutePath
        if (ensuredMarkers[cacheKey] == true) return

        val marker = File(directory, NO_MEDIA_FILE_NAME)
        if (marker.isFile) {
            ensuredMarkers[cacheKey] = true
            return
        }

        repeat(MARKER_CREATION_ATTEMPTS) {
            runCatching { marker.createNewFile() }
            if (marker.isFile) {
                ensuredMarkers[cacheKey] = true
                return
            }
        }
        throw IOException("无法创建 $NO_MEDIA_FILE_NAME: ${directory.absolutePath}")
    }

    fun ensureTreeDirectory(
        context: Context,
        subdirectory: String,
        directory: DocumentFile,
        ensuredMarkers: ConcurrentMap<String, Boolean>,
        hasCachedChild: (Context, DocumentFile, String) -> Boolean,
        createMarker: (DocumentFile) -> DocumentFile?,
        isMarkerAccessible: (Context, DocumentFile) -> ManagedDownloadReferenceIo.AccessResult,
        rememberMarker: (DocumentFile, String) -> Unit
    ) {
        if (!ManagedDownloadTreeNaming.shouldCreateNoMediaMarker(subdirectory)) return
        val cacheKey = directory.uri.toString()
        if (ensuredMarkers[cacheKey] == true) return
        if (hasCachedChild(context, directory, NO_MEDIA_FILE_NAME)) {
            ensuredMarkers[cacheKey] = true
            return
        }

        repeat(MARKER_CREATION_ATTEMPTS) {
            if (hasCachedChild(context, directory, NO_MEDIA_FILE_NAME)) {
                ensuredMarkers[cacheKey] = true
                return
            }
            val marker = try {
                createMarker(directory)
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (marker != null && isUsableNoMediaMarker(isMarkerAccessible(context, marker))) {
                val storedName = ManagedDownloadTreeNaming.resolveTreeStoredName(
                    marker.name,
                    NO_MEDIA_FILE_NAME
                )
                if (storedName == NO_MEDIA_FILE_NAME) {
                    rememberMarker(marker, storedName)
                    ensuredMarkers[cacheKey] = true
                    return
                }
                runCatching { marker.delete() }
            }
        }
        throw IOException("无法创建 $NO_MEDIA_FILE_NAME: ${directory.uri}")
    }

    internal fun isUsableNoMediaMarker(
        accessResult: ManagedDownloadReferenceIo.AccessResult
    ): Boolean = accessResult == ManagedDownloadReferenceIo.AccessResult.Accessible
}
