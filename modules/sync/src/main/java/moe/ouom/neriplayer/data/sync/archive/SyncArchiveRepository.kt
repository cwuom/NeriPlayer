@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncData
import java.io.File
import kotlin.coroutines.coroutineContext
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Bridge
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Format
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataLimits
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataBudget

class SyncArchiveRepository private constructor(
    cacheDirectory: File,
    private val beforeNormalization: (SyncData) -> Unit,
    private val legacyRecovery: SyncLegacyLyricRecovery?,
    private val metadataLimits: SyncArchiveMetadataLimits
) {
    constructor(cacheDirectory: File, beforeNormalization: (SyncData) -> Unit = {}) :
        this(cacheDirectory, beforeNormalization, null, SyncArchiveMetadataLimits())

    constructor(cacheDirectory: File, legacyRecovery: SyncLegacyLyricRecovery, beforeNormalization: (SyncData) -> Unit = {}) :
        this(cacheDirectory, beforeNormalization, legacyRecovery, SyncArchiveMetadataLimits())

    internal constructor(cacheDirectory: File,
        metadataLimits: SyncArchiveMetadataLimits,
        legacyRecovery: SyncLegacyLyricRecovery? = null,
        beforeNormalization: (SyncData) -> Unit = {}) :
        this(cacheDirectory, beforeNormalization, legacyRecovery, metadataLimits)

    private val cache = SyncArchiveCache(cacheDirectory)
    private val bridge = SyncArchiveV4Bridge(cacheDirectory, cache)
    private val legacyArchive = SyncLegacyLyricArchive(cache, metadataLimits)
    private val retainedSource = SyncRetainedLegacySource(cacheDirectory)
    private var legacySource: SyncLegacyLyricSource? = null
    private var capturedLegacyObjects = emptyList<SyncArchiveRef>()
    val playbackDatasets by lazy { FileSyncPlaybackDatasetStore(File(cacheDirectory, "playback-staging")) }
    var lastReferencedPaths: Set<String> = emptySet()
        private set

    fun captureLegacyLyrics(data: SyncData) {
        val captured = legacyArchive.capture(data)
        retainedSource.invalidate()
        legacySource = captured?.source
        capturedLegacyObjects = captured?.objects.orEmpty()
    }

    suspend fun prepareCancellable(data: SyncData): SyncPreparedArchive {
        val context = coroutineContext
        return prepare(data) { context.ensureActive() }
    }

    suspend fun prepareCancellable(dataset: SyncDataset): SyncPreparedArchive = prepareSafely {
        val context = coroutineContext
        val original = prepareOriginal(dataset) { context.ensureActive() }
        bridge.prepare(original, retainedSource.resolve(legacySource), retainedSource.checksum, lastReferencedPaths) { context.ensureActive() }
    }

    private suspend fun prepareOriginal(dataset: SyncDataset, checkActive: () -> Unit): SyncPreparedArchive {
        val budget = SyncArchiveMetadataBudget(metadataLimits)
        val chunks = ArrayList<SyncArchiveRef>()
        val objects = LinkedHashMap<String, SyncArchiveRef>()
        val chunker = SyncContentChunker { raw ->
            checkActive()
            val ref = cache.store(raw, index = false)
            chunks.add(ref)
            objects[ref.path] = ref
        }
        val records = chunker.use { SyncArchiveRecords.writeDataset(dataset, it, checkActive, budget) }
        verifyLegacyBudget(budget, checkActive)
        checkActive()
        val root = buildTree(chunks, objects, checkActive)
        addLegacyObjects(objects)
        val manifest = SyncArchiveManifest(3, SyncArchiveRecords.header(dataset.data), root,
            records, chunker.totalBytes, chunks.size.toLong(), legacySource)
        return SyncPreparedArchive(SyncArchiveCodec.manifest(manifest), protectedPaths(objects.keys), cache, objects.values.toList())
    }

    fun prepare(data: SyncData, checkActive: () -> Unit = {}): SyncPreparedArchive = prepareSafely {
        bridge.prepare(prepareOriginal(data, checkActive), retainedSource.resolve(legacySource), retainedSource.checksum, lastReferencedPaths, checkActive)
    }

    internal fun prepareOriginal(data: SyncData, checkActive: () -> Unit = {}): SyncPreparedArchive {
        val budget = SyncArchiveMetadataBudget(metadataLimits)
        val chunks = ArrayList<SyncArchiveRef>()
        val objects = LinkedHashMap<String, SyncArchiveRef>()
        val chunker = SyncContentChunker { raw ->
            checkActive()
            val ref = cache.store(raw, index = false)
            chunks.add(ref)
            objects[ref.path] = ref
        }
        val records = chunker.use { SyncArchiveRecords.write(data, it, checkActive, budget) }
        verifyLegacyBudget(budget, checkActive)
        checkActive()
        val root = buildTree(chunks, objects, checkActive)
        addLegacyObjects(objects)
        val manifest = SyncArchiveManifest(3, SyncArchiveRecords.header(data), root,
            records, chunker.totalBytes, chunks.size.toLong(), legacySource)
        return SyncPreparedArchive(SyncArchiveCodec.manifest(manifest), protectedPaths(objects.keys), cache, objects.values.toList())
    }

    private fun verifyLegacyBudget(budget: SyncArchiveMetadataBudget, checkActive: () -> Unit) {
        val source = legacySource ?: return
        val input = retainedSource.resolve(source)?.inputStream() ?: bridge.openLegacy(source.root)
        input.use { legacyArchive.verifyBudget(source, it, budget, checkActive) }
    }

    private inline fun prepareSafely(block: () -> SyncPreparedArchive): SyncPreparedArchive = try {
        block()
    } catch (failure: Throwable) {
        cleanFailedOperation(failure, lastReferencedPaths)
        throw failure
    }

    private fun cleanFailedOperation(failure: Throwable, rollbackPaths: Set<String>) {
        try {
            cache.trim(rollbackPaths + capturedLegacyObjects.map { it.path } + listOfNotNull(legacySource?.root?.path))
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
    }

    private fun addLegacyObjects(objects: MutableMap<String, SyncArchiveRef>) {
        capturedLegacyObjects.forEach { objects[it.path] = it }
    }

    private fun protectedPaths(paths: Set<String>): Set<String> = paths + listOfNotNull(legacySource?.root?.path)

    private fun buildTree(chunks: List<SyncArchiveRef>, objects: MutableMap<String, SyncArchiveRef>, checkActive: () -> Unit): SyncArchiveRef? {
        var level = chunks
        while (level.size > 1) {
            level = level.chunked(SyncArchiveLimits.INDEX_FANOUT).map { children ->
                checkActive()
                cache.store(ProtoBuf.encodeToByteArray(SyncArchiveIndex(children)), index = true).also { objects[it.path] = it }
            }
        }
        return level.firstOrNull()
    }

    suspend fun read(content: ByteArray, fetch: suspend (String) -> Result<ByteArray>): Result<SyncData> =
        read(content, verifyRemoteObjects = false, fetch = fetch)

    suspend fun read(content: ByteArray, verifyRemoteObjects: Boolean,
        fetch: suspend (String) -> Result<ByteArray>): Result<SyncData> {
        val rollbackPaths = lastReferencedPaths
        lastReferencedPaths = emptySet()
        return try {
            val budget = SyncArchiveMetadataBudget(metadataLimits)
            load(content, verifyRemoteObjects, fetch).use { loaded ->
                val context = coroutineContext
                val data = loaded.main().use {
                    SyncArchiveRecords.read(loaded.manifest.header, it, loaded.manifest.recordCount,
                        loaded.manifest.rawDataBytes, beforeNormalization, budget) { context.ensureActive() }
                }
                val restored = restorePreservedLyrics(data, recoverLegacyLyrics(loaded, budget))
                retainLegacy(loaded)
                cache.trim(loaded.paths)
                lastReferencedPaths = loaded.paths
                Result.success(restored)
            }
        } catch (cancelled: CancellationException) {
            cleanFailedOperation(cancelled, rollbackPaths)
            throw cancelled
        } catch (error: Exception) {
            cleanFailedOperation(error, rollbackPaths)
            Result.failure(error)
        }
    }

    suspend fun readDataset(
        content: ByteArray,
        store: SyncPlaybackDatasetStore,
        sanitizeTrack: (SyncTrackStat) -> SyncTrackStat?,
        sanitizeBucket: (SyncPlaybackStatBucket) -> SyncPlaybackStatBucket?,
        fetch: suspend (String) -> Result<ByteArray>
    ): Result<SyncDataset> = readDataset(content, store, sanitizeTrack, sanitizeBucket,
        verifyRemoteObjects = false, fetch = fetch)

    suspend fun readDataset(
        content: ByteArray,
        store: SyncPlaybackDatasetStore,
        sanitizeTrack: (SyncTrackStat) -> SyncTrackStat?,
        sanitizeBucket: (SyncPlaybackStatBucket) -> SyncPlaybackStatBucket?,
        verifyRemoteObjects: Boolean,
        fetch: suspend (String) -> Result<ByteArray>
    ): Result<SyncDataset> {
        val rollbackPaths = lastReferencedPaths
        lastReferencedPaths = emptySet()
        return try {
            val budget = SyncArchiveMetadataBudget(metadataLimits)
            load(content, verifyRemoteObjects, fetch).use { loaded ->
                val dataset = decodeDataset(loaded, store, sanitizeTrack, sanitizeBucket, budget)
                try {
                    val data = restorePreservedLyrics(dataset.data, recoverLegacyLyrics(loaded, budget))
                    retainLegacy(loaded)
                    val restored = if (data === dataset.data) dataset else SyncDataset(data, dataset.playback,
                        dataset.capturedPlaybackRevision, dataset.playbackMatchesCaptured)
                    Result.success(approveDataset(restored, loaded.paths))
                } catch (failure: Throwable) {
                    try { dataset.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) {
            cleanFailedOperation(cancelled, rollbackPaths)
            throw cancelled
        } catch (error: Exception) {
            cleanFailedOperation(error, rollbackPaths)
            Result.failure(error)
        }
    }

    private suspend fun load(content: ByteArray, verifyRemote: Boolean,
        fetch: suspend (String) -> Result<ByteArray>): SyncArchiveV4Bridge.Loaded {
        val context = coroutineContext
        if (SyncArchiveV4Format.isManifest(content)) {
            return bridge.load(content, verifyRemote, fetch) { context.ensureActive() }
        }
        val manifest = SyncArchiveCodec.readManifest(content)
        val main = resolve(manifest, verifyRemote, fetch)
        val workspace = bridge.newWorkspace()
        try {
            val legacy = manifest.legacyLyrics?.let { materializeLegacy(it, workspace.directory, verifyRemote, fetch) }
            return SyncArchiveV4Bridge.Loaded(manifest, main.paths + legacy?.second.orEmpty(),
                { SyncArchiveInputStream(main.data, cache) }, legacy?.first, workspace)
        } catch (failure: Throwable) {
            try { workspace.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    suspend fun verifyRemoteClosure(content: ByteArray, fetch: suspend (String) -> Result<ByteArray>): Result<Set<String>> = try {
        load(content, true, fetch).use { Result.success(it.paths) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun materializeLegacy(source: SyncLegacyLyricSource, workspace: File, verifyRemote: Boolean,
        fetch: suspend (String) -> Result<ByteArray>): Pair<File, Set<String>> {
        val refs = resolve(source.manifest(), verifyRemote, fetch)
        val file = File(workspace, "legacy.records")
        SyncArchiveInputStream(refs.data, cache).use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        return file to refs.paths
    }

    private suspend fun recoverLegacyLyrics(loaded: SyncArchiveV4Bridge.Loaded, budget: SyncArchiveMetadataBudget): List<SyncSong> {
        val source = loaded.manifest.legacyLyrics ?: return emptyList()
        coroutineContext.ensureActive()
        val recovery = legacyRecovery
        if (recovery != null && recovery.isCompleted(source.hash)) {
            return verifyPreservedLyrics(recovery.preservedLyrics(), budget)
        }
        return recoverLegacySource(source, requireNotNull(loaded.legacy), budget, recovery)
    }

    private suspend fun recoverLegacySource(source: SyncLegacyLyricSource, file: File,
        budget: SyncArchiveMetadataBudget, recovery: SyncLegacyLyricRecovery?): List<SyncSong> {
        val context = coroutineContext
        val data = file.inputStream().use {
            legacyArchive.read(source, it, budget) { context.ensureActive() }
        }
        val accounted = data.lyricOverrides.toHashSet()
        verifyPreservedLyrics(recovery?.preservedLyrics().orEmpty(), budget, accounted)
        context.ensureActive()
        if (recovery == null) beforeNormalization(data) else recovery.recover(source.hash, data)
        val preserved = verifyPreservedLyrics(recovery?.preservedLyrics().orEmpty(), budget, accounted)
        context.ensureActive()
        return data.lyricOverrides + preserved
    }

    private suspend fun verifyPreservedLyrics(candidates: List<SyncSong>, budget: SyncArchiveMetadataBudget,
        accounted: MutableSet<SyncSong> = HashSet()): List<SyncSong> {
        val context = coroutineContext
        for (candidate in candidates) {
            context.ensureActive()
            // 同一份已恢复候选只占一次容量，不同全文仍分别检查
            if (accounted.add(candidate)) budget.encode(SyncSong.serializer(), candidate) { context.ensureActive() }
        }
        context.ensureActive()
        return candidates
    }

    private fun retainLegacy(loaded: SyncArchiveV4Bridge.Loaded) {
        val source = loaded.manifest.legacyLyrics
        retainedSource.retain(source, loaded.legacy)
        legacySource = source
        capturedLegacyObjects = emptyList()
    }

    private fun restorePreservedLyrics(data: SyncData, candidates: List<SyncSong>): SyncData {
        if (candidates.isEmpty()) return data
        val preserved = candidates.map { SyncSongLyricMergePolicy.prepareLegacy(it) }
        return SyncSongLyricMergePolicy.converge(data.copy(lyricOverrides = data.lyricOverrides + preserved))
    }

    private suspend fun decodeDataset(
        loaded: SyncArchiveV4Bridge.Loaded, store: SyncPlaybackDatasetStore,
        sanitizeTrack: (SyncTrackStat) -> SyncTrackStat?, sanitizeBucket: (SyncPlaybackStatBucket) -> SyncPlaybackStatBucket?,
        budget: SyncArchiveMetadataBudget
    ): SyncDataset {
        val context = coroutineContext
        val manifest = loaded.manifest
        return store.newSink().use { sink ->
            loaded.main().use {
                SyncArchiveRecords.readDataset(manifest.header, it, manifest.recordCount,
                    manifest.rawDataBytes, sink, sanitizeTrack, sanitizeBucket, beforeNormalization, budget) { context.ensureActive() }
            }
        }
    }

    private fun approveDataset(dataset: SyncDataset, paths: Set<String>): SyncDataset {
        cache.trim(paths)
        lastReferencedPaths = paths
        return dataset
    }

    internal suspend fun visit(content: ByteArray, fetch: suspend (String) -> Result<ByteArray>, visitor: (Int, ByteArray) -> Unit) {
        load(content, false, fetch).use { loaded ->
            val context = coroutineContext
            loaded.main().use {
                SyncArchiveRecords.visit(it, loaded.manifest.recordCount, loaded.manifest.rawDataBytes, { context.ensureActive() }, visitor)
            }
        }
    }

    private suspend fun resolve(manifest: SyncArchiveManifest, verifyRemote: Boolean,
        fetch: suspend (String) -> Result<ByteArray>): Resolved {
        val resolver = SyncArchiveTreeReader(cache, fetch, manifest.chunkCount, manifest.rawDataBytes, verifyRemote)
        manifest.root?.let { resolver.walk(it, 0) }
        require(resolver.data.size.toLong() == manifest.chunkCount) { "Sync chunk count mismatch" }
        require(resolver.data.sumOf { it.rawBytes.toLong() } == manifest.rawDataBytes) { "Sync raw byte count mismatch" }
        return Resolved(resolver.data, resolver.paths)
    }

    private data class Resolved(val data: List<SyncArchiveRef>, val paths: Set<String>)

    companion object {
        const val MANIFEST_FILE_NAME = "neriplayer-sync-v3.manifest"
        fun isManifest(content: ByteArray): Boolean = content.size >= 8 && String(content, 0, 6, Charsets.US_ASCII) == "NPSYNC"
        fun protocolVersion(content: ByteArray): Int = if (SyncArchiveV4Format.isManifest(content))
            SyncArchiveV4Format.readManifest(content).protocol else SyncArchiveCodec.readManifest(content).protocol
        fun webDavPublicationContent(content: ByteArray): ByteArray = SyncArchiveV4Format.manifest(
            SyncArchiveV4Format.readManifest(content).copy(publicationId = java.util.UUID.randomUUID().toString())
        )
        internal fun originalManifest(content: ByteArray): SyncArchiveManifest = if (SyncArchiveV4Format.isManifest(content))
            SyncArchiveV4Format.readManifest(content).original else SyncArchiveCodec.readManifest(content)
    }
}

private class SyncArchiveTreeReader(
    private val cache: SyncArchiveCache,
    private val fetch: suspend (String) -> Result<ByteArray>,
    private val expectedChunks: Long,
    private var remainingBytes: Long,
    private val verifyRemote: Boolean
) {
    val data = ArrayList<SyncArchiveRef>()
    val paths = LinkedHashSet<String>()
    private var visits = 0L

    suspend fun walk(ref: SyncArchiveRef, depth: Int) {
        coroutineContext.ensureActive()
        validateTraversal(depth)
        SyncArchiveCodec.validate(ref)
        if (ref.index) {
            readChildren(ref, readObject(ref), depth)
        } else {
            validateLeaf(ref)
            readObject(ref)
            data.add(ref)
        }
    }

    private suspend fun readObject(ref: SyncArchiveRef): ByteArray {
        val cached = if (verifyRemote && ref.path !in paths) null else cache.cachedCompressed(ref)
        val content = cached ?: fetch(ref.path).getOrThrow().also { cache.save(ref, it) }
        paths.add(ref.path)
        return content
    }

    private fun validateTraversal(depth: Int) {
        require(depth <= SyncArchiveLimits.MAX_TREE_DEPTH) { "Sync index nesting exceeds safe budget" }
        visits++
        require(visits <= Math.multiplyExact(expectedChunks, SyncArchiveLimits.MAX_TREE_DEPTH + 1L)) { "Sync index contains excessive references" }
    }

    private suspend fun readChildren(ref: SyncArchiveRef, content: ByteArray, depth: Int) {
        val node = ProtoBuf.decodeFromByteArray<SyncArchiveIndex>(SyncArchiveCodec.decodeObject(ref, content))
        require(node.children.size in 1..SyncArchiveLimits.INDEX_FANOUT) { "Invalid sync index fanout" }
        node.children.forEach { walk(it, depth + 1) }
    }

    private fun validateLeaf(ref: SyncArchiveRef) {
        require(data.size.toLong() < expectedChunks) { "Unexpected sync data chunk" }
        require(ref.rawBytes.toLong() <= remainingBytes) { "Sync stream exceeds declared byte budget" }
        remainingBytes -= ref.rawBytes
    }
}
