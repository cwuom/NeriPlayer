package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.policy.ManagedDownloadSizePolicy
import moe.ouom.neriplayer.core.download.ParsedManagedDownloadFileName
import moe.ouom.neriplayer.core.download.candidateManagedDownloadFileNameTemplates
import moe.ouom.neriplayer.core.download.model.isFinalizedDownloadedAudioEntry
import moe.ouom.neriplayer.core.download.parseManagedDownloadBaseName
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeNaming
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataRecoveryStore
import moe.ouom.neriplayer.data.local.media.LocalMetadataSidecar
import moe.ouom.neriplayer.data.local.media.LocalKnownSidecarReferences
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.NearbyLyricReferences
import moe.ouom.neriplayer.data.local.media.CoverReferenceValidation
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.local.media.normalizeLocalAlbumIdentity
import moe.ouom.neriplayer.data.local.media.preferredLocalMediaReference
import moe.ouom.neriplayer.data.local.media.isMediaStoreSidecarReference
import moe.ouom.neriplayer.data.local.media.isMediaStoreUri
import moe.ouom.neriplayer.data.local.media.isUsableCoverReference
import moe.ouom.neriplayer.data.local.media.validateCoverReference
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class LocalAudioScanPhase {
    PREPARING,
    READING_DOWNLOAD_INDEX,
    QUERYING_MEDIA_STORE,
    TRAVERSING,
    BUILDING_ENTRIES,
    HYDRATING_METADATA,
    COMPLETED
}

data class LocalAudioScanProgress(
    val scanId: Long = 0L,
    val phase: LocalAudioScanPhase = LocalAudioScanPhase.PREPARING,
    val processed: Int = 0,
    val total: Int = 0,
    val discoveredSongs: Int = 0,
    val visitedDirectories: Int = 0,
    val elapsedMs: Long = 0L,
    val phaseElapsedMs: Long = 0L,
    val waitingForProvider: Boolean = false
) {
    val fraction: Float?
        get() = total.takeIf { it > 0 }?.let {
            (processed.toFloat() / it).coerceIn(0f, 1f)
        }
}

internal class LocalAudioScanProgressEmitter(
    private val scanId: Long,
    private val startedAt: Long,
    private val onProgress: (LocalAudioScanProgress) -> Unit
) {
    internal var lastReportedAt = 0L
    internal var phaseStartedAt = startedAt
    internal var currentPhase = LocalAudioScanPhase.PREPARING
    internal var lastProcessed = 0
    internal var lastTotal = 0
    internal var lastDiscoveredSongs = 0
    internal var lastVisitedDirectories = 0

    fun emit(
        phase: LocalAudioScanPhase,
        processed: Int,
        total: Int,
        discoveredSongs: Int,
        visitedDirectories: Int,
        waitingForProvider: Boolean = false,
        force: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReportedAt < PROGRESS_REPORT_INTERVAL_MS) {
            return
        }
        lastReportedAt = now
        if (phase != currentPhase) {
            currentPhase = phase
            phaseStartedAt = now
        }
        lastProcessed = processed
        lastTotal = total
        lastDiscoveredSongs = discoveredSongs
        lastVisitedDirectories = visitedDirectories
        try {
            onProgress(
                LocalAudioScanProgress(
                    scanId = scanId,
                    phase = phase,
                    processed = processed,
                    total = total,
                    discoveredSongs = discoveredSongs,
                    visitedDirectories = visitedDirectories,
                    elapsedMs = now - startedAt,
                    phaseElapsedMs = now - phaseStartedAt,
                    waitingForProvider = waitingForProvider
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "scan progress callback failed: ${error.message}")
        }
    }

    fun emitWaitingHeartbeat(phase: LocalAudioScanPhase) {
        emit(
            phase = phase,
            processed = lastProcessed,
            total = lastTotal,
            discoveredSongs = lastDiscoveredSongs,
            visitedDirectories = lastVisitedDirectories,
            waitingForProvider = true,
            force = true
        )
    }

    internal companion object {
        const val PROGRESS_REPORT_INTERVAL_MS = 50L
        const val TAG = "LocalAudioScanProgress"
    }
}

data class LocalAudioImportResult(
    val songs: List<SongItem>,
    val failedCount: Int,
    val completed: Boolean = true,
    val metadataDeferred: Boolean = false
)

internal fun <T> Result<T>.getOrRethrowCancellation(
    onFailure: (Throwable) -> Unit
): T? {
    return fold(
        onSuccess = { it },
        onFailure = { error ->
            if (error is CancellationException) throw error
            onFailure(error)
            null
        }
    )
}

internal fun shouldUseMediaStoreScanResult(result: LocalAudioImportResult?): Boolean {
    return result?.songs?.isNotEmpty() == true
}

internal fun shouldKeepMediaStoreAudioRow(
    hasResolvedFile: Boolean,
    hasProviderAudioReference: Boolean,
    hasReadableMediaStoreReference: Boolean
): Boolean {
    return hasResolvedFile ||
        hasProviderAudioReference ||
        hasReadableMediaStoreReference
}

internal fun shouldFallbackToDocumentFileAfterTraversalFailure(error: Throwable): Boolean {
    return error !is CancellationException
}

internal fun shouldProbeMediaStoreContentReference(
    rowOrdinal: Int,
    hasResolvedFile: Boolean,
    probeLimit: Int = 256
): Boolean {
    return hasResolvedFile || rowOrdinal in 1..probeLimit
}

