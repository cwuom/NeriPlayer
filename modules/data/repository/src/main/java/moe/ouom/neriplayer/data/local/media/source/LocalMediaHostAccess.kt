package moe.ouom.neriplayer.data.local.media.source

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.download.naming.ParsedManagedDownloadFileName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadLibraryEntry
import moe.ouom.neriplayer.data.model.download.DownloadLibrarySnapshot

interface LocalMediaDownloadAccess {
    val snapshotCacheFileName: String
    fun defaultRootDirectory(context: Context): File
    fun configuredDirectoryUri(): String?
    fun currentDownloadFileNameTemplate(): String?
    fun cachedDownloadLibrarySnapshot(context: Context, restorePersisted: Boolean = true): DownloadLibrarySnapshot?
    suspend fun buildDownloadLibrarySnapshot(context: Context, forceRefresh: Boolean = false): DownloadLibrarySnapshot
    fun peekDownloadedAudio(song: SongItem): DownloadLibraryEntry?
    fun peekCoverReference(audio: DownloadLibraryEntry): String?
    fun toPlayableUri(reference: String?): String?
    fun isKnownManagedDownloadDocumentId(documentId: String, treeDocumentId: String?): Boolean
    fun isManagedDownloadRelativePath(relativePath: String?, treeDocumentId: String?): Boolean
    fun isLikelyManagedDownloadSong(context: Context, song: SongItem): Boolean
    fun resolveManagedAudioDisplayName(context: Context, song: SongItem): String?
    fun candidateFileNameTemplates(activeTemplate: String? = null): List<String>
    fun parseBaseName(baseName: String, template: String?): ParsedManagedDownloadFileName?
}

interface LocalMediaCoverAccess {
    fun peekLocalCoverUri(song: SongItem): String?
    fun getLocalCoverUri(context: Context, song: SongItem, resolveLocalMediaFallback: Boolean = true): String?
}

fun interface CrashLogCleanup {
    fun clear(context: Context): Boolean
}

object LocalMediaHostAccess {
    private data class Access(
        val downloads: LocalMediaDownloadAccess,
        val covers: LocalMediaCoverAccess,
        val crashLogs: CrashLogCleanup
    )

    @Volatile
    private var access: Access? = null

    fun bind(downloads: LocalMediaDownloadAccess, covers: LocalMediaCoverAccess, crashLogs: CrashLogCleanup) {
        access = Access(downloads, covers, crashLogs)
    }

    val downloads: LocalMediaDownloadAccess
        get() = requireAccess().downloads

    val covers: LocalMediaCoverAccess
        get() = requireAccess().covers

    val crashLogs: CrashLogCleanup
        get() = requireAccess().crashLogs

    private fun requireAccess(): Access = checkNotNull(access) {
        "Local media host access has not been bound"
    }
}
