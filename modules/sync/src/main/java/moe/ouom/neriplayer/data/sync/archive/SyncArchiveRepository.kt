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

class SyncArchiveRepository(cacheDirectory: File, private val beforeNormalization: (SyncData) -> Unit = {}) {
    private val cache = SyncArchiveCache(cacheDirectory)
    val playbackDatasets by lazy { FileSyncPlaybackDatasetStore(File(cacheDirectory, "playback-staging")) }
    var lastReferencedPaths: Set<String> = emptySet()
        private set

    suspend fun prepareCancellable(data: SyncData): SyncPreparedArchive {
        val context = coroutineContext
        return prepare(data) { context.ensureActive() }
    }

    suspend fun prepareCancellable(dataset: SyncDataset): SyncPreparedArchive {
        val context = coroutineContext
        val chunks = ArrayList<SyncArchiveRef>()
        val objects = LinkedHashMap<String, SyncArchiveRef>()
        val chunker = SyncContentChunker { raw ->
            context.ensureActive()
            val ref = cache.store(raw, index = false)
            chunks.add(ref)
            objects[ref.path] = ref
        }
        val records = chunker.use { SyncArchiveRecords.writeDataset(dataset, it) { context.ensureActive() } }
        context.ensureActive()
        val root = buildTree(chunks, objects) { context.ensureActive() }
        val manifest = SyncArchiveManifest(3, SyncArchiveRecords.header(dataset.data), root,
            records, chunker.totalBytes, chunks.size.toLong())
        return SyncPreparedArchive(SyncArchiveCodec.manifest(manifest), objects.keys.toSet(), cache, objects.values.toList())
    }

    fun prepare(data: SyncData, checkActive: () -> Unit = {}): SyncPreparedArchive {
        val chunks = ArrayList<SyncArchiveRef>()
        val objects = LinkedHashMap<String, SyncArchiveRef>()
        val chunker = SyncContentChunker { raw ->
            checkActive()
            val ref = cache.store(raw, index = false)
            chunks.add(ref)
            objects[ref.path] = ref
        }
        val records = chunker.use { SyncArchiveRecords.write(data, it, checkActive) }
        checkActive()
        val root = buildTree(chunks, objects, checkActive)
        val manifest = SyncArchiveManifest(3, SyncArchiveRecords.header(data), root,
            records, chunker.totalBytes, chunks.size.toLong())
        return SyncPreparedArchive(SyncArchiveCodec.manifest(manifest), objects.keys.toSet(), cache, objects.values.toList())
    }

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

    suspend fun read(content: ByteArray, fetch: suspend (String) -> Result<ByteArray>): Result<SyncData> {
        lastReferencedPaths = emptySet()
        return try {
            val manifest = SyncArchiveCodec.readManifest(content)
            val refs = resolve(manifest, fetch)
            val context = coroutineContext
            val data = SyncArchiveInputStream(refs.data, cache).use {
                SyncArchiveRecords.read(manifest.header, it, manifest.recordCount, manifest.rawDataBytes, beforeNormalization) { context.ensureActive() }
            }
            lastReferencedPaths = refs.paths
            cache.trim(refs.paths)
            Result.success(data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun readDataset(
        content: ByteArray,
        store: SyncPlaybackDatasetStore,
        sanitizeTrack: (SyncTrackStat) -> SyncTrackStat?,
        sanitizeBucket: (SyncPlaybackStatBucket) -> SyncPlaybackStatBucket?,
        fetch: suspend (String) -> Result<ByteArray>
    ): Result<SyncDataset> {
        lastReferencedPaths = emptySet()
        return try {
            val manifest = SyncArchiveCodec.readManifest(content)
            val refs = resolve(manifest, fetch)
            val dataset = decodeDataset(manifest, refs, store, sanitizeTrack, sanitizeBucket)
            Result.success(approveDataset(dataset, refs.paths))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun decodeDataset(
        manifest: SyncArchiveManifest, refs: Resolved, store: SyncPlaybackDatasetStore,
        sanitizeTrack: (SyncTrackStat) -> SyncTrackStat?, sanitizeBucket: (SyncPlaybackStatBucket) -> SyncPlaybackStatBucket?
    ): SyncDataset {
        val context = coroutineContext
        return store.newSink().use { sink ->
            SyncArchiveInputStream(refs.data, cache).use {
                SyncArchiveRecords.readDataset(manifest.header, it, manifest.recordCount,
                    manifest.rawDataBytes, sink, sanitizeTrack, sanitizeBucket, beforeNormalization) { context.ensureActive() }
            }
        }
    }

    private fun approveDataset(dataset: SyncDataset, paths: Set<String>): SyncDataset {
        try {
            cache.trim(paths)
            lastReferencedPaths = paths
            return dataset
        } catch (error: Exception) {
            try { dataset.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    internal suspend fun visit(content: ByteArray, fetch: suspend (String) -> Result<ByteArray>, visitor: (Int, ByteArray) -> Unit) {
        val manifest = SyncArchiveCodec.readManifest(content)
        val refs = resolve(manifest, fetch)
        val context = coroutineContext
        SyncArchiveInputStream(refs.data, cache).use {
            SyncArchiveRecords.visit(it, manifest.recordCount, manifest.rawDataBytes, { context.ensureActive() }, visitor)
        }
    }

    private suspend fun resolve(manifest: SyncArchiveManifest, fetch: suspend (String) -> Result<ByteArray>): Resolved {
        val resolver = SyncArchiveTreeReader(cache, fetch, manifest.chunkCount)
        manifest.root?.let { resolver.walk(it, 0) }
        require(resolver.data.size.toLong() == manifest.chunkCount) { "Sync chunk count mismatch" }
        require(resolver.data.sumOf { it.rawBytes.toLong() } == manifest.rawDataBytes) { "Sync raw byte count mismatch" }
        return Resolved(resolver.data, resolver.paths)
    }

    private data class Resolved(val data: List<SyncArchiveRef>, val paths: Set<String>)

    companion object {
        const val MANIFEST_FILE_NAME = "neriplayer-sync-v3.manifest"
        fun isManifest(content: ByteArray): Boolean = SyncArchiveCodec.isManifest(content)
    }
}

private class SyncArchiveTreeReader(
    private val cache: SyncArchiveCache,
    private val fetch: suspend (String) -> Result<ByteArray>,
    private val expectedChunks: Long
) {
    val data = ArrayList<SyncArchiveRef>()
    val paths = LinkedHashSet<String>()
    private var visits = 0L

    suspend fun walk(ref: SyncArchiveRef, depth: Int) {
        coroutineContext.ensureActive()
        validateTraversal(depth)
        SyncArchiveCodec.validate(ref)
        val content = cache.cachedCompressed(ref) ?: fetch(ref.path).getOrThrow().also { cache.save(ref, it) }
        paths.add(ref.path)
        if (ref.index) readChildren(ref, content, depth) else addData(ref)
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

    private fun addData(ref: SyncArchiveRef) {
        require(data.size.toLong() < expectedChunks) { "Unexpected sync data chunk" }
        data.add(ref)
    }
}