internal fun hasUsableMediaStoreContentReference(
    rowOrdinal: Int,
    hasResolvedFile: Boolean,
    probeSucceeded: Boolean,
    probeLimit: Int = 256
): Boolean {
    return hasResolvedFile ||
        !shouldProbeMediaStoreContentReference(
            rowOrdinal = rowOrdinal,
            hasResolvedFile = false,
            probeLimit = probeLimit
        ) || probeSucceeded
}

internal fun shouldDeferExpensiveScanMetadata(
    songCount: Int,
    threshold: Int = 256
): Boolean {
    return songCount > threshold
}

internal fun shouldHydrateLocalSongFastIdentity(
    song: SongItem,
    metadataReference: String?
): Boolean {
    if (!metadataReference.isNullOrBlank() &&
        !isMediaStoreSidecarReference(metadataReference)
    ) {
        return true
    }
    val source = song.localMediaUri()
    if (source != null) {
        return !isMediaStoreUri(source) ||
            LocalAudioImportManager.needsLocalIdentityMetadataProbe(song)
    }
    return if (isMediaStoreSourceReference(song.mediaUri)) {
        LocalAudioImportManager.needsLocalIdentityMetadataProbe(song)
    } else {
        true
    }
}

internal fun isMediaStoreSourceReference(reference: String?): Boolean {
    val normalized = reference?.trim()?.lowercase(Locale.ROOT) ?: return false
    return normalized.startsWith("content://media/") ||
        normalized.startsWith("content://com.android.providers.media.documents/")
}

internal const val CREATION_TIME_FUTURE_TOLERANCE_MS = 24L * 60L * 60L * 1_000L

internal fun resolveMediaStoreSourceAddedAt(
    dateAddedSeconds: Long?,
    dateModifiedSeconds: Long?
): Long {
    return dateAddedSeconds.toEpochMillisOrNull()
        ?: dateModifiedSeconds.toEpochMillisOrNull()
        ?: 0L
}

internal fun resolveScannedSourceAddedAt(
    preferredTimestampMs: Long?,
    fallbackTimestampMs: Long?
): Long {
    return preferredTimestampMs?.takeIf { it > 0L }
        ?: fallbackTimestampMs?.takeIf { it > 0L }
        ?: 0L
}

internal fun resolveFilesystemCreationTime(file: File): Long? {
    return resolveFilesystemCreationObservation(file)?.timestampMs
}

internal data class FilesystemCreationObservation(
    val timestampMs: Long,
    val confidence: String
)

internal fun resolveFilesystemCreationConfidence(
    creationTimeMs: Long,
    lastModifiedTimeMs: Long
): String {
    return if (creationTimeMs == lastModifiedTimeMs) "INFERRED" else "EXACT"
}

internal fun resolveFilesystemCreationObservation(file: File): FilesystemCreationObservation? {
    return runCatching {
        val attributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        val creationTimeMs = attributes.creationTime().toMillis()
        val lastModifiedTimeMs = attributes.lastModifiedTime().toMillis()
        val latestAllowed = System.currentTimeMillis() + CREATION_TIME_FUTURE_TOLERANCE_MS
        if (creationTimeMs <= 0L || creationTimeMs > latestAllowed) {
            return@runCatching null
        }
        FilesystemCreationObservation(
            timestampMs = creationTimeMs,
            confidence = resolveFilesystemCreationConfidence(
                creationTimeMs = creationTimeMs,
                lastModifiedTimeMs = lastModifiedTimeMs
            )
        )
    }.getOrNull()
}

internal fun validSongTimestamp(timestamp: Long?): Long {
    return timestamp?.takeIf { it > 0L } ?: Long.MIN_VALUE
}

internal fun songSourceTimestamp(song: SongItem): Long {
    return validSongTimestamp(song.logicalCreatedAtMs ?: song.addedAt)
}

internal fun songCreationConfidence(song: SongItem): Int {
    return when (song.createdAtConfidence?.uppercase(Locale.ROOT)) {
        "EXACT" -> 3
        "PROVIDER_REPORTED" -> 2
        "INFERRED" -> 1
        else -> 0
    }
}

internal fun isNonCreationTimestampSource(source: String?): Boolean = when (source?.trim()?.uppercase(Locale.ROOT)) {
    "SAF_LAST_MODIFIED", "MTIME", "MTIME_FALLBACK", "MEDIASTORE_DATE_MODIFIED", "MEDIASTORE_DATE_ADDED" -> true
    else -> false
}

internal fun hasStableSongCreationEvidence(song: SongItem): Boolean {
    if (isNonCreationTimestampSource(song.createdAtSource)) {
        return false
    }
    if (song.logicalCreatedAtMs?.let { validSongTimestamp(it) != Long.MIN_VALUE } == true) {
        return true
    }
    if (songCreationConfidence(song) >= 2) {
        return true
    }
    // 旧版本没有可信度字段，只能把已有 addedAt 当作旧数据回退时间
    // 显式 UNKNOWN 或 INFERRED 的记录不能借此伪造创建时间
    return song.createdAtConfidence == null &&
        validSongTimestamp(song.addedAt) != Long.MIN_VALUE
}

/** 扫描预览按来源时间排序，不使用本次歌单加入时间 */
internal fun localSongSourceCreationComparator(): Comparator<SongItem> {
    return Comparator { left, right ->
        val leftHasStableCreation = hasStableSongCreationEvidence(left)
        val rightHasStableCreation = hasStableSongCreationEvidence(right)
        if (leftHasStableCreation != rightHasStableCreation) {
            return@Comparator if (leftHasStableCreation) -1 else 1
        }
        if (!leftHasStableCreation) {
            // DocumentsProvider 没有可靠创建时间时，利用稳定排序保留 Provider 发现顺序
            // 不要用 addedAt 或文件名重排这些歌曲
            return@Comparator 0
        }

        val timestampComparison = validSongTimestamp(right.logicalCreatedAtMs ?: right.addedAt)
            .compareTo(validSongTimestamp(left.logicalCreatedAtMs ?: left.addedAt))
        if (timestampComparison != 0) {
            return@Comparator timestampComparison
        }

        // 同时创建的文件保持原次序，迁移后的 URI 和文件名不能改变先后
        0
    }
}

