@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import moe.ouom.neriplayer.data.model.sync.SyncData
import java.io.Closeable

internal object SyncArchiveLimits {
    const val MAX_OBJECT_BYTES = 2 * 1024 * 1024
    const val MAX_RAW_BYTES = 1024 * 1024
    const val MAX_RECORD_BYTES = 64 * 1024 * 1024
    const val INDEX_FANOUT = 512
    const val MAX_TREE_DEPTH = 8
    const val CACHE_BYTES = 256L * 1024 * 1024
    const val MAX_LEGACY_SOURCE_RAW_BYTES = 32L * 1024 * 1024
    const val MAX_LEGACY_SOURCE_CHUNKS = 1024L
}

@Serializable
internal data class SyncArchiveRef(
    @ProtoNumber(1) val hash: String,
    @ProtoNumber(2) val rawHash: String,
    @ProtoNumber(3) val rawBytes: Int,
    @ProtoNumber(4) val compressedBytes: Int,
    @ProtoNumber(5) val index: Boolean = false
) {
    val path: String get() = "neriplayer-sync-v3-$hash.zst"
}

@Serializable
internal data class SyncArchiveIndex(@ProtoNumber(1) val children: List<SyncArchiveRef>)

@Serializable
internal data class SyncArchiveManifest(
    @ProtoNumber(1) val protocol: Int = 3,
    @ProtoNumber(2) val header: SyncData,
    @ProtoNumber(3) val root: SyncArchiveRef?,
    @ProtoNumber(4) val recordCount: Long,
    @ProtoNumber(5) val rawDataBytes: Long,
    @ProtoNumber(6) val chunkCount: Long,
    @ProtoNumber(7) val legacyLyrics: SyncLegacyLyricSource? = null
)

class SyncArchiveObject(val path: String, val content: ByteArray)

class SyncPreparedArchive internal constructor(
    val content: ByteArray,
    val paths: Set<String>,
    private val cache: SyncArchiveCache,
    private val refs: List<SyncArchiveRef>
) : Closeable {
    val objects: Sequence<SyncArchiveObject> get() = objects(emptySet())
    fun objects(excludingPaths: Set<String>): Sequence<SyncArchiveObject> = refs.asSequence()
        .filter { it.path !in excludingPaths }.map {
        SyncArchiveObject(it.path, cache.readCompressed(it))
    }
    override fun close() { cache.trim(paths) }
}
