package moe.ouom.neriplayer.core.player.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.core.net.toUri
import java.io.File
import java.io.RandomAccessFile
import moe.ouom.neriplayer.core.player.policy.storage.RestorableLocalMediaState
import moe.ouom.neriplayer.core.player.policy.storage.resolveRestorableLocalMediaState
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.preferredLocalMediaReference
import moe.ouom.neriplayer.data.model.SongItem

internal object LocalPlaybackMediaResolver {
    fun source(song: SongItem, context: Context): String? {
        val preferred = preferredLocalMediaReference(song.localFilePath, song.mediaUri)
        return listOfNotNull(preferred, song.localFilePath, song.mediaUri)
            .distinct()
            .firstOrNull { isReadable(it, context) }
            ?: preferred
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
            .any { isRestorable(it, context) }
    }

    fun sanitizeRestoredPlaylist(playlist: List<SongItem>, context: Context): List<SongItem> =
        playlist.filter { song ->
            !LocalSongSupport.isLocalSong(song, context) || isRestorableSong(song, context)
        }

    private fun canOpenFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return runCatching { RandomAccessFile(file, "r").use { true } }.getOrDefault(false)
    }

    private fun isReadableFileUri(uri: Uri): Boolean =
        uri.path?.let(::File)?.let(::canOpenFile) == true

    private fun canReadProviderUri(uri: Uri, context: Context): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun parseUri(reference: String): Uri? = runCatching { reference.toUri() }.getOrNull()

    private fun fileUrl(reference: String): String = Uri.fromFile(File(reference)).toString()
}