/** 已加入本地歌单的歌曲优先按加入时间，同一批次再看来源创建时间 */
internal fun localSongNewestFirstComparator(): Comparator<SongItem> {
    return compareByDescending<SongItem> { song ->
        validSongTimestamp(song.membershipAddedAtMs)
    }
        .thenByDescending(::songSourceTimestamp)
        .thenByDescending { song ->
            songCreationConfidence(song)
        }
        .thenBy { it.sourceStableKey.orEmpty() }
        .thenBy { it.mediaUri.orEmpty() }
        .thenBy { it.localFileName.orEmpty() }
}

internal fun selectMetadataSidecarReference(
    referencesByName: Map<String, String>,
    audioName: String,
    indexedReference: String? = null
): String? {
    indexedReference?.takeIf(String::isNotBlank)?.let { return it }
    return referencesByName.entries
        .asSequence()
        .filter { entry ->
            ManagedDownloadTreeNaming.metadataNameOrdinal(entry.key, audioName) != null
        }
        .minWithOrNull(
            compareBy<Map.Entry<String, String>>(
                { entry ->
                    ManagedDownloadTreeNaming.metadataNameOrdinal(entry.key, audioName)
                        ?: Int.MAX_VALUE
                },
                { entry -> entry.key }
            )
        )
        ?.value
}

/**
 * 旧 metadata 可能保存了已经失效的 SAF 封面引用, 优先使用当前快照重绑定的引用
 */
internal fun selectHydratedLocalCoverReference(
    sidecarCover: String?,
    existingCover: String?,
    reboundCover: String?,
    metadataFallbackCover: String?
): String? {
    val sidecar = sidecarCover.normalizeImportedCoverReference()
    val existing = existingCover.normalizeImportedCoverReference()
    val rebound = reboundCover.normalizeImportedCoverReference()
    val fallback = metadataFallbackCover.normalizeImportedCoverReference()

    // 仍可直接使用的侧车封面保持原有权威性, 避免被远端候选替换
    if (sidecar != null && !isPotentiallyStaleSafCoverReference(sidecar)) {
        return sidecar
    }
    rebound?.let { return it }
    if (existing != null && !sameImportedCoverReference(existing, sidecar)) {
        // 扫描结果可能是新的 SAF URI, 不能因为它不是 MediaStore URI 就丢掉
        return existing
    }
    if (fallback != null && !isPotentiallyStaleSafCoverReference(fallback)) {
        return fallback
    }
    return fallback ?: existing ?: sidecar
}

internal data class LocalCoverHydrationResult(
    val song: SongItem,
    val clearCoverUrl: Boolean = false,
    val clearOriginalCoverUrl: Boolean = false
)

/**
 * 快速扫描留下的旧 SAF 引用不能覆盖后续详细扫描得到的新封面
 */
internal fun selectMergedImportedCoverReference(
    quickCover: String?,
    detailedCover: String?
): String? {
    val quick = quickCover.normalizeImportedCoverReference()
    val detailed = detailedCover.normalizeImportedCoverReference()
    return when {
        quick == null -> detailed
        detailed == null -> quick
        isPotentiallyStaleSafCoverReference(quick) && quick != detailed -> detailed
        else -> quick
    }
}

internal fun String?.normalizeImportedCoverReference(): String? {
    return this?.trim()?.takeIf(String::isNotBlank)
}

internal fun sameImportedCoverReference(first: String?, second: String?): Boolean {
    return first != null && second != null && first == second
}

internal fun isPotentiallyStaleSafCoverReference(reference: String): Boolean {
    return reference.startsWith("content://", ignoreCase = true) &&
        !isMediaStoreSidecarReference(reference)
}

internal fun buildLocalSidecarMetadataIndex(
    filesByName: Map<String, String>
): Map<String, String> {
    val best = HashMap<String, Pair<Int, String>>()
    filesByName.forEach { (normalizedName, reference) ->
        val audioName = ManagedDownloadTreeNaming.metadataAudioName(normalizedName)
            ?: return@forEach
        val key = audioName.lowercase(Locale.ROOT)
        val ordinal = ManagedDownloadTreeNaming.metadataNameOrdinal(
            actualName = normalizedName,
            audioName = audioName
        ) ?: Int.MAX_VALUE
        val previous = best[key]
        if (previous == null || ordinal < previous.first ||
            (ordinal == previous.first && reference < previous.second)
        ) {
            best[key] = ordinal to reference
        }
    }
    return best.mapValues { (_, value) -> value.second }
}

internal fun Long?.toEpochMillisOrNull(): Long? {
    val seconds = this?.takeIf { it > 0L } ?: return null
    val latestAllowedSeconds =
        (System.currentTimeMillis() / 1_000L) + (CREATION_TIME_FUTURE_TOLERANCE_MS / 1_000L)
    if (seconds > latestAllowedSeconds || seconds > Long.MAX_VALUE / 1_000L) {
        return null
    }
    return seconds * 1_000L
}

internal fun Long?.toValidTimestampMsOrNull(): Long? {
    val timestamp = this?.takeIf { it > 0L } ?: return null
    val latestAllowed = System.currentTimeMillis() + CREATION_TIME_FUTURE_TOLERANCE_MS
    return timestamp.takeIf { it <= latestAllowed }
}

