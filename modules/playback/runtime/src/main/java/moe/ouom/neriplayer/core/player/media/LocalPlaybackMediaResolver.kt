package moe.ouom.neriplayer.core.player.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.File
import java.io.RandomAccessFile
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.logging.playbackLogReference
import moe.ouom.neriplayer.core.player.policy.storage.RestorableLocalMediaState
import moe.ouom.neriplayer.core.player.policy.storage.resolveRestorableLocalMediaState
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.curation.FolderArtworkLocator
import moe.ouom.neriplayer.data.local.media.preferredLocalMediaReference
import moe.ouom.neriplayer.data.model.SongItem

internal object LocalPlaybackMediaResolver {
    fun source(song: SongItem, context: Context): String? {
        val preferred = preferredLocalMediaReference(song.localFilePath, song.mediaUri)
        val readable = listOfNotNull(preferred, song.localFilePath, song.mediaUri)
            .distinct()
            .firstOrNull { reference ->
                isReadable(reference, context).also { result ->
                    NPLogger.d(
                        "NERI-LocalPlayback",
                        "LocalPlaybackTrace stage=candidate id=${song.id} readable=$result " +
                            "reference=${playbackLogReference(reference)}"
                    )
                }
            }
            ?: grantedDocumentSource(song, context)
        val message = "LocalPlaybackTrace stage=source id=${song.id} channel=${song.channelId} " +
            "result=${if (readable != null) "readable" else "no_readable_source"} " +
            "mediaUri=${playbackLogReference(song.mediaUri)} " +
            "localPath=${playbackLogReference(song.localFilePath)} " +
            "selected=${playbackLogReference(readable ?: preferred)}"
        if (readable != null) NPLogger.d("NERI-LocalPlayback", message)
        else NPLogger.w("NERI-LocalPlayback", message)
        return readable ?: preferred
    }

