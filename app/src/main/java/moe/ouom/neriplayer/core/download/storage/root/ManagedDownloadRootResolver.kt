package moe.ouom.neriplayer.core.download.storage.root

import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.download.storage.ROOT_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.TREE_ROOT_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.directory.ManagedDownloadDirectoryIdentity

internal class ManagedDownloadRootResolver(
    private val locks: ConcurrentHashMap<String, Any>
) {
    @Volatile
    private var cachedTreeRoot: CachedTreeRoot? = null

    fun normalizeDirectoryUri(uriString: String?): String? {
        return ManagedDownloadDirectoryIdentity.normalizeDirectoryUri(uriString)
    }

    fun resolveConfiguredRoot(
        context: Context,
        configuredDirectoryUri: String?,
        onUnavailableTreeRoot: (String) -> Unit
    ): ManagedDownloadRootHandle {
        val configuredUri = normalizeDirectoryUri(configuredDirectoryUri)
        resolveTreeRoot(context, configuredUri)?.let { return it }
        if (configuredUri != null) {
            onUnavailableTreeRoot(configuredUri)
            throw ManagedDownloadRootUnavailableException(configuredUri)
        }
        return createDefaultRoot(context)
    }

    fun resolveRoot(context: Context, directoryUriString: String?): ManagedDownloadRootHandle? {
        val normalizedUri = normalizeDirectoryUri(directoryUriString)
        return if (normalizedUri == null) {
            createDefaultRoot(context)
        } else {
            resolveTreeRoot(context, normalizedUri)
        }
    }

    fun resolveTreeRoot(context: Context, directoryUriString: String?): ManagedDownloadRootHandle.TreeRoot? {
        val normalizedUri = ManagedDownloadDirectoryIdentity.normalizeConfiguredDirectoryUri(directoryUriString)
            ?: return null
        val identity = ManagedDownloadDirectoryIdentity.directoryIdentity(normalizedUri) ?: normalizedUri
        if (requiresPersistedPermission(normalizedUri) &&
            !hasPersistedWritePermissionForIdentity(context, identity)
        ) {
            invalidateCachedTreeRoot(normalizedUri, identity)
            return null
        }
        resolveCachedTreeRoot(normalizedUri, identity)?.let { return it }

        val lock = locks.computeIfAbsent("tree_root:$identity") { Any() }
        return synchronized(lock) {
            resolveCachedTreeRoot(normalizedUri, identity)?.let { return@synchronized it }
            val treeUri = runCatching { normalizedUri.toUri() }.getOrNull() ?: return@synchronized null
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@synchronized null
            tree.takeIf { isAccessibleDirectory(it) }
                ?.let { rememberCachedTreeRoot(normalizedUri, identity, ManagedDownloadRootHandle.TreeRoot(it)) }
        }
    }

    fun hasPersistedWritePermission(context: Context, directoryUriString: String?): Boolean {
        val normalizedUri = ManagedDownloadDirectoryIdentity.normalizeConfiguredDirectoryUri(directoryUriString)
            ?: return false
        val identity = ManagedDownloadDirectoryIdentity.directoryIdentity(normalizedUri) ?: normalizedUri
        return hasPersistedWritePermissionForIdentity(context, identity)
    }

    fun createDefaultRoot(context: Context): ManagedDownloadRootHandle.FileRoot {
        val dir = defaultRootDirectory(context).apply { mkdirs() }
        return ManagedDownloadRootHandle.FileRoot(dir)
    }

    fun clearCache() {
        cachedTreeRoot = null
    }

    private fun resolveCachedTreeRoot(
        normalizedUri: String,
        identity: String
    ): ManagedDownloadRootHandle.TreeRoot? {
        val now = System.currentTimeMillis()
        val cachedRoot = cachedTreeRoot
            ?.takeIf { it.identity == identity && it.normalizedUri == normalizedUri }
            ?: return null
        if (now - cachedRoot.validatedAtMs <= TREE_ROOT_CACHE_VALIDATE_INTERVAL_MS) {
            return cachedRoot.root
        }
        return cachedRoot.root
            .takeIf { isAccessibleDirectory(it.tree) }
            ?.also {
                cachedTreeRoot = cachedRoot.copy(validatedAtMs = now)
            }
    }

    private fun hasPersistedWritePermissionForIdentity(
        context: Context,
        identity: String
    ): Boolean {
        val permissions = runCatching {
            context.contentResolver.persistedUriPermissions
        }.getOrNull() ?: return false
        return permissions.any { permission ->
            permission.isReadPermission &&
                permission.isWritePermission &&
                ManagedDownloadDirectoryIdentity.directoryIdentity(permission.uri.toString()) == identity
        }
    }

    private fun isAccessibleDirectory(directory: DocumentFile): Boolean {
        return runCatching { directory.exists() && directory.isDirectory }
            .getOrDefault(false)
    }

    private fun invalidateCachedTreeRoot(normalizedUri: String, identity: String) {
        if (cachedTreeRoot?.normalizedUri == normalizedUri && cachedTreeRoot?.identity == identity) {
            cachedTreeRoot = null
        }
    }

    private fun requiresPersistedPermission(normalizedUri: String): Boolean {
        return runCatching { normalizedUri.toUri().authority }
            .getOrNull() == EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY
    }

    companion object {
        const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
            "com.android.externalstorage.documents"

        internal fun defaultRootDirectory(context: Context): File {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            return File(baseDir, ROOT_DIR_NAME)
        }
    }

    private fun rememberCachedTreeRoot(
        normalizedUri: String,
        identity: String,
        root: ManagedDownloadRootHandle.TreeRoot
    ): ManagedDownloadRootHandle.TreeRoot {
        cachedTreeRoot = CachedTreeRoot(
            identity = identity,
            normalizedUri = normalizedUri,
            root = root,
            validatedAtMs = System.currentTimeMillis()
        )
        return root
    }

    private data class CachedTreeRoot(
        val identity: String,
        val normalizedUri: String,
        val root: ManagedDownloadRootHandle.TreeRoot,
        val validatedAtMs: Long
    )
}