internal data class SidecarCopyPlan(
    val source: File,
    val target: File
)

internal data class QuickImportedSongSeed(
    val sourceRef: String,
    val displayName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val sourceAddedAt: Long? = null,
    val sourceAddedAtSource: String? = null,
    val sourceAddedAtConfidence: String? = null,
    val localFile: File? = null,
    val nearbyCoverUri: String? = null,
    val mediaStoreCoverUri: String? = null,
    val stableIdentitySource: String? = null,
    val sourceStableKey: String? = null,
    val matchedLyric: String? = null,
    val matchedTranslatedLyric: String? = null,
    val originalLyric: String? = null,
    val originalTranslatedLyric: String? = null,
    val matchedRomanizedLyric: String? = null,
    val originalRomanizedLyric: String? = null
)

internal data class QuickImportedAudioInfo(
    val displayName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val sourceAddedAt: Long? = null,
    val sourceAddedAtSource: String? = null,
    val sourceAddedAtConfidence: String? = null,
    val mediaStoreCoverUri: String? = null
)

internal data class ExternalAudioCopyInfo(
    val displayName: String?,
    val sizeBytes: Long?,
    val sourceAddedAt: Long? = null,
    val sourceAddedAtSource: String? = null,
    val sourceAddedAtConfidence: String? = null,
    val sourceLastModifiedAt: Long? = null
)

internal data class ExternalProviderTimestampInfo(
    val dateAddedMs: Long? = null,
    val dateModifiedMs: Long? = null,
    val documentLastModifiedMs: Long? = null
)

internal data class ExternalAudioBaseInfo(
    val displayName: String?,
    val sizeBytes: Long?
)

internal data class StabilizedExternalAudio(
    val uri: Uri,
    val sourceAddedAt: Long? = null,
    val sourceAddedAtSource: String? = null,
    val sourceAddedAtConfidence: String? = null
)

internal data class FolderScanCandidate(
    val uri: Uri,
    val displayName: String? = null,
    val nearbyCoverUri: String? = null,
    val sourceAddedAt: Long? = null,
    val sourceAddedAtSource: String? = null,
    val sourceAddedAtConfidence: String? = null,
    val durationMs: Long? = null,
    val knownSidecarReferences: LocalKnownSidecarReferences? = null
)

internal data class FolderTraversalResult(
    val candidates: List<FolderScanCandidate>,
    val visitedDirectoryCount: Int,
    val failedCount: Int,
    val mode: String
)

internal data class CompletedScanSongs(
    val songs: List<SongItem>,
    val metadataDeferred: Boolean
)

internal data class MediaStoreQueryResult(
    val cursor: Cursor,
    val totalCount: Int
) : Closeable {
    override fun close() {
        cursor.close()
    }
}

internal data class ExternalStorageFolderMediaStoreScope(
    val volumeName: String,
    val relativePath: String
)

internal data class QueriedFolderChild(
    val documentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val lastModifiedMs: Long? = null,
    val durationMs: Long? = null
)

internal const val MEDIA_STORE_SIDECAR_CACHE_MAX_CHILDREN_PER_DIRECTORY = 8_192
internal const val MEDIA_STORE_SIDECAR_CACHE_MAX_CHILDREN_TOTAL = 65_536
internal const val MEDIA_STORE_SIDECAR_CACHE_MAX_DIRECTORIES = 512
internal const val MEDIA_STORE_SIDECAR_INDEX_MAX_ENTRIES = 8_192
internal val LOCAL_AUDIO_FILE_EXTENSIONS = setOf(
    "aac",
    "aif",
    "aiff",
    "alac",
    "amr",
    "ape",
    "flac",
    "m4a",
    "m4b",
    "m4p",
    "mid",
    "midi",
    "mka",
    "mp3",
    "oga",
    "ogg",
    "opus",
    "wav",
    "wma"
)
internal val MEDIA_STORE_SIDECAR_PRIORITY_EXTENSIONS = setOf(
    "lrc",
    "txt",
    "json",
    "jpg",
    "jpeg",
    "png",
    "webp"
)

internal fun isAudioDocumentChild(child: QueriedFolderChild): Boolean {
    if (child.mimeType.startsWith("audio/", ignoreCase = true)) return true
    return child.displayName.substringAfterLast('.', "")
        .lowercase(Locale.ROOT) in LOCAL_AUDIO_FILE_EXTENSIONS
}

/**
 * 大目录只保留解析旁车真正需要的条目，避免一次扫描把整个目录复制到长期缓存
 */
internal fun boundMediaStoreSidecarChildrenForCache(
    children: List<QueriedFolderChild>,
    maxEntries: Int = MEDIA_STORE_SIDECAR_CACHE_MAX_CHILDREN_PER_DIRECTORY
): List<QueriedFolderChild> {
    if (maxEntries <= 0 || children.isEmpty()) return emptyList()
    if (children.size <= maxEntries) return children

    fun isEssential(child: QueriedFolderChild): Boolean {
        return child.isDirectory || isAudioDocumentChild(child)
    }

    fun isSidecar(child: QueriedFolderChild): Boolean {
        return !child.isDirectory &&
            child.displayName.substringAfterLast('.', "")
                .lowercase(Locale.ROOT) in MEDIA_STORE_SIDECAR_PRIORITY_EXTENSIONS
    }

    val prioritized = children.asSequence()
        .filter(::isEssential)
        .take(maxEntries)
        .toList()
    if (prioritized.size == maxEntries) return prioritized

    val sidecars = children.asSequence()
        .filter { child -> !isEssential(child) && isSidecar(child) }
        .take(maxEntries - prioritized.size)
        .toList()
    if (prioritized.size + sidecars.size == maxEntries) {
        return prioritized + sidecars
    }

    val remaining = maxEntries - prioritized.size - sidecars.size
    return prioritized + sidecars + children.asSequence()
        .filter { child -> !isEssential(child) && !isSidecar(child) }
        .take(remaining)
        .toList()
}

