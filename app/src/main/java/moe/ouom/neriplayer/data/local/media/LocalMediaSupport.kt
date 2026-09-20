package moe.ouom.neriplayer.data.local.media

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.local.media/LocalMediaSupport
 * Updated: 2026/3/23
 */


import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.system.Os
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.kyant.taglib.Picture
import com.kyant.taglib.PropertyMap
import com.kyant.taglib.TagLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.metadata.MAX_SOURCE_COVER_BYTES
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey as songStableKey
import moe.ouom.neriplayer.data.local.storage.LocalStorageRootGeneration
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.readBytesLimited
import moe.ouom.neriplayer.util.media.NERI_ORIGINAL_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.NERI_ROMANIZED_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.mergeLyricsForExternalPlayers
import moe.ouom.neriplayer.util.media.standardLyricsMetadataKeys
import moe.ouom.neriplayer.util.media.translatedLyricsMetadataKeys
import moe.ouom.neriplayer.util.network.isFileInsideDirectory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.text.Normalizer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.net.URLConnection
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import androidx.core.net.toUri
import okhttp3.Request

object LocalMediaSupport {
    internal const val TAG = "LocalMediaSupport"
    internal const val CONSECUTIVE_EMPTY_REFRESH_CACHE_LIMIT = 512
    internal val localLyricsPerfLogCount = AtomicInteger()
    internal val lyricExtensions = listOf("lrc", "txt")
    internal val coverFileNames = listOf("cover", "folder", "front")
    internal val imageExtensions = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    internal data class LocalCoverCacheHit(val coverUri: String?)
    internal data class FilePathCacheHit(val path: String?)
    internal data class LocalLyricsCacheEntry(
        val value: LocalLyricsScanMetadata,
        val cachedAtMs: Long
    )
    internal val localLyricsLookupCache = object : LinkedHashMap<String, LocalLyricsCacheEntry>(
        LOCAL_LYRICS_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LocalLyricsCacheEntry>
        ): Boolean {
            return size > LOCAL_LYRICS_LOOKUP_CACHE_LIMIT
        }
    }
    internal data class DocumentChildrenCacheEntry(
        val children: List<DocumentChild>,
        val cachedAtMs: Long,
        val isComplete: Boolean
    ) {
        fun isFresh(nowMs: Long): Boolean {
            val ttlMs = if (isComplete) {
                DOCUMENT_CHILDREN_CACHE_TTL_MS
            } else {
                DOCUMENT_CHILDREN_INCOMPLETE_CACHE_TTL_MS
            }
            return nowMs - cachedAtMs <= ttlMs
        }
    }
    internal val documentChildrenCache = object : LinkedHashMap<String, DocumentChildrenCacheEntry>(
        DOCUMENT_CHILDREN_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DocumentChildrenCacheEntry>
        ): Boolean {
            return size > DOCUMENT_CHILDREN_CACHE_LIMIT
        }
    }
    internal val consecutiveEmptyDocumentRefreshes = ConcurrentHashMap<String, Int>()
    internal data class DocumentNavigationCacheEntry(
        val navigation: LocalDocumentNavigation?,
        val cachedAtMs: Long
    )
    internal val documentNavigationCache = object : LinkedHashMap<String, DocumentNavigationCacheEntry>(
        DOCUMENT_NAVIGATION_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DocumentNavigationCacheEntry>
        ): Boolean {
            return size > DOCUMENT_NAVIGATION_CACHE_LIMIT
        }
    }
    internal val localCoverLookupCache = object : LinkedHashMap<String, String?>(
        LOCAL_COVER_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
            return size > LOCAL_COVER_LOOKUP_CACHE_LIMIT
        }
    }
    internal val nearbyCoverLookupCache = object : LinkedHashMap<String, String?>(
        NEARBY_COVER_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
            return size > NEARBY_COVER_LOOKUP_CACHE_LIMIT
        }
    }
    internal val directoryCoverLookupCache = object : LinkedHashMap<String, String?>(
        DIRECTORY_COVER_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
            return size > DIRECTORY_COVER_LOOKUP_CACHE_LIMIT
        }
    }
    internal data class DirectoryFileIndex(
        val directoryLastModifiedMs: Long,
        val cachedAtElapsedMs: Long,
        val files: List<File>,
        val directories: List<File>
    )
    internal val directoryFileIndexCache = object : LinkedHashMap<String, DirectoryFileIndex>(
        DIRECTORY_FILE_INDEX_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DirectoryFileIndex>
        ): Boolean {
            return size > DIRECTORY_FILE_INDEX_CACHE_LIMIT
        }
    }
    internal val mediaStoreAlbumArtCache = object : LinkedHashMap<String, String?>(
        NEARBY_COVER_LOOKUP_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
            return size > NEARBY_COVER_LOOKUP_CACHE_LIMIT
        }
    }

    internal data class AudioTrackTechInfo(
        val audioMimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channelCount: Int?,
        val durationMs: Long?
    )

    internal data class RetrieverTextMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val composer: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val durationMs: Long? = null,
        val mimeType: String? = null,
        val bitrateKbps: Int? = null,
        val sampleRateHz: Int? = null
    )

    internal data class ResolvedInspectableLocalMedia(
        val queried: QueriedContentInfo,
        val resolvedPath: String?,
        val file: File?,
        val playableUri: Uri,
        val displayName: String,
        val fallbackTitle: String,
        val fileExtension: String?
    )

    internal data class ContainerMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val composer: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null
    )

    internal data class TagLibMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val composer: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val durationMs: Long? = null,
        val bitrateKbps: Int? = null,
        val sampleRateHz: Int? = null,
        val channelCount: Int? = null,
        val lyrics: String? = null,
        val translatedLyrics: String? = null,
        val romanizedLyrics: String? = null,
        val coverBytes: ByteArray? = null,
        val sourceStableKey: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TagLibMetadata) return false

            return title == other.title &&
                artist == other.artist &&
                album == other.album &&
                albumArtist == other.albumArtist &&
                composer == other.composer &&
                genre == other.genre &&
                year == other.year &&
                trackNumber == other.trackNumber &&
                discNumber == other.discNumber &&
                durationMs == other.durationMs &&
                bitrateKbps == other.bitrateKbps &&
                sampleRateHz == other.sampleRateHz &&
                channelCount == other.channelCount &&
                lyrics == other.lyrics &&
                translatedLyrics == other.translatedLyrics &&
                romanizedLyrics == other.romanizedLyrics &&
                sourceStableKey == other.sourceStableKey &&
                (coverBytes?.contentEquals(other.coverBytes) ?: (other.coverBytes == null))
        }

        override fun hashCode(): Int {
            var result = title?.hashCode() ?: 0
            result = 31 * result + (artist?.hashCode() ?: 0)
            result = 31 * result + (album?.hashCode() ?: 0)
            result = 31 * result + (albumArtist?.hashCode() ?: 0)
            result = 31 * result + (composer?.hashCode() ?: 0)
            result = 31 * result + (genre?.hashCode() ?: 0)
            result = 31 * result + (year ?: 0)
            result = 31 * result + (trackNumber ?: 0)
            result = 31 * result + (discNumber ?: 0)
            result = 31 * result + (durationMs?.hashCode() ?: 0)
            result = 31 * result + (bitrateKbps ?: 0)
            result = 31 * result + (sampleRateHz ?: 0)
            result = 31 * result + (channelCount ?: 0)
            result = 31 * result + (lyrics?.hashCode() ?: 0)
            result = 31 * result + (translatedLyrics?.hashCode() ?: 0)
            result = 31 * result + (romanizedLyrics?.hashCode() ?: 0)
            result = 31 * result + (sourceStableKey?.hashCode() ?: 0)
            result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    internal data class QuickLocalMetadataSelection(
        val title: String,
        val artist: String,
        val album: String,
        val usesFallbackAlbum: Boolean,
        val durationMs: Long
    )

    internal fun selectQuickLocalMetadata(
        title: String,
        queriedArtist: String?,
        queriedAlbum: String?,
        queriedDurationMs: Long?,
        unknownArtistLabel: String,
        defaultAlbumLabel: String
    ): QuickLocalMetadataSelection {
        return this.selectQuickLocalMetadataImpl(title, queriedArtist, queriedAlbum, queriedDurationMs, unknownArtistLabel, defaultAlbumLabel)
    }


    fun inspect(context: Context, song: SongItem): LocalMediaDetails? {
        return this.inspectImpl(context, song)
    }


    fun inspectMetadataOnly(
        context: Context,
        song: SongItem,
        resolveCoverFallback: Boolean = true
    ): LocalMediaDetails? {
        return this.inspectMetadataOnlyImpl(context, song, resolveCoverFallback)
    }


    internal suspend fun writeEditableMetadata(
        context: Context,
        song: SongItem,
        coverReference: String? = song.customCoverUrl,
        writeCover: Boolean = coverReference != null,
        writeLyrics: Boolean = false,
        embeddedPropertyMapOverride: PropertyMap? = null,
        requiredEmbeddedPropertyKeys: Set<String> = emptySet(),
        persistCompanionSidecars: Boolean = true
    ): LocalMediaMetadataWriteOutcome {
        return this.writeEditableMetadataImpl(context, song, coverReference, writeCover, writeLyrics, embeddedPropertyMapOverride, requiredEmbeddedPropertyKeys, persistCompanionSidecars)
    }


    internal suspend fun writeEditableMetadataInternal(
        context: Context,
        song: SongItem,
        coverReference: String? = song.customCoverUrl,
        writeCover: Boolean = coverReference != null,
        writeLyrics: Boolean = false,
        embeddedPropertyMapOverride: PropertyMap? = null,
        requiredEmbeddedPropertyKeys: Set<String> = emptySet(),
        persistCompanionSidecars: Boolean = true,
        candidates: List<Uri>
    ): LocalMediaMetadataWriteOutcome = withContext(Dispatchers.IO) {
        val startedAtMs = SystemClock.elapsedRealtime()
        if (candidates.isEmpty()) {
            return@withContext LocalMediaMetadataWriteOutcome.NOT_WRITABLE
        }

        var fallbackOutcome = LocalMediaMetadataWriteOutcome.NOT_WRITABLE
        candidates.forEach { sourceUri ->
            currentCoroutineContext().ensureActive()
            val localFile = if (persistCompanionSidecars) {
                resolveEditableSidecarFile(context, sourceUri)
            } else {
                null
            }
            val persistAvailableSidecars = persistCompanionSidecars &&
                !isStandaloneContentMetadataTarget(context, sourceUri, localFile)
            val stagedAttempted = shouldUseTransactionalStagedWrite(sourceUri)
            val companionTransaction = if (persistAvailableSidecars) {
                LocalMediaCompanionTransaction(context, sourceUri.toString())
            } else null
            val writeTransaction = if (stagedAttempted) {
                writeEditableMetadataThroughStagedContentCopy(
                    context = context,
                    song = song,
                    sourceUri = sourceUri,
                    coverReference = coverReference,
                    writeCover = writeCover,
                    writeLyrics = writeLyrics,
                    fallbackOutcome = LocalMediaMetadataWriteOutcome.FAILED,
                    embeddedPropertyMapOverride = embeddedPropertyMapOverride,
                    requiredEmbeddedPropertyKeys = requiredEmbeddedPropertyKeys,
                    companionTransaction = companionTransaction
                )
            } else {
                writeEditableMetadataDirectTransaction(
                    context = context,
                    song = song,
                    sourceUri = sourceUri,
                    coverReference = coverReference,
                    writeCover = writeCover,
                    writeLyrics = writeLyrics,
                    embeddedPropertyMapOverride = embeddedPropertyMapOverride,
                    requiredEmbeddedPropertyKeys = requiredEmbeddedPropertyKeys
                )
            }
            val outcome = writeTransaction.outcome
            var embeddedTransactionSettled = outcome != LocalMediaMetadataWriteOutcome.SUCCESS
            var companionTransactionSettled = companionTransaction == null
            try {
                if (!persistAvailableSidecars) {
                    var finalOutcome = outcome
                    if (outcome == LocalMediaMetadataWriteOutcome.SUCCESS) {
                        val committed = runCatching {
                            writeTransaction.commit?.invoke()
                        }.onFailure { error ->
                            logEditableMetadataFailure("commit_recovery_record", sourceUri, error)
                        }.isSuccess
                        if (!committed) {
                            writeTransaction.rollback?.invoke()
                            finalOutcome = LocalMediaMetadataWriteOutcome.FAILED
                        }
                        embeddedTransactionSettled = true
                    }
                    logEditableMetadataWriteTiming(
                        sourceUri = sourceUri,
                        startedAtMs = startedAtMs,
                        outcome = finalOutcome,
                        mode = if (stagedAttempted) "staged_embedded" else "direct_embedded"
                    )
                    if (finalOutcome == LocalMediaMetadataWriteOutcome.SUCCESS) {
                        return@withContext finalOutcome
                    }
                    fallbackOutcome = selectEditableMetadataWriteFallback(
                        current = fallbackOutcome,
                        candidate = finalOutcome
                    )
                    return@forEach
                }
                // 嵌入标签和侧载文件是两条独立的恢复路径。TagLib 暂不支持某种
                // 容器时仍必须保存 Lyrics 和 npmeta，不能让嵌入失败阻断侧载重建
                // MediaStore 路径可能能通过 stat 但仍会被 scoped storage 拒绝读取
                // 这类来源始终沿 SAF 引用写入
                val displayName = song.localFileName
                    ?.takeIf(String::isNotBlank)
                    ?: sourceUri.lastPathSegment.orEmpty()
                val knownSidecarReferences = resolveContentSidecarReferences(
                    context = context,
                    sourceUri = sourceUri,
                    file = localFile,
                    displayName = displayName,
                )
                companionTransaction?.initializeSidecarsOnly()
                val lyricsSidecarWritten = if (writeLyrics) {
                    currentCoroutineContext().ensureActive()
                    writeLocalLyricsSidecars(
                        context = context,
                        sourceUri = sourceUri,
                        file = localFile,
                        displayName = displayName,
                        song = song,
                        knownReferences = knownSidecarReferences.lyricReferences,
                        companionTransaction = companionTransaction
                    )
                } else {
                    true
                }
                val coverSidecarWritten = if (writeCover) {
                    currentCoroutineContext().ensureActive()
                    writeLocalCoverSidecar(
                        context = context,
                        sourceUri = sourceUri,
                        file = localFile,
                        displayName = displayName,
                        coverReference = coverReference,
                        stableIdentityKey = editableMetadataSourceStableKey(song),
                        companionTransaction = companionTransaction
                    )
                } else {
                    true
                }
                val metadataCoverReference = if (writeCover && !coverReference.isNullOrBlank()) {
                    findNearbyCoverReference(
                        context = context,
                        uri = sourceUri,
                        file = localFile,
                        displayName = displayName
                    ) ?: coverReference
                } else {
                    null
                }
                val metadataSidecarWritten = writeLocalLyricsMetadata(
                    context = context,
                    sourceUri = sourceUri,
                    file = localFile,
                    displayName = displayName,
                    song = song,
                    knownReference = knownSidecarReferences.metadataReference,
                    writeFullMetadata = true,
                    writeLyricFields = writeLyrics,
                    coverReference = metadataCoverReference,
                    clearCoverReference = writeCover && coverReference.isNullOrBlank(),
                    companionTransaction = companionTransaction
                )
                val sidecarsWritten = lyricsSidecarWritten && coverSidecarWritten &&
                    metadataSidecarWritten
                var finalOutcome = combineEditableMetadataWriteOutcome(
                    directOutcome = outcome,
                    lyricsSidecarWritten = lyricsSidecarWritten && metadataSidecarWritten,
                    coverSidecarWritten = coverSidecarWritten,
                    allowSidecarAuthoritativeFallback = embeddedPropertyMapOverride == null
                )
                if (!sidecarsWritten) {
                    companionTransaction?.rollback() ?: writeTransaction.rollback?.invoke()
                    embeddedTransactionSettled = true
                    companionTransactionSettled = true
                    NPLogger.w(TAG, "write local metadata sidecar failed for $sourceUri")
                }
                if (sidecarsWritten && (finalOutcome == LocalMediaMetadataWriteOutcome.SUCCESS ||
                        finalOutcome == LocalMediaMetadataWriteOutcome.SIDECAR_ONLY)) {
                    val committed = runCatching {
                        companionTransaction?.commit() ?: writeTransaction.commit?.invoke()
                    }.onFailure { error ->
                        logEditableMetadataFailure("commit_recovery_record", sourceUri, error)
                    }.isSuccess
                    if (!committed) {
                        companionTransaction?.rollback() ?: writeTransaction.rollback?.invoke()
                        finalOutcome = LocalMediaMetadataWriteOutcome.FAILED
                    }
                    embeddedTransactionSettled = true
                    companionTransactionSettled = true
                }
                logEditableMetadataWriteTiming(
                    sourceUri = sourceUri,
                    startedAtMs = startedAtMs,
                    outcome = finalOutcome,
                    mode = if (stagedAttempted) "staged" else "direct"
                )
                if (finalOutcome == LocalMediaMetadataWriteOutcome.SUCCESS) {
                    return@withContext finalOutcome
                }
                fallbackOutcome = selectEditableMetadataWriteFallback(
                    current = fallbackOutcome,
                    candidate = finalOutcome
                )
            } finally {
                if (!companionTransactionSettled) {
                    companionTransaction?.rollback()
                } else if (!embeddedTransactionSettled) {
                    writeTransaction.rollback?.invoke()
                }
                if (companionTransaction != null) {
                    clearLyricsLookupCache()
                    clearCoverLookupCache()
                }
            }
        }
        logEditableMetadataWriteTiming(
            sourceUri = candidates.lastOrNull(),
            startedAtMs = startedAtMs,
            outcome = fallbackOutcome,
            mode = "fallback"
        )
        fallbackOutcome
    }



    internal suspend fun writeLocalLyricsSidecars(
        context: Context,
        song: SongItem
    ): Boolean = withContext(Dispatchers.IO) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val candidates = editableLocalMediaUriCandidates(context, song)
        if (candidates.isEmpty()) return@withContext false
        val written = candidates.any { sourceUri ->
            currentCoroutineContext().ensureActive()
            val file = resolveEditableSidecarFile(context, sourceUri)
            writeLocalLyricsSidecars(
                context = context,
                sourceUri = sourceUri,
                file = file,
                displayName = song.localFileName
                    ?.takeIf(String::isNotBlank)
                    ?: sourceUri.lastPathSegment.orEmpty(),
                song = song
            )
        }
        if (written) clearLyricsLookupCache()
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        val message = "local lyric sidecar write finished: song=${song.name}, " +
            "written=$written, candidates=${candidates.size}, elapsedMs=$elapsedMs"
        if (elapsedMs >= EDITABLE_METADATA_WRITE_BUDGET_MS) {
            NPLogger.w(TAG, "$message, overBudget=true")
        } else {
            NPLogger.d(TAG, "$message, overBudget=false")
        }
        written
    }

    internal suspend fun writeLocalMetadataSidecar(
        context: Context,
        song: SongItem,
        writeLyrics: Boolean = false,
        coverReference: String? = null,
        clearCoverReference: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val candidates = editableLocalMediaUriCandidates(context, song)
        if (candidates.isEmpty()) return@withContext false
        val written = candidates.any { sourceUri ->
            currentCoroutineContext().ensureActive()
            val file = resolveEditableSidecarFile(context, sourceUri)
            val displayName = song.localFileName
                ?.takeIf(String::isNotBlank)
                ?: sourceUri.lastPathSegment.orEmpty()
            val references = resolveContentSidecarReferences(
                context = context,
                sourceUri = sourceUri,
                file = file,
                displayName = displayName
            )
            val knownReference = references.metadataReference
            val effectiveCoverReference = if (
                !coverReference.isNullOrBlank() && !clearCoverReference
            ) {
                findNearbyCoverReference(
                    context = context,
                    uri = sourceUri,
                    file = file,
                    displayName = displayName
                ) ?: coverReference
            } else {
                coverReference
            }
            writeLocalLyricsMetadata(
                context = context,
                sourceUri = sourceUri,
                file = file,
                displayName = displayName,
                song = song,
                knownReference = knownReference,
                writeFullMetadata = true,
                writeLyricFields = writeLyrics,
                coverReference = effectiveCoverReference,
                clearCoverReference = clearCoverReference
            )
        }
        if (written) {
            clearLyricsLookupCache()
        }
        written
    }

    /**
     * 检查歌词侧载是否被外部删除，编辑器保存时需要据此重建文件
     */
    internal fun needsLyricSidecarRepair(
        context: Context,
        song: SongItem
    ): Boolean {
        return this.needsLyricSidecarRepairImpl(context, song)
    }


    internal fun shouldRebuildLyricSidecars(
        expectedOriginal: Boolean,
        expectedTranslated: Boolean,
        expectedRomanized: Boolean,
        hasOriginalSidecar: Boolean,
        hasTranslatedSidecar: Boolean,
        hasRomanizedSidecar: Boolean
    ): Boolean {
        return this.shouldRebuildLyricSidecarsImpl(expectedOriginal, expectedTranslated, expectedRomanized, hasOriginalSidecar, hasTranslatedSidecar, hasRomanizedSidecar)
    }


    internal suspend fun writeLocalCoverSidecar(
        context: Context,
        song: SongItem,
        coverReference: String?,
        writeCover: Boolean,
        stableIdentityKey: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val candidates = editableLocalMediaUriCandidates(context, song)
        if (candidates.isEmpty() || !writeCover) return@withContext true
        val resolvedStableIdentityKey = stableIdentityKey
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: editableMetadataSourceStableKey(song)
        candidates.any { sourceUri ->
            val file = resolveEditableSidecarFile(context, sourceUri)
            writeLocalCoverSidecar(
                context = context,
                sourceUri = sourceUri,
                file = file,
                displayName = song.localFileName
                    ?.takeIf(String::isNotBlank)
                    ?: sourceUri.lastPathSegment.orEmpty(),
                coverReference = coverReference,
                stableIdentityKey = resolvedStableIdentityKey
            )
        }.also { written ->
            if (written) clearCoverLookupCache()
        }
    }







    internal fun shouldSkipLocalCoverSidecar(sourceReference: String, file: File?): Boolean {
        val authority = sourceReference
            .substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
        return file == null && isMediaStoreAuthority(authority)
    }







    internal fun resolveLocalLyricsTargetDirectory(
        file: File,
        nearby: NearbyLyricFiles,
        legacyRoot: File = File(LEGACY_DOWNLOAD_ROOT),
        isLegacyDownload: Boolean = runCatching {
            isFileInsideDirectory(file, legacyRoot)
        }.getOrDefault(false)
    ): File {
        return this.resolveLocalLyricsTargetDirectoryImpl(file, nearby, legacyRoot, isLegacyDownload)
    }


    internal data class DocumentLyricReferenceResolution(
        val references: NearbyLyricReferences,
        val createdReferences: Map<String, DocumentChild>
    )



















    /**
     * 只读取歌词相关字段，避免歌词首屏触发 TagLib、封面和音频轨道解析
     */
    internal fun inspectLyricsFast(
        song: SongItem,
        includeStoredFallback: Boolean = true
    ): LocalLyricsScanMetadata {
        return this.inspectLyricsFastImpl(song, includeStoredFallback)
    }


    internal fun inspectLyricsFast(
        context: Context?,
        song: SongItem,
        includeStoredFallback: Boolean = true,
        includeEmbeddedFallback: Boolean = context != null,
        knownSidecarReferences: LocalKnownSidecarReferences? = null,
        forceRefresh: Boolean = false
    ): LocalLyricsScanMetadata {
        return this.inspectLyricsFastImpl(context, song, includeStoredFallback, includeEmbeddedFallback, knownSidecarReferences, forceRefresh)
    }






    internal fun inspectEmbeddedLyrics(
        context: Context,
        song: SongItem
    ): LocalLyricsScanMetadata? {
        return this.inspectEmbeddedLyricsImpl(context, song)
    }








    internal fun clearLyricsLookupCache() {
        synchronized(localLyricsLookupCache) {
            localLyricsLookupCache.clear()
        }
        invalidateSafReadCaches()
    }

    internal fun invalidateSongAssetCaches(song: SongItem) {
        return this.invalidateSongAssetCachesImpl(song)
    }


    internal fun clearCoverLookupCache() {
        return this.clearCoverLookupCacheImpl()
    }


    internal fun invalidateSafReadCaches() {
        return this.invalidateSafReadCachesImpl()
    }










    internal fun shouldUseTransactionalStagedWrite(
        sourceScheme: String?,
        sourcePath: String?
    ): Boolean {
        return this.shouldUseTransactionalStagedWriteImpl(sourceScheme, sourcePath)
    }


    internal fun resolveEditableMediaExtension(song: SongItem, sourceUri: Uri): String =
        resolveEditableMediaExtension(song, sourceUri.lastPathSegment)



    internal data class EditableMetadataWriteTransaction(
        val outcome: LocalMediaMetadataWriteOutcome,
        val rollback: (() -> Unit)? = null,
        val commit: (() -> Unit)? = null
    )

    /** 所有外部成品音频先在完整副本上改标签，原地写只用于应用私有暂存副本 */
    internal fun shouldUseTransactionalStagedWrite(sourceUri: Uri): Boolean =
        shouldUseTransactionalStagedWrite(sourceUri.scheme, sourceUri.path)

    internal fun writeEditableMetadataDirect(
        context: Context,
        song: SongItem,
        sourceUri: Uri,
        coverReference: String?,
        writeCover: Boolean,
        writeLyrics: Boolean,
        embeddedPropertyMapOverride: PropertyMap? = null,
        requiredEmbeddedPropertyKeys: Set<String> = emptySet()
    ): LocalMediaMetadataWriteOutcome = writeEditableMetadataDirectTransaction(
        context = context,
        song = song,
        sourceUri = sourceUri,
        coverReference = coverReference,
        writeCover = writeCover,
        writeLyrics = writeLyrics,
        embeddedPropertyMapOverride = embeddedPropertyMapOverride,
        requiredEmbeddedPropertyKeys = requiredEmbeddedPropertyKeys
    ).outcome





    internal fun retryEditableMetadataReadback(
        sourceScheme: String?,
        readBack: () -> Boolean
    ): Boolean {
        return this.retryEditableMetadataReadbackImpl(sourceScheme, readBack)
    }




    fun resolveLocalFile(context: Context, uri: Uri): File? {
        if (!uri.isSupportedLocalMediaUri()) return null
        val resolvedPath = directFilePath(uri)
            ?: queryContentInfo(context, uri).filePath
            ?: resolvePathFromDescriptor(context, uri)
        return resolvedPath?.let(::File)?.takeIf(File::exists)
    }

    fun inspectQuick(
        context: Context,
        uri: Uri,
        includeAudioTrackInfo: Boolean = false
    ): LocalMediaDetails {
        return this.inspectQuickImpl(context, uri, includeAudioTrackInfo)
    }


    /**
     * 只读取 Provider 或音频轨头暴露的时长
     */
    fun resolveDurationFast(context: Context, uri: Uri): Long {
        return this.resolveDurationFastImpl(context, uri)
    }


    fun resolveMediaStoreDurationsFast(
        context: Context,
        sources: List<Uri>
    ): Map<String, Long> {
        return this.resolveMediaStoreDurationsFastImpl(context, sources)
    }


    internal fun Uri.mediaStoreAudioCollectionUri(): Uri? {
        val path = path ?: return null
        val collectionPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (collectionPath.isBlank()) return null
        return buildUpon().path(collectionPath).build()
    }

    internal fun inspectLyricsForScan(
        context: Context,
        uri: Uri
    ): LocalLyricsScanMetadata {
        return this.inspectLyricsForScanImpl(context, uri)
    }


    fun inspectMetadataOnly(
        context: Context,
        uri: Uri,
        resolveCoverFallback: Boolean = true
    ): LocalMediaDetails {
        return this.inspectMetadataOnlyImpl(context, uri, resolveCoverFallback)
    }


    fun resolveCoverUri(context: Context, song: SongItem): String? {
        return this.resolveCoverUriImpl(context, song)
    }


    internal fun resolveCoverReferenceByPriority(
        sidecarReference: String?,
        embeddedReference: String?,
        fallbackReference: String? = null
    ): String? {
        return this.resolveCoverReferenceByPriorityImpl(sidecarReference, embeddedReference, fallbackReference)
    }


    /**
     * 只查找本地 Covers 或同目录封面，不打开音频解析内嵌图片
     */
    fun resolveNearbyCoverUri(context: Context, song: SongItem): String? {
        return this.resolveNearbyCoverUriImpl(context, song)
    }




    /**
     * MediaStore 可以直接提供已经索引的专辑图片，不必打开音频容器
     * 这里只作为快速提示，侧载和内嵌封面仍然是最终依据
     */
    fun peekMediaStoreAlbumArtUri(context: Context, song: SongItem): String? {
        val source = song.localMediaUri() ?: return null
        return peekMediaStoreAlbumArtUri(context, source)
    }

    fun peekMediaStoreAlbumArtUri(context: Context, source: Uri): String? {
        return this.peekMediaStoreAlbumArtUriImpl(context, source)
    }


    fun mediaStoreAlbumArtUri(albumId: Long): String {
        require(albumId > 0L) { "albumId must be positive" }
        return "content://$MEDIA_STORE_AUTHORITY/external/audio/albumart/$albumId"
    }

    /**
     * 列表恢复时只检查已经生成的缩略图, 避免重新打开音频并解析内嵌图片
     */
    fun peekCachedEmbeddedCoverUri(context: Context, song: SongItem): String? {
        return embeddedCoverCacheLookupKeys(song)
            .asSequence()
            .flatMap { key -> sequenceOf(key, "$key#taglib") }
            .firstNotNullOfOrNull { key -> findCachedEmbeddedCover(context, key) }
    }

    internal fun peekCachedEmbeddedCoverUri(context: Context, source: Uri): String? {
        return this.peekCachedEmbeddedCoverUriImpl(context, source)
    }


    internal fun embeddedCoverCacheLookupKeys(song: SongItem): List<String> {
        return this.embeddedCoverCacheLookupKeysImpl(song)
    }


    fun resolveCoverUri(context: Context, uri: Uri): String? {
        return this.resolveCoverUriImpl(context, uri)
    }


    fun inspect(context: Context, uri: Uri): LocalMediaDetails {
        return this.inspectImpl(context, uri)
    }






    fun toSongItem(details: LocalMediaDetails): SongItem {
        return this.toSongItemImpl(details)
    }


    suspend fun shareSongFile(context: Context, song: SongItem): Boolean {
        return this.shareSongFileImpl(context, song)
    }


    fun downloadDirectory(context: Context): File {
        return ManagedDownloadRootResolver.defaultRootDirectory(context)
    }

    // 优先直接分享受控目录中的文件，无法直出时再复制到缓存 staging 后分享
    fun prepareShareableFile(context: Context, sourceFile: File): File {
        return prepareShareableFileInDirectory(
            sourceFile = sourceFile,
            shareDir = File(context.cacheDir, SHARED_LOCAL_MEDIA_DIR)
        )
    }

    internal fun prepareShareableContentFile(
        context: Context,
        sourceUri: Uri,
        suggestedName: String
    ): File? {
        return this.prepareShareableContentFileImpl(context, sourceUri, suggestedName)
    }


    internal fun prepareShareableFileInDirectory(sourceFile: File, shareDir: File): File {
        return this.prepareShareableFileInDirectoryImpl(sourceFile, shareDir)
    }


    internal fun shareableStageFileName(sourceFile: File): String {
        val extension = sourceFile.extension
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            .orEmpty()
        return "${stableKey("${sourceFile.absolutePath}|${sourceFile.length()}|${sourceFile.lastModified()}")}$extension"
    }

    internal fun shouldRestageShareCopy(stagedFile: File, sourceFile: File): Boolean {
        return !stagedFile.exists() ||
            stagedFile.length() != sourceFile.length() ||
            stagedFile.lastModified() < sourceFile.lastModified()
    }

    fun readTextContent(context: Context, reference: String): String? {
        return this.readTextContentImpl(context, reference)
    }


    fun readTextFile(file: File): String? {
        return this.readTextFileImpl(file)
    }




    internal fun readLocalMetadataSidecarFast(
        context: Context,
        song: SongItem,
        metadataReference: String? = null
    ): LocalMetadataSidecar? {
        return this.readLocalMetadataSidecarFastImpl(context, song, metadataReference)
    }


    internal fun shouldProbeAbsoluteMetadataSidecar(
        sourceUri: Uri?,
        metadataFile: File
    ): Boolean {
        return this.shouldProbeAbsoluteMetadataSidecarImpl(sourceUri, metadataFile)
    }


    internal fun shouldProbeRetrieverTextMetadata(
        sourceReference: String?,
        file: File?
    ): Boolean {
        return this.shouldProbeRetrieverTextMetadataImpl(sourceReference, file)
    }


    internal fun parseLocalMetadataSidecar(
        reference: String,
        raw: String
    ): LocalMetadataSidecar? {
        return this.parseLocalMetadataSidecarImpl(reference, raw)
    }


    internal fun buildLocalLyricsMetadataJson(
        existingRaw: String?,
        song: SongItem,
        clearMissingLyricFields: Boolean = false
    ): String {
        return this.buildLocalLyricsMetadataJsonImpl(existingRaw, song, clearMissingLyricFields)
    }


    internal fun buildEditableLocalMetadataJson(
        existingRaw: String?,
        song: SongItem,
        writeLyrics: Boolean,
        coverReference: String?,
        clearCoverReference: Boolean
    ): String {
        return this.buildEditableLocalMetadataJsonImpl(existingRaw, song, writeLyrics, coverReference, clearCoverReference)
    }


















    /**
     * MediaStore 不会从音频 URI 暴露同级文件，使用 RELATIVE_PATH 回到已保存的外部存储树
     * 让 scoped storage 变化后仍能找到 Lyrics、Covers 和元数据侧载
     */


    internal fun resolveWritableLocalMediaUri(
        context: Context,
        sourceUri: Uri
    ): Uri? {
        return this.resolveWritableLocalMediaUriImpl(context, sourceUri)
    }


    internal fun buildExternalStorageDocumentId(
        parentDocumentId: String,
        displayName: String
    ): String? {
        return this.buildExternalStorageDocumentIdImpl(parentDocumentId, displayName)
    }








    internal fun List<String>.startsWithSegments(prefix: List<String>): Boolean {
        return size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
    }

    internal fun JSONObject.optPresentLocalMetadataString(fieldName: String): String? {
        if (!has(fieldName) || isNull(fieldName)) return null
        return optString(fieldName)
    }







    internal data class QueriedContentInfo(
        val displayName: String?,
        val sizeBytes: Long?,
        val mimeType: String?,
        val lastModifiedMs: Long?,
        val filePath: String?,
        val relativePath: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?
    )





















    internal fun applyEditableMetadata(
        propertyMap: PropertyMap,
        title: String,
        artist: String,
        lyrics: String?,
        translatedLyrics: String?,
        romanizedLyrics: String? = null,
        audioExtension: String?,
        writeLyrics: Boolean = false,
        sourceStableKey: String? = null
    ): PropertyMap {
        return this.applyEditableMetadataImpl(propertyMap, title, artist, lyrics, translatedLyrics, romanizedLyrics, audioExtension, writeLyrics, sourceStableKey)
    }


    internal fun hasExpectedEditableMetadata(
        propertyMap: PropertyMap,
        title: String,
        artist: String,
        lyrics: String?,
        translatedLyrics: String?,
        romanizedLyrics: String? = null,
        audioExtension: String?,
        expectedStandardLyrics: String? = mergeLyricsForExternalPlayers(lyrics, translatedLyrics),
        verifyStandardLyrics: Boolean = lyrics != null || translatedLyrics != null,
        verifyMissingLyrics: Boolean = false,
        sourceStableKey: String? = null
    ): Boolean {
        return this.hasExpectedEditableMetadataImpl(propertyMap, title, artist, lyrics, translatedLyrics, romanizedLyrics, audioExtension, expectedStandardLyrics, verifyStandardLyrics, verifyMissingLyrics, sourceStableKey)
    }










    internal sealed class EditableCoverWritePlan {
        data object Unchanged : EditableCoverWritePlan()
        data object Unreadable : EditableCoverWritePlan()
        data class Update(
            val pictures: Array<Picture>,
            val originalPictures: Array<Picture>
        ) : EditableCoverWritePlan() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Update

                if (!pictures.contentEquals(other.pictures)) return false
                if (!originalPictures.contentEquals(other.originalPictures)) return false

                return true
            }

            override fun hashCode(): Int {
                var result = pictures.contentHashCode()
                result = 31 * result + originalPictures.contentHashCode()
                return result
            }
        }
    }

    internal data class EditableMetadataSnapshot(
        val existingProperties: PropertyMap,
        val updatedProperties: PropertyMap,
        val picturePlan: EditableCoverWritePlan,
        val expectedStandardLyrics: String?,
        val sourceStableKey: String,
        val writesLyrics: Boolean,
        val clearsMissingLyrics: Boolean,
        val requiredEmbeddedPropertyKeys: Set<String>
    )



    internal fun hasExpectedPropertyMapValues(
        actual: PropertyMap,
        expected: PropertyMap,
        requiredKeys: Set<String>
    ): Boolean {
        return this.hasExpectedPropertyMapValuesImpl(actual, expected, requiredKeys)
    }




    internal fun hasExpectedEditableCover(
        actualPictures: Array<Picture>,
        expectedPictures: Array<Picture>,
        audioExtension: String? = null
    ): Boolean {
        return this.hasExpectedEditableCoverImpl(actualPictures, expectedPictures, audioExtension)
    }




    internal fun usesRolelessEditableCoverPictures(audioExtension: String?): Boolean {
        return audioExtension
            ?.trim()
            ?.lowercase(Locale.ROOT) in ROLELESS_COVER_PICTURE_EXTENSIONS
    }

    internal fun shouldRestoreEditablePropertiesAfterCoverWrite(
        audioExtension: String?,
        writesCover: Boolean
    ): Boolean {
        return writesCover &&
            usesRolelessEditableCoverPictures(audioExtension)
    }

    internal fun replaceEditableCoverPictures(
        existingPictures: Array<Picture>,
        replacementPicture: Picture?,
        audioExtension: String?
    ): Array<Picture> {
        return this.replaceEditableCoverPicturesImpl(existingPictures, replacementPicture, audioExtension)
    }






    internal fun resolveEditableCoverMutation(
        writeCover: Boolean,
        coverReference: String?
    ): EditableCoverMutation {
        return this.resolveEditableCoverMutationImpl(writeCover, coverReference)
    }


    internal fun String.isRemoteCoverReference(): Boolean {
        return startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true)
    }

    internal fun readEditableCoverBytes(context: Context, reference: String): ByteArray? {
        return this.readEditableCoverBytesImpl(context, reference)
    }






    internal fun normalizeEmbeddedCoverForContainer(
        sourceBytes: ByteArray,
        sourceMimeType: String?,
        audioExtension: String?
    ): Pair<ByteArray, String>? {
        return this.normalizeEmbeddedCoverForContainerImpl(sourceBytes, sourceMimeType, audioExtension)
    }
















    internal fun parseId3FileMetadata(file: File): ContainerMetadata? {
        return this.parseId3FileMetadataImpl(file)
    }






    internal fun parseWaveMetadata(file: File): ContainerMetadata? {
        return this.parseWaveMetadataImpl(file)
    }








    internal fun ContainerMetadata.hasAnyValue(): Boolean {
        return !title.isNullOrBlank() ||
            !artist.isNullOrBlank() ||
            !album.isNullOrBlank() ||
            !albumArtist.isNullOrBlank() ||
            !composer.isNullOrBlank() ||
            !genre.isNullOrBlank() ||
            year != null ||
            trackNumber != null ||
            discNumber != null
    }











    internal fun embeddedCoverCacheKeys(
        uri: String,
        resolvedPath: String?
    ): List<String> {
        val baseKey = resolvedPath ?: uri
        return listOf(baseKey, "$baseKey#taglib")
    }

    internal fun embeddedCoverCacheKeys(
        uri: Uri,
        resolved: ResolvedInspectableLocalMedia
    ): List<String> = embeddedCoverCacheKeys(uri.toString(), resolved.resolvedPath)













    internal fun embeddedCoverCacheSampleSize(
        width: Int,
        height: Int,
        targetDimension: Int = MAX_EMBEDDED_COVER_CACHE_DIMENSION_PX
    ): Int {
        return this.embeddedCoverCacheSampleSizeImpl(width, height, targetDimension)
    }




    internal fun findNearbyLyricFiles(
        file: File?,
        extensions: List<String> = lyricExtensions
    ): NearbyLyricFiles {
        return this.findNearbyLyricFilesImpl(file, extensions)
    }


    internal fun copyNearbyLyricSidecars(
        context: Context,
        sourceUri: Uri,
        sourceDisplayName: String,
        targetFile: File
    ) {
        return this.copyNearbyLyricSidecarsImpl(context, sourceUri, sourceDisplayName, targetFile)
    }








    internal data class ContentSidecarReferences(
        val metadataReference: String?,
        val lyricReferences: NearbyLyricReferences
    )









    internal fun containsExactDocumentSource(
        documentIds: Collection<String>,
        sourceDocumentId: String?
    ): Boolean {
        return !sourceDocumentId.isNullOrBlank() && sourceDocumentId in documentIds
    }

    internal fun matchesDocumentPathParent(
        path: List<String>,
        parentDocumentId: String,
        sourceDocumentId: String?,
        displayName: String,
        actualDisplayName: String?
    ): Boolean {
        return this.matchesDocumentPathParentImpl(path, parentDocumentId, sourceDocumentId, displayName, actualDisplayName)
    }






    internal data class DocumentChild(
        val documentId: String,
        val displayName: String,
        val isDirectory: Boolean,
        val uri: String,
        val createdByCurrentMutation: Boolean = false
    )

    internal data class DocumentChildrenQueryResult(
        val children: List<DocumentChild>,
        val isComplete: Boolean
    )

    internal fun isManagedSidecarDirectoryName(actualName: String, desiredName: String): Boolean {
        return this.isManagedSidecarDirectoryNameImpl(actualName, desiredName)
    }






    internal fun localCoverSidecarName(
        baseName: String,
        extension: String,
        stableIdentityKey: String?
    ): String {
        return this.localCoverSidecarNameImpl(baseName, extension, stableIdentityKey)
    }






    internal fun sidecarNameMatches(actualName: String, canonicalName: String): Boolean {
        return this.sidecarNameMatchesImpl(actualName, canonicalName)
    }
























    /** 大目录只保留有界的热缓存，避免扫描歌曲越多内存和 GC 越来越高 */










































    internal fun resolveEffectiveLocalLyricContent(
        sidecarContent: String?,
        embeddedContent: String?
    ): String? {
        return sidecarContent ?: embeddedContent?.takeIf(String::isNotBlank)
    }



    internal fun resolveEffectiveLocalLyricPath(
        reference: String?,
        content: String?
    ): String? {
        return reference?.takeIf { content != null }
    }





    internal enum class LyricKind {
        ORIGINAL,
        TRANSLATED,
        ROMANIZED
    }

    internal fun findNearbyCover(file: File?): File? {
        return this.findNearbyCoverImpl(file)
    }




























    internal fun String?.takeMeaningfulLocalMetadata(): String? {
        val value = this?.trim().orEmpty()
        if (value.isBlank()) return null
        return value.takeUnless {
            it.lowercase(Locale.ROOT) in LOCAL_METADATA_PLACEHOLDERS
        }
    }











    internal fun ByteArray.decodeContainerText(): String? {
        if (isEmpty()) return null
        val trimmed = dropLastWhile { it == 0.toByte() || it == 32.toByte() }.toByteArray()
        if (trimmed.isEmpty()) return null

        detectBomCharset(trimmed)?.let { (charset, offset) ->
            return trimmed.copyOfRange(offset, trimmed.size)
                .toString(charset)
                .normalizeDecodedText()
                .trim(NUL_CHAR, ' ')
                .takeIf { it.isNotBlank() }
        }

        val candidates = buildList {
            add(StandardCharsets.UTF_8)
            add(StandardCharsets.UTF_16LE)
            add(StandardCharsets.UTF_16BE)
            runCatching { Charset.forName("GB18030") }.getOrNull()?.let(::add)
            runCatching { Charset.forName("GBK") }.getOrNull()?.let(::add)
            runCatching { Charset.forName("windows-1252") }.getOrNull()?.let(::add)
            add(StandardCharsets.ISO_8859_1)
        }.distinct()

        return candidates
            .map { charset ->
                charset to scoreDecodedText(trimmed.toString(charset).normalizeDecodedText().trim(NUL_CHAR, ' '))
            }
            .maxByOrNull { it.second }
            ?.first
            ?.let { trimmed.toString(it).normalizeDecodedText().trim(NUL_CHAR, ' ') }
            ?.takeIf { it.isNotBlank() }
    }



    internal fun String.extractYear(): Int? {
        val match = Regex("(19|20)\\d{2}").find(this) ?: return null
        return match.value.toIntOrNull()
    }



    internal fun String.normalizeDecodedText(): String = replace(BOM_CHAR.toString(), "")
}

