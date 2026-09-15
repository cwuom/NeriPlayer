package moe.ouom.neriplayer.core.download

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.storage.directory.ManagedDownloadDirectoryIdentity

internal class ManagedDownloadStorageSettings(
    private val defaultRootPathProvider: (Context) -> String
) {
    @Volatile
    private var customDirectoryUri: String? = null

    @Volatile
    private var customDirectoryLabel: String? = null

    @Volatile
    private var downloadFileNameTemplate: String? = null

    val configuredDirectoryUri: String?
        get() = customDirectoryUri

    val fileNameTemplate: String?
        get() = downloadFileNameTemplate

    fun prime(directoryUri: String?, directoryLabel: String?, fileNameTemplate: String?) {
        customDirectoryUri = directoryUri?.takeIf { it.isNotBlank() }
        customDirectoryLabel = directoryLabel?.takeIf { it.isNotBlank() }
        downloadFileNameTemplate = normalizeDownloadFileNameTemplate(fileNameTemplate)
    }

    fun updateDirectoryUri(uri: String?) {
        customDirectoryUri = uri?.takeIf { it.isNotBlank() }
    }

    fun updateDirectoryLabel(label: String?) {
        customDirectoryLabel = label?.takeIf { it.isNotBlank() }
    }

    fun updateFileNameTemplate(template: String?) {
        downloadFileNameTemplate = normalizeDownloadFileNameTemplate(template)
    }

    fun describeDirectory(context: Context, uriString: String? = customDirectoryUri): String {
        val resolvedUri = uriString?.takeIf { it.isNotBlank() }
        if (resolvedUri.isNullOrBlank()) {
            return context.getString(R.string.settings_download_directory_default_label)
        }
        if (resolvedUri == customDirectoryUri && !customDirectoryLabel.isNullOrBlank()) {
            return customDirectoryLabel.orEmpty()
        }
        val treeUri = runCatching { resolvedUri.toUri() }.getOrNull()
        val tree = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        return tree?.name?.takeIf { it.isNotBlank() } ?: resolvedUri
    }

    fun snapshotCacheKey(context: Context): String {
        val configuredIdentity = ManagedDownloadDirectoryIdentity.directoryIdentity(customDirectoryUri)
        return if (configuredIdentity != null) {
            "tree:$configuredIdentity"
        } else {
            "file:${defaultRootPathProvider(context)}"
        }
    }
}