internal class MediaStoreSidecarResolver(
    private val context: Context,
    private val folderUri: Uri,
    selectedRelativePath: String
) {
    internal data class SidecarChildrenQueryResult(
        val children: List<QueriedFolderChild>,
        val truncated: Boolean
    )

    internal val selectedPath = selectedRelativePath
    internal val selectedSegments = splitStoragePath(selectedRelativePath)
    internal val childrenCache = HashMap<String, List<QueriedFolderChild>>()
    internal var cachedChildrenCount = 0
    internal val directoryCache = HashMap<String, DirectorySidecarIndex?>()
    internal val rootLyricsIndex: Map<String, String> by lazy {
        val root = directoryIndex(emptyList()) ?: return@lazy emptyMap()
        root.lyricsIndex
    }
    internal val rootCoverIndex: Map<String, String> by lazy {
        val root = directoryIndex(emptyList()) ?: return@lazy emptyMap()
        root.coverIndex
    }

    fun resolve(
        relativePath: String?,
        displayName: String
    ): LocalKnownSidecarReferences? {
        val directory = directoryFor(relativePath) ?: return null
        val baseName = displayName.substringBeforeLast('.', displayName)
        val resolved = resolveSidecarReferences(
            directIndex = directory.directIndex,
            nestedIndex = directory.lyricsIndex,
            rootLyricsIndex = rootLyricsIndex,
            displayName = displayName,
            baseName = baseName,
            metadataIndex = directory.metadataIndex
        )
        return resolved
    }

    fun resolveAudioReference(
        relativePath: String?,
        displayName: String
    ): String? {
        val directory = directoryFor(relativePath) ?: return null
        return directory.audioIndex[displayName.lowercase(Locale.ROOT)]
    }

    fun resolveNearbyCoverReference(
        relativePath: String?,
        displayName: String
    ): String? {
        val directory = directoryFor(relativePath) ?: return null
        val baseName = displayName.substringBeforeLast('.', displayName)
        val extensions = listOf("jpg", "jpeg", "png", "webp")

        fun findSpecific(index: Map<String, String>): String? {
            return extensions.firstNotNullOfOrNull { extension ->
                index["$baseName.$extension".lowercase(Locale.ROOT)]
            }
        }

        findSpecific(directory.directIndex)?.let { return it }
        findSpecific(directory.coverIndex)?.let { return it }
        findSpecific(rootCoverIndex)?.let { return it }
        return listOf("cover", "folder", "front").firstNotNullOfOrNull { coverName ->
            extensions.firstNotNullOfOrNull { extension ->
                directory.directIndex["$coverName.$extension".lowercase(Locale.ROOT)]
                    ?: directory.coverIndex["$coverName.$extension".lowercase(Locale.ROOT)]
                    ?: rootCoverIndex["$coverName.$extension".lowercase(Locale.ROOT)]
            }
        }
    }

    internal fun directoryFor(relativePath: String?): DirectorySidecarIndex? {
        val rowSegments = splitStoragePath(relativePath ?: selectedPath)
        if (rowSegments.size < selectedSegments.size ||
            rowSegments.take(selectedSegments.size) != selectedSegments
        ) {
            return null
        }
        return directoryIndex(rowSegments.drop(selectedSegments.size))
    }

    internal fun resolveSidecarReferences(
        directIndex: Map<String, String>,
        nestedIndex: Map<String, String>,
        rootLyricsIndex: Map<String, String>,
        displayName: String,
        baseName: String,
        metadataIndex: Map<String, String>
    ): LocalKnownSidecarReferences {
        fun find(names: List<String>): String? {
            return names.firstNotNullOfOrNull { name ->
                rootLyricsIndex[name.lowercase(Locale.ROOT)]
                    ?: nestedIndex[name.lowercase(Locale.ROOT)]
                    ?: directIndex[name.lowercase(Locale.ROOT)]
            }
        }

        fun lyricNames(prefix: String): List<String> {
            return listOf("lrc", "txt").flatMap { extension ->
                listOf("$prefix.$extension", "$prefix.lrc.txt")
            }
        }
        val original = find(lyricNames(baseName))
        val translated = find(lyricNames("${baseName}_trans"))
        val romanized = find(
            lyricNames(baseName + "_roma") +
                lyricNames(baseName + "_romalrc") +
                lyricNames(baseName + "_romanized")
        )
        return LocalKnownSidecarReferences(
            lyrics = NearbyLyricReferences(
                original = original,
                translated = translated,
                romanized = romanized
            ),
            metadata = metadataIndex[displayName.lowercase(Locale.ROOT)]
        )
    }

    internal fun buildDocumentNameIndex(
        children: Collection<QueriedFolderChild>
    ): Map<String, String> {
        return children.asSequence()
            .filterNot(QueriedFolderChild::isDirectory)
            .filter { child ->
                child.displayName.substringAfterLast('.', "")
                    .lowercase(Locale.ROOT) in MEDIA_STORE_SIDECAR_PRIORITY_EXTENSIONS
            }
            .take(MEDIA_STORE_SIDECAR_INDEX_MAX_ENTRIES)
            .map { child -> child.displayName.lowercase(Locale.ROOT) to child.documentUri.toString() }
            .toMap()
    }

    internal fun buildDocumentAudioIndex(
        children: Collection<QueriedFolderChild>
    ): Map<String, String> {
        return children.asSequence()
            .filter(::isAudioDocumentChild)
            .take(MEDIA_STORE_SIDECAR_INDEX_MAX_ENTRIES)
            .map { child -> child.displayName.lowercase(Locale.ROOT) to child.documentUri.toString() }
            .toMap()
    }

    internal fun directoryIndex(relativeSegments: List<String>): DirectorySidecarIndex? {
        val cacheKey = relativeSegments.joinToString("/") { it.lowercase(Locale.ROOT) }
        if (cacheKey in directoryCache) return directoryCache[cacheKey]

        var directoryUri = folderUri
        relativeSegments.forEach { segment ->
            val child = children(directoryUri).firstOrNull {
                it.isDirectory && it.displayName.equals(segment, ignoreCase = true)
            }
            if (child == null) {
                if (directoryCache.size < MEDIA_STORE_SIDECAR_CACHE_MAX_DIRECTORIES) {
                    directoryCache[cacheKey] = null
                }
                return null
            }
            directoryUri = child.documentUri
        }

        val directChildren = children(directoryUri)
        val lyricsDirectory = directChildren.firstOrNull {
            it.isDirectory && it.displayName.equals("Lyrics", ignoreCase = true)
        }
        val lyricsChildren = lyricsDirectory?.let { children(it.documentUri) }.orEmpty()
        val coversDirectory = directChildren.firstOrNull {
            it.isDirectory && it.displayName.equals("Covers", ignoreCase = true)
        }
        val coversChildren = coversDirectory?.let { children(it.documentUri) }.orEmpty()
        val resolved = DirectorySidecarIndex(
            directIndex = buildDocumentNameIndex(directChildren),
            lyricsIndex = buildDocumentNameIndex(lyricsChildren),
            coverIndex = buildDocumentNameIndex(coversChildren),
            metadataIndex = buildDocumentMetadataIndex(directChildren),
            audioIndex = buildDocumentAudioIndex(directChildren)
        )
        if (directoryCache.size < MEDIA_STORE_SIDECAR_CACHE_MAX_DIRECTORIES) {
            directoryCache[cacheKey] = resolved
        }
        return resolved
    }

    internal fun buildDocumentMetadataIndex(
        children: Collection<QueriedFolderChild>
    ): Map<String, String> {
        val best = HashMap<String, Pair<Int, String>>()
        children.asSequence()
            .filterNot(QueriedFolderChild::isDirectory)
            .take(MEDIA_STORE_SIDECAR_INDEX_MAX_ENTRIES)
            .forEach { child ->
                val audioName = ManagedDownloadTreeNaming.metadataAudioName(child.displayName)
                    ?: return@forEach
                val key = audioName.lowercase(Locale.ROOT)
                val ordinal = ManagedDownloadTreeNaming.metadataNameOrdinal(
                    actualName = child.displayName,
                    audioName = audioName
                ) ?: Int.MAX_VALUE
                val previous = best[key]
                if (previous == null || ordinal < previous.first ||
                    (ordinal == previous.first && child.documentUri.toString() < previous.second)
                ) {
                    best[key] = ordinal to child.documentUri.toString()
                }
            }
        return best.mapValues { (_, value) -> value.second }
    }

    internal fun children(parentUri: Uri): List<QueriedFolderChild> {
        val key = parentUri.toString()
        childrenCache[key]?.let { return it }
        val documentId = runCatching { DocumentsContract.getDocumentId(parentUri) }
            .getOrElse {
                runCatching { DocumentsContract.getTreeDocumentId(parentUri) }.getOrNull()
            }
        if (documentId.isNullOrBlank()) return emptyList()
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, documentId)
        }.getOrNull() ?: return emptyList()
        val queryResult = runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                val nameIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
                val mimeIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                    error("DocumentsProvider returned an incomplete child projection")
                }
                var truncated = false
                val result = buildList<QueriedFolderChild> {
                    while (cursor.moveToNext()) {
                        if (size >= MEDIA_STORE_SIDECAR_CACHE_MAX_CHILDREN_PER_DIRECTORY) {
                            // 旁车索引只服务于快速首屏，巨型目录交给后续按需解析
                            truncated = true
                            break
                        }
                        val childId = cursor.getString(idIndex)
                            ?.takeIf(String::isNotBlank)
                            ?: continue
                        val childName = cursor.getString(nameIndex)
                            ?.takeIf(String::isNotBlank)
                            ?: continue
                        val mimeType = cursor.getString(mimeIndex).orEmpty()
                        val isDirectory = mimeType ==
                            DocumentsContract.Document.MIME_TYPE_DIR
                        val extension = childName.substringAfterLast('.', "")
                            .lowercase(Locale.ROOT)
                        if (!isDirectory &&
                            extension !in MEDIA_STORE_SIDECAR_PRIORITY_EXTENSIONS &&
                            !mimeType.startsWith("audio/", ignoreCase = true) &&
                            extension !in LOCAL_AUDIO_FILE_EXTENSIONS
                        ) {
                            continue
                        }
                        add(
                            QueriedFolderChild(
                                documentUri = DocumentsContract.buildDocumentUriUsingTree(
                                    parentUri,
                                    childId
                                ),
                                displayName = childName,
                                mimeType = mimeType,
                                isDirectory = isDirectory
                            )
                        )
                    }
                }
                if (truncated) {
                    NPLogger.d(
                        TAG,
                        "MediaStore sidecar directory query truncated at " +
                            "$MEDIA_STORE_SIDECAR_CACHE_MAX_CHILDREN_PER_DIRECTORY: uri=$key"
                    )
                }
                SidecarChildrenQueryResult(
                    children = result,
                    truncated = truncated
                )
            }
        }
        if (queryResult.isFailure) {
            val error = queryResult.exceptionOrNull()
            NPLogger.d(TAG, "MediaStore sidecar index unavailable: ${error?.message}")
            return emptyList()
        }
        val queried = queryResult.getOrNull() ?: run {
            NPLogger.d(TAG, "MediaStore sidecar index unavailable: null cursor")
            return emptyList()
        }
        val result = queried.children
        if (queried.truncated) {
            // 截断结果只作为本次解析的有界快照缓存，避免每首歌重复触发 Binder
            // 查询，完整性仍保持未知，后续刷新会重新建立索引
            val remainingCapacity = (
                MEDIA_STORE_SIDECAR_CACHE_MAX_CHILDREN_TOTAL - cachedChildrenCount
            ).coerceAtLeast(0)
            if (childrenCache.size < MEDIA_STORE_SIDECAR_CACHE_MAX_DIRECTORIES &&
                result.size <= remainingCapacity
            ) {
                childrenCache[key] = result
                cachedChildrenCount += result.size
            }
            return result
        }
        val previousSize = childrenCache[key]?.size ?: 0
        if (key !in childrenCache &&
            childrenCache.size >= MEDIA_STORE_SIDECAR_CACHE_MAX_DIRECTORIES
        ) {
            NPLogger.d(
                TAG,
                "MediaStore sidecar directory cache limit reached: " +
                    "directories=${childrenCache.size}, uri=$key"
            )
            return result
        }
        val remainingCapacity = (
            MEDIA_STORE_SIDECAR_CACHE_MAX_CHILDREN_TOTAL -
                cachedChildrenCount + previousSize
            ).coerceAtLeast(0)
        val bounded = boundMediaStoreSidecarChildrenForCache(
            children = result,
            maxEntries = minOf(
                MEDIA_STORE_SIDECAR_CACHE_MAX_CHILDREN_PER_DIRECTORY,
                remainingCapacity
            )
        )
        if (bounded.size < result.size) {
            NPLogger.d(
                TAG,
                "MediaStore sidecar directory cache bounded: " +
                    "uri=$key, children=${result.size}, cached=${bounded.size}"
            )
        }
        if (bounded.size < result.size) {
            // 裁剪结果只能用于判断是否值得缓存，不能作为真实目录结果返回
            // 否则未命中的音频会被误判为不存在
            return result
        }
        childrenCache[key] = result
        cachedChildrenCount += result.size - previousSize
        return result
    }

    internal data class DirectorySidecarIndex(
        val directIndex: Map<String, String>,
        val lyricsIndex: Map<String, String>,
        val coverIndex: Map<String, String>,
        val metadataIndex: Map<String, String>,
        val audioIndex: Map<String, String>
    )

    internal companion object {
        const val TAG = "LocalAudioImport"
        fun splitStoragePath(path: String): List<String> {
            return Uri.decode(path)
                .split('/')
                .map(String::trim)
                .filter(String::isNotBlank)
        }
    }
}

