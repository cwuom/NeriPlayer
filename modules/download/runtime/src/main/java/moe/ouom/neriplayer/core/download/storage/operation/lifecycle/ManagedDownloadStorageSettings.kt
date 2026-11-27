package moe.ouom.neriplayer.core.download.storage.operation.lifecycle

import moe.ouom.neriplayer.core.download.naming.normalizeDownloadFileNameTemplate
import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.storage.directory.ManagedDownloadDirectoryIdentity

internal class ManagedDownloadStorageSettings(
    private val defaultRootPathProvider: (Context) -> String
) {
    data class FileNameTemplateState(val template: String?, val revision: Long)

    @Volatile
    private var customDirectoryUri: String? = null

    @Volatile
    private var customDirectoryLabel: String? = null

    private val mutableFileNameTemplateState = MutableStateFlow(FileNameTemplateState(null, 0L))
    val fileNameTemplateState: StateFlow<FileNameTemplateState> = mutableFileNameTemplateState.asStateFlow()

    val configuredDirectoryUri: String?
        get() = customDirectoryUri

    val fileNameTemplate: String?
        get() = fileNameTemplateState.value.template

    fun prime(directoryUri: String?, directoryLabel: String?, fileNameTemplate: String?) {
        customDirectoryUri = directoryUri?.takeIf { it.isNotBlank() }
        customDirectoryLabel = directoryLabel?.takeIf { it.isNotBlank() }
        updateFileNameTemplate(fileNameTemplate)
    }

    @Synchronized
    fun updateDirectoryUri(uri: String?): Boolean {
        val normalizedUri = uri?.takeIf { it.isNotBlank() }
        if (customDirectoryUri == normalizedUri) return false
        customDirectoryUri = normalizedUri
        return true
    }

    fun updateDirectoryLabel(label: String?) {
        customDirectoryLabel = label?.takeIf { it.isNotBlank() }
    }

    fun updateFileNameTemplate(template: String?) {
        val normalizedTemplate = normalizeDownloadFileNameTemplate(template)
        mutableFileNameTemplateState.update { current ->
            if (current.template == normalizedTemplate) current
            else FileNameTemplateState(normalizedTemplate, current.revision + 1L)
        }
    }

    fun describeDirectory(context: Context, uriString: String? = customDirectoryUri): String {
        val resolvedUri = uriString?.takeIf { it.isNotBlank() }
        if (resolvedUri.isNullOrBlank()) {
            return context.getString(CoreCommonR.string.settings_download_directory_default_label)
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
