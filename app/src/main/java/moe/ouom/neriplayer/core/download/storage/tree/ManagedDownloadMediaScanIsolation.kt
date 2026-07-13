package moe.ouom.neriplayer.core.download.storage.tree

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.core.download.storage.NO_MEDIA_FILE_NAME
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentMap

internal object ManagedDownloadMediaScanIsolation {
    fun ensureFileDirectory(
        subdirectory: String,
        directory: File,
        ensuredMarkers: ConcurrentMap<String, Boolean>
    ) {
        if (!ManagedDownloadTreeNaming.shouldCreateNoMediaMarker(subdirectory)) return
        val cacheKey = directory.absolutePath
        if (ensuredMarkers[cacheKey] == true) return

        val marker = File(directory, NO_MEDIA_FILE_NAME)
        if (marker.exists()) {
            ensuredMarkers[cacheKey] = true
            return
        }
        if (!marker.createNewFile()) {
            throw IOException("无法创建 $NO_MEDIA_FILE_NAME")
        }
        ensuredMarkers[cacheKey] = true
    }

    fun ensureTreeDirectory(
        context: Context,
        subdirectory: String,
        directory: DocumentFile,
        ensuredMarkers: ConcurrentMap<String, Boolean>,
        hasCachedChild: (Context, DocumentFile, String) -> Boolean,
        createMarker: (DocumentFile) -> DocumentFile?,
        rememberMarker: (DocumentFile, String) -> Unit
    ) {
        if (!ManagedDownloadTreeNaming.shouldCreateNoMediaMarker(subdirectory)) return
        val cacheKey = directory.uri.toString()
        if (ensuredMarkers[cacheKey] == true) return
        if (hasCachedChild(context, directory, NO_MEDIA_FILE_NAME)) {
            ensuredMarkers[cacheKey] = true
            return
        }

        val marker = createMarker(directory)
            ?: throw IOException("无法创建 $NO_MEDIA_FILE_NAME")
        val storedName = ManagedDownloadTreeNaming.resolveTreeStoredName(marker.name, NO_MEDIA_FILE_NAME)
        rememberMarker(marker, storedName)
        ensuredMarkers[cacheKey] = true
    }
}