internal class ManagedMediaStoreSidecarIndex(
    private val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
    private val treeDocumentId: String?
) {
    internal val publicationGate = ManagedDownloadCandidatePublicationGate(
        snapshot = snapshot,
        treeDocumentId = treeDocumentId
    )
    internal val audioByName = snapshot
        ?.audioEntries
        ?.associateBy { entry -> entry.name.lowercase(Locale.ROOT) }
        .orEmpty()

    fun shouldPublish(
        relativePath: String?,
        displayName: String,
        candidateReferences: Collection<String> = emptyList()
    ): Boolean {
        return publicationGate.evaluateRelativePath(
            relativePath = relativePath,
            displayName = displayName,
            candidateReferences = candidateReferences
        ).shouldPublish
    }

    fun resolve(
        relativePath: String?,
        displayName: String
    ): LocalKnownSidecarReferences? {
        val currentSnapshot = snapshot ?: return null
        if (!shouldPublish(relativePath = relativePath, displayName = displayName)) {
            return null
        }
        if (!ManagedDownloadStorage.isManagedDownloadRelativePath(relativePath, treeDocumentId)) {
            return null
        }
        val audio = audioByName[displayName.lowercase(Locale.ROOT)] ?: return null
        val metadataReference = currentSnapshot.metadataEntriesByAudioName[audio.name]
            ?.reference
            ?.takeIf(String::isNotBlank)
            ?.takeUnless(::isMediaStoreSidecarReference)
        val coverReference = currentSnapshot.metadataByAudioName[audio.name]
            ?.coverPath
            ?.takeIf(currentSnapshot.knownReferences::contains)
        if (metadataReference == null && coverReference == null) return null
        return LocalKnownSidecarReferences(
            lyrics = NearbyLyricReferences(
                original = null,
                translated = null,
                romanized = null
            ),
            metadata = metadataReference,
            cover = coverReference
        )
    }
}

