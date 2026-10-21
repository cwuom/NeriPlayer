package moe.ouom.neriplayer.data.local.media.source

import android.content.Context
import android.content.UriPermission
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.File
import java.util.Locale
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.logging.playbackLogReference
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.curation.FolderArtworkLocator
import moe.ouom.neriplayer.data.local.media.curation.StorageDocumentPath
import moe.ouom.neriplayer.data.local.media.preferredLocalMediaReference
import moe.ouom.neriplayer.data.model.SongItem

/** 仅为当前歌曲快照保留已验证的文件来源，不注册可被其他歌曲借用的别名 */
class ResolvedLocalMediaSource private constructor(
    val reference: String,
    private val anchorReference: String,
    private val sourceSong: SongItem
) {
    fun anchorFor(song: SongItem, actualReference: String?): String? = anchorReference.takeIf {
        sourceSong == song && reference == actualReference &&
            anchorReference == preferredLocalMediaReference(song.localFilePath, song.mediaUri)
    }

    // 原声明只用于阻止替换，不能证明当前引用属于手动文件
    fun unverifiedDeclaredReferenceFor(song: SongItem, actualReference: String?): String? {
        if (sourceSong != song || reference != actualReference) return null
        return preferredLocalMediaReference(song.localFilePath, song.mediaUri)?.takeIf {
            it != reference && it != anchorReference
        }
    }

    companion object {
        fun direct(song: SongItem, reference: String): ResolvedLocalMediaSource =
            ResolvedLocalMediaSource(reference, reference, song)

        fun recoverFromFolderGrant(context: Context, song: SongItem): ResolvedLocalMediaSource? {
            if (!LocalSongSupport.isLocalSong(song, context)) return null
            val anchor = preferredLocalMediaReference(song.localFilePath, song.mediaUri) ?: return null
            val file = referenceFile(context, anchor) ?: return null
            if (hasConflictingDeclaredFile(song, file)) return null
            val path = runCatching { FolderArtworkLocator.storagePathOf(file.canonicalPath) }
                .getOrNull() ?: return null
            val reference = readableGrantedReference(context, song, path) ?: return null
            return ResolvedLocalMediaSource(reference, anchor, song)
        }

        private fun readableGrantedReference(context: Context, song: SongItem, path: StorageDocumentPath): String? {
            val permissions = context.contentResolver.persistedUriPermissions.orEmpty()
            logFolderGrants(song, permissions)
            // 只恢复同一规范路径的树内文档，目录授权本身不能证明另一个音频别名
            return permissions.asSequence()
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
                .firstOrNull { canReadDocument(context, it) }
        }

        private fun canReadDocument(context: Context, reference: String): Boolean = runCatching {
            context.contentResolver.openAssetFileDescriptor(reference.toUri(), "r")?.use { true } == true
        }.getOrDefault(false)

        private fun logFolderGrants(song: SongItem, permissions: List<UriPermission>) {
            NPLogger.d(
                "NERI-LocalPlayback",
                "LocalPlaybackTrace stage=folder_grants id=${song.id} count=${permissions.size} " +
                    "grants=${permissions.take(8).map { permission ->
                        "${playbackLogReference(permission.uri.toString())}:read=${permission.isReadPermission}:write=${permission.isWritePermission}"
                    }}"
            )
        }

        fun hasConflictingDeclaredFile(context: Context, song: SongItem): Boolean {
            val anchor = preferredLocalMediaReference(song.localFilePath, song.mediaUri) ?: return false
            val file = referenceFile(context, anchor) ?: return false
            return hasConflictingDeclaredFile(song, file)
        }

        private fun hasConflictingDeclaredFile(song: SongItem, anchorFile: File): Boolean =
            listOf(song.localFilePath, song.mediaUri).mapNotNull(::directFile).any { declared ->
                runCatching { declared.canonicalFile != anchorFile }.getOrDefault(true)
            }

        private fun referenceFile(context: Context, reference: String): File? = runCatching {
            val file = directFile(reference) ?: contentReferenceFile(context, reference.toUri())
            file?.canonicalFile
        }.getOrNull()

        private fun contentReferenceFile(context: Context, uri: Uri): File? {
            if (!uri.scheme.equals("content", ignoreCase = true)) return null
            return when (uri.authority) {
                "com.android.externalstorage.documents" -> externalStorageFile(uri)
                "media" -> mediaStoreFile(context, uri)
                else -> null
            }
        }

        private fun externalStorageFile(uri: Uri): File? {
            val documentId = DocumentsContract.getDocumentId(uri)
            val volume = documentId.substringBefore(':')
            val relative = documentId.substringAfter(':', "").takeIf(String::isNotBlank) ?: return null
            val root = externalStorageRoot(volume) ?: return null
            val file = File(root, relative).canonicalFile
            val path = FolderArtworkLocator.storagePathOf(file.path) ?: return null
            return file.takeIf { path.volumeId.equals(volume, ignoreCase = true) && path.relativePath == relative }
        }

        private fun externalStorageRoot(volume: String): String? = when {
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) ->
                "/storage/${volume.uppercase(Locale.ROOT)}"
            else -> null
        }

        private fun mediaStoreFile(context: Context, uri: Uri): File? {
            if (!isMediaStoreAudioItemPath(uri.pathSegments)) return null
            return context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                singleMediaStoreFile(cursor)
            }
        }

        private fun isMediaStoreAudioItemPath(segments: List<String>): Boolean {
            if (segments.size != 4 || segments[1] != "audio" || segments[2] != "media") return false
            val id = segments[3].toLongOrNull() ?: return false
            return id >= 0 && id.toString() == segments[3]
        }

        private fun singleMediaStoreFile(cursor: Cursor): File? {
            val column = cursor.getColumnIndex("_data")
            if (!cursor.moveToFirst() || column < 0) return null
            val path = cursor.getString(column)?.takeIf { it.startsWith('/') } ?: return null
            return if (cursor.moveToNext()) null else File(path)
        }

        private fun directFile(reference: String?): File? = when {
            reference == null -> null
            reference.startsWith('/') -> File(reference)
            reference.startsWith("file:", ignoreCase = true) ->
                runCatching { reference.toUri().path?.let(::File) }.getOrNull()
            else -> null
        }
    }
}
