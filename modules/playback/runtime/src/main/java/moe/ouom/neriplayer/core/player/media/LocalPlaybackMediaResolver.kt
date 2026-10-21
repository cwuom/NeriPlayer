package moe.ouom.neriplayer.core.player.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.core.net.toUri
import java.io.File
import java.io.RandomAccessFile
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.logging.playbackLogReference
import moe.ouom.neriplayer.core.player.policy.storage.RestorableLocalMediaState
import moe.ouom.neriplayer.core.player.policy.storage.resolveRestorableLocalMediaState
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.source.ResolvedLocalMediaSource
import moe.ouom.neriplayer.data.local.media.preferredLocalMediaReference
import moe.ouom.neriplayer.data.model.SongItem

internal object LocalPlaybackMediaResolver {
    fun source(song: SongItem, context: Context): String? = sourceContext(song, context)?.reference

    fun sourceContext(song: SongItem, context: Context): ResolvedLocalMediaSource? {
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
        val resolved = resolveReadableSource(song, context, preferred, readable)
        val selected = resolved ?: preferred?.let { ResolvedLocalMediaSource.direct(song, it) }
        logSourceSelection(song, resolved, selected)
        return selected
    }

    private fun resolveReadableSource(
        song: SongItem,
        context: Context,
        preferred: String?,
        readable: String?
    ): ResolvedLocalMediaSource? {
        if (readable == null) return ResolvedLocalMediaSource.recoverFromFolderGrant(context, song)
        if (readable != preferred && ResolvedLocalMediaSource.hasConflictingDeclaredFile(context, song)) return null
        return ResolvedLocalMediaSource.verifiedReadableDeclaredFile(context, song, readable)
            ?: ResolvedLocalMediaSource.direct(song, readable)
    }

    private fun logSourceSelection(
        song: SongItem,
        resolved: ResolvedLocalMediaSource?,
        selected: ResolvedLocalMediaSource?
    ) {
        val message = "LocalPlaybackTrace stage=source id=${song.id} channel=${song.channelId} " +
            "result=${if (resolved != null) "readable" else "no_readable_source"} " +
            "mediaUri=${playbackLogReference(song.mediaUri)} " +
            "localPath=${playbackLogReference(song.localFilePath)} " +
            "selected=${playbackLogReference(selected?.reference)}"
        if (resolved != null) NPLogger.d("NERI-LocalPlayback", message)
        else NPLogger.w("NERI-LocalPlayback", message)
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
            .any { isRestorable(it, context) } || ResolvedLocalMediaSource.recoverFromFolderGrant(context, song) != null
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