internal enum class ManagedDownloadCandidatePublication {
    NON_MANAGED,
    FINALIZED,
    WITHHELD;

    val shouldPublish: Boolean
        get() = this != WITHHELD
}

internal class ManagedDownloadCandidatePublicationGate(
    private val snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
    private val treeDocumentId: String?
) {
    internal val audioByName = snapshot
        ?.audioEntries
        ?.associateBy { entry -> entry.name.lowercase(Locale.ROOT) }
        .orEmpty()
    internal val audioByReference = snapshot
        ?.audioEntries
        ?.flatMap { entry ->
            listOf(entry.reference, entry.mediaUri, entry.localFilePath)
                .mapNotNull { reference -> reference?.takeIf(String::isNotBlank) }
                .map { reference -> reference to entry }
        }
        ?.toMap()
        .orEmpty()
    private val pendingAudioNames = snapshot?.let { current ->
        (current.pendingAudioEntries + current.audioEntries.filter { it.isPendingAudioWrite })
            .mapTo(hashSetOf()) { ManagedDownloadTreeNaming.canonicalLookupName(it.logicalName) }
    }.orEmpty()

    fun evaluateRelativePath(
        relativePath: String?,
        displayName: String,
        candidateReferences: Collection<String> = emptyList()
    ): ManagedDownloadCandidatePublication {
        return evaluate(
            isInsideManagedRoot = ManagedDownloadStorage.isManagedDownloadRelativePath(
                relativePath,
                treeDocumentId
            ),
            displayName = displayName,
            candidateReferences = candidateReferences
        )
    }

    fun evaluate(
        isInsideManagedRoot: Boolean,
        displayName: String,
        candidateReferences: Collection<String> = emptyList()
    ): ManagedDownloadCandidatePublication {
        if (ManagedDownloadPendingAudioWriteNames.isArtifactName(displayName)) {
            return ManagedDownloadCandidatePublication.WITHHELD
        }
        val entryByReference = candidateReferences.asSequence()
            .mapNotNull { reference -> audioByReference[reference] }
            .firstOrNull()
        val entry = entryByReference ?: if (isInsideManagedRoot) {
            audioByName[displayName.lowercase(Locale.ROOT)]
        } else {
            null
        }
        if (!isInsideManagedRoot && entry == null) {
            return ManagedDownloadCandidatePublication.NON_MANAGED
        }
        val currentSnapshot = snapshot ?: return ManagedDownloadCandidatePublication.WITHHELD
        if (!currentSnapshot.rootEntriesComplete) {
            return ManagedDownloadCandidatePublication.WITHHELD
        }
        val canonicalAudioName = ManagedDownloadTreeNaming.canonicalLookupName(entry?.name ?: displayName)
        val metadata = entry?.let { ManagedDownloadStorage.metadataForAudioEntry(currentSnapshot, it) }
            ?: currentSnapshot.metadataByCanonicalAudioName[canonicalAudioName]
            ?: currentSnapshot.metadataByDeclaredAudioName[canonicalAudioName]
        val hasDownloadEvidence = metadata != null ||
            canonicalAudioName in currentSnapshot.metadataEntriesByCanonicalAudioName ||
            canonicalAudioName in currentSnapshot.pendingMetadataByCanonicalAudioName ||
            canonicalAudioName in pendingAudioNames
        // 下载目录也可以放普通音频，不能把没有下载凭据当成下载尚未完成
        if (!hasDownloadEvidence && entry?.isPendingAudioWrite != true) {
            return ManagedDownloadCandidatePublication.NON_MANAGED
        }
        if (entry == null) {
            return ManagedDownloadCandidatePublication.WITHHELD
        }
        return if (isFinalizedDownloadedAudioEntry(
                rootEntriesComplete = currentSnapshot.rootEntriesComplete,
                isPendingAudioWrite = entry.isPendingAudioWrite,
                metadata = metadata
            )
        ) {
            ManagedDownloadCandidatePublication.FINALIZED
        } else {
            ManagedDownloadCandidatePublication.WITHHELD
        }
    }
}