    private fun grantedDocumentSource(song: SongItem, context: Context): String? {
        val file = recoverableFile(song, context) ?: return null
        val path = runCatching { FolderArtworkLocator.storagePathOf(file.canonicalPath) }
            .getOrNull() ?: return null
        // 目录授权不代表 MediaStore 授权，旧记录需要恢复同一文件的树内引用
        return context.contentResolver.persistedUriPermissions.orEmpty().also { permissions ->
            NPLogger.d(
                "NERI-LocalPlayback",
                "LocalPlaybackTrace stage=folder_grants id=${song.id} count=${permissions.size} " +
                    "grants=${permissions.take(8).map { permission ->
                        "${playbackLogReference(permission.uri.toString())}:read=${permission.isReadPermission}:write=${permission.isWritePermission}"
                    }}"
            )
        }.asSequence()
            .filter { it.isReadPermission }
            .map { it.uri }
            .filter { it.authority == "com.android.externalstorage.documents" }
            .filter { tree ->
                runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                    ?.let(path::isInside) == true
            }
            .mapNotNull { tree ->
                runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(tree, path.documentId).toString()
                }.getOrNull()
            }
            .firstOrNull { isReadable(it, context) }
    }

    private fun recoverableFile(song: SongItem, context: Context): File? {
        for (reference in listOf(song.localFilePath, song.mediaUri)) {
            directFile(reference)?.let { return it }
        }
        val uri = song.mediaUri?.let(::parseUri) ?: return null
        return runCatching { LocalMediaSupport.resolveLocalFile(context, uri) }.getOrNull()
    }

    private fun directFile(reference: String?): File? = when {
        reference == null -> null
        reference.startsWith('/') -> File(reference)
        reference.startsWith("file:", ignoreCase = true) -> parseUri(reference)?.path?.let(::File)
        else -> null
    }

    fun playableUrl(mediaUri: String?): String? {
        val uriString = mediaUri?.takeIf { it.isNotBlank() } ?: return null
        return if (uriString.startsWith("/")) fileUrl(uriString) else playableParsedUrl(uriString)
    }

    private fun playableParsedUrl(uriString: String): String? {
        val parsed = parseUri(uriString) ?: return null
        return if (parsed.scheme.isNullOrEmpty()) fileUrl(uriString) else uriString
    }

    fun isReadable(mediaUri: String?, context: Context): Boolean {
        val uriString = mediaUri?.takeIf { it.isNotBlank() } ?: return false
        if (uriString.startsWith("/")) return canOpenFile(File(uriString))
        return isReadableParsedUri(uriString, context)
    }

    private fun isReadableParsedUri(uriString: String, context: Context): Boolean {
        val uri = parseUri(uriString) ?: return false
        return when (uri.scheme?.lowercase()) {
            null, "" -> canOpenFile(File(uriString))
            "file" -> isReadableFileUri(uri)
            "content", "android.resource" -> canReadProviderUri(uri, context)
            else -> false
        }
    }

    fun restorableState(mediaUri: String?, context: Context): RestorableLocalMediaState {
        val uriString = mediaUri?.takeIf { it.isNotBlank() }
            ?: return RestorableLocalMediaState.REVOKED
        if (uriString.startsWith("/")) {
            return restorableFileState(null, File(uriString))
        }
        return restorableParsedUri(uriString, context)
    }

    private fun restorableParsedUri(uriString: String, context: Context): RestorableLocalMediaState {
        val uri = parseUri(uriString)
            ?: return RestorableLocalMediaState.REVOKED
        return when (uri.scheme?.lowercase()) {
            null, "" -> restorableFileState(uri.scheme, File(uriString))
            "file" -> restorableFileState(uri.scheme, uri.path?.let(::File))
            "content" -> restorableContentState(uri, context)
            else -> resolveRestorableLocalMediaState(scheme = uri.scheme)
        }
    }

    private fun restorableFileState(scheme: String?, file: File?): RestorableLocalMediaState =
        resolveRestorableLocalMediaState(
            scheme = scheme,
            localFileReadable = file?.let(::canOpenFile) == true
        )

    private fun restorableContentState(uri: Uri, context: Context): RestorableLocalMediaState =
        resolveRestorableLocalMediaState(
            scheme = uri.scheme,
            hasPersistedReadPermission = hasPersistedReadPermission(uri, context),
            hasCurrentReadPermission = hasCurrentReadPermission(uri, context)
        )

    private fun hasPersistedReadPermission(uri: Uri, context: Context): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && it.uri == uri
        }

    private fun hasCurrentReadPermission(uri: Uri, context: Context): Boolean =
        context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

    fun isRestorable(mediaUri: String?, context: Context): Boolean =
        restorableState(mediaUri, context) != RestorableLocalMediaState.REVOKED

    fun isRestorableSong(song: SongItem, context: Context): Boolean {
        val preferred = preferredLocalMediaReference(song.localFilePath, song.mediaUri)
        return listOfNotNull(preferred, song.localFilePath, song.mediaUri)
            .distinct()
            .any { isRestorable(it, context) } || grantedDocumentSource(song, context) != null
    }

    fun sanitizeRestoredPlaylist(playlist: List<SongItem>, context: Context): List<SongItem> =
        playlist.filter { song ->
            !LocalSongSupport.isLocalSong(song, context) || isRestorableSong(song, context)
        }

    private fun canOpenFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return readableProbe("file_probe", file.absolutePath) {
            RandomAccessFile(file, "r").use { true }
        }
    }

    private fun isReadableFileUri(uri: Uri): Boolean =
        uri.path?.let(::File)?.let(::canOpenFile) == true

    private fun canReadProviderUri(uri: Uri, context: Context): Boolean =
        readableProbe("provider_probe", uri.toString()) {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }

    private fun readableProbe(stage: String, reference: String, probe: () -> Boolean): Boolean =
        runCatching(probe).onFailure { logProbeFailure(stage, reference, it) }.getOrDefault(false)

    private fun logProbeFailure(stage: String, reference: String, error: Throwable) {
        NPLogger.w(
            "NERI-LocalPlayback",
            "LocalPlaybackTrace stage=$stage result=error errorType=${error.javaClass.name} " +
                "causeType=${error.cause?.javaClass?.name} reference=${playbackLogReference(reference)}"
        )
    }

    private fun parseUri(reference: String): Uri? = runCatching { reference.toUri() }.getOrNull()

    private fun fileUrl(reference: String): String = Uri.fromFile(File(reference)).toString()
}