internal fun android.database.Cursor.getOptionalString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    if (index == -1 || isNull(index)) return null
    return getString(index)
}

internal fun android.database.Cursor.getOptionalLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    if (index == -1 || isNull(index)) return null
    return getLong(index)
}

internal fun MediaMetadataRetriever.extractNonBlankMetadata(keyCode: Int): String? {
    return extractMetadata(keyCode)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

internal fun Map<String, Array<String>>?.readFirstValue(vararg keys: String): String? {
    val propertyMap = this ?: return null
    return keys.firstNotNullOfOrNull { key ->
        propertyMap.entries.firstOrNull { (entryKey, _) -> entryKey.equals(key, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.replace(BOM_CHAR.toString(), "")
            ?.trim(NUL_CHAR, ' ')
            ?.takeIf { it.isNotBlank() }
    }
}

internal fun Map<String, Array<String>>?.readNeriSourceStableKey(): String? {
    readFirstValue("NERI_STABLE_KEY", "NERI STABLE KEY")
        ?.let { return it }

    return readFirstValue("COMMENT")?.let { comment ->
        runCatching {
            JSONObject(comment).optString("stableKey")
                .trim()
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

internal fun RandomAccessFile.readFourCc(): String? {
    val bytes = ByteArray(4)
    val read = read(bytes)
    if (read != 4) return null
    return bytes.toString(StandardCharsets.US_ASCII)
}

internal fun RandomAccessFile.readLittleEndianUInt32(): Long {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (b3 == -1) return -1L
    return (b0.toLong() and 0xFF) or
        ((b1.toLong() and 0xFF) shl 8) or
        ((b2.toLong() and 0xFF) shl 16) or
        ((b3.toLong() and 0xFF) shl 24)
}

internal fun RandomAccessFile.readChunkBytes(chunkSize: Long, fileLimit: Long): ByteArray? {
    if (chunkSize <= 0L) return ByteArray(0)
    val readableSize = minOf(chunkSize, fileLimit - filePointer, MAX_CONTAINER_METADATA_BYTES)
    if (readableSize <= 0L) return null
    val data = ByteArray(readableSize.toInt())
    val read = read(data)
    return if (read <= 0) null else data.copyOf(read)
}

internal fun ByteArray.readAscii(offset: Int, length: Int): String? {
    if (offset < 0 || length <= 0 || offset + length > size) return null
    return copyOfRange(offset, offset + length).toString(StandardCharsets.US_ASCII)
}

internal fun ByteArray.readFourCc(offset: Int): String? {
    if (offset < 0 || offset + 4 > size) return null
    return copyOfRange(offset, offset + 4).toString(StandardCharsets.US_ASCII)
}

internal fun ByteArray.readLittleEndianUInt32(offset: Int): Long {
    if (offset < 0 || offset + 4 > size) return 0L
    return (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
}

internal fun ByteArray.readBigEndianInt(offset: Int): Int {
    if (offset < 0 || offset + 4 > size) return 0
    return ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)
}

internal fun ByteArray.readBigEndianInt24(offset: Int): Int {
    if (offset < 0 || offset + 3 > size) return 0
    return ((this[offset].toInt() and 0xFF) shl 16) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        (this[offset + 2].toInt() and 0xFF)
}

internal fun ByteArray.readSynchsafeInt(offset: Int): Int {
    if (offset < 0 || offset + 4 > size) return 0
    return ((this[offset].toInt() and 0x7F) shl 21) or
        ((this[offset + 1].toInt() and 0x7F) shl 14) or
        ((this[offset + 2].toInt() and 0x7F) shl 7) or
        (this[offset + 3].toInt() and 0x7F)
}

internal fun Char.isAsciiLetterOrDigit(): Boolean {
    return this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'
}

internal fun Char.isCjkUnifiedIdeograph(): Boolean {
    val code = code
    return code in 0x3400..0x4DBF ||
        code in 0x4E00..0x9FFF ||
        code in 0xF900..0xFAFF
}

internal fun MediaFormat.getOptionalInt(key: String): Int? {
    if (!containsKey(key)) return null
    return runCatching { getInteger(key) }.getOrNull()
}

internal fun MediaFormat.getOptionalString(key: String): String? {
    if (!containsKey(key)) return null
    return runCatching { getString(key) }.getOrNull()
}