internal fun buildNearbySidecarCopyPlans(
    sourceFile: File,
    targetFile: File,
    lyricExtensions: List<String>,
    imageExtensions: List<String>,
    coverNames: List<String>
): List<SidecarCopyPlan> {
    val sourceDir = sourceFile.parentFile ?: return emptyList()
    val targetDir = targetFile.parentFile ?: return emptyList()
    val sourceBase = sourceFile.nameWithoutExtension
    val targetBase = targetFile.nameWithoutExtension
    val targetCoverDir = File(targetDir, "Covers")

    return buildList {
        fun addIfExists(source: File, target: File) {
            if (source.exists()) {
                add(SidecarCopyPlan(source = source, target = target))
            }
        }

        val nearbyLyricFiles = LocalMediaSupport.findNearbyLyricFiles(
            file = sourceFile,
            extensions = lyricExtensions
        )

        fun addSelectedLyricSidecar(source: File?) {
            source ?: return
            val suffix = source.name
                .removePrefix(sourceBase)
                .takeIf { it.startsWith('.') || it.startsWith('_') }
                ?: return
            addIfExists(source, File(targetDir, "$targetBase$suffix"))
        }

        addSelectedLyricSidecar(nearbyLyricFiles.original)
        addSelectedLyricSidecar(nearbyLyricFiles.translated)
        addSelectedLyricSidecar(nearbyLyricFiles.romanized)

        imageExtensions.forEach { extension ->
            addIfExists(File(sourceDir, "$sourceBase.$extension"), File(targetDir, "$targetBase.$extension"))
        }

        coverNames.forEach { name ->
            imageExtensions.forEach { extension ->
                addIfExists(
                    File(sourceDir, "$name.$extension"),
                    File(targetCoverDir, "$targetBase.$extension")
                )
            }
        }

        val sourceCoverDir = File(sourceDir, "Covers")
        imageExtensions.forEach { extension ->
            addIfExists(File(sourceCoverDir, "$sourceBase.$extension"), File(targetDir, "$targetBase.$extension"))
        }
    }
}
