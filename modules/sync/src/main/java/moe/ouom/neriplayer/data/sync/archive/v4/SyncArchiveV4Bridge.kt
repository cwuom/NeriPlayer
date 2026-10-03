@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive.v4

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveCache
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveCodec
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveIndex
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveInputStream
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveLimits
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveManifest
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveObject
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRef
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveWorkspace
import moe.ouom.neriplayer.data.sync.archive.SyncPreparedArchive
import moe.ouom.neriplayer.data.sync.archive.compact.SyncArchiveCompactRecords

internal class SyncArchiveV4Bridge(private val directory: File, private val originalCache: SyncArchiveCache) {
    private val objects = SyncArchiveV4Objects(directory)
    init { SyncArchiveWorkspace.recoverAbandoned(directory) }

    fun prepare(original: SyncPreparedArchive, retainedLegacy: File?, retainedChecksum: String?, rollbackPaths: Set<String>, checkActive: () -> Unit): SyncPreparedArchive {
        val manifest = SyncArchiveCodec.readManifest(original.content)
        val legacyProtection = if (retainedLegacy == null) localRefs(manifest.legacyLyrics?.root).map { it.path }.toSet() else emptySet()
        val workspace = newWorkspace()
        var publishedPaths = emptySet<String>()
        var failure: Throwable? = null
        try {
            val prepared = encode(manifest, retainedLegacy, retainedChecksum, workspace.directory, legacyProtection, checkActive)
            publishedPaths = prepared.paths
            return prepared
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                workspace.close()
                originalCache.trim(legacyProtection + if (failure == null) publishedPaths else rollbackPaths)
            } catch (cleanup: Throwable) {
                val primary = failure
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    private fun encode(manifest: SyncArchiveManifest, retainedLegacy: File?, retainedChecksum: String?,
        workspace: File, legacyProtection: Set<String>, checkActive: () -> Unit): SyncPreparedArchive {
        val mainDigest = MessageDigest.getInstance("SHA-256")
        val legacyDigest = MessageDigest.getInstance("SHA-256")
        val main = SyncArchiveInputStream(localLeaves(manifest.root), originalCache)
        return DigestInputStream(main, mainDigest).use { mainInput ->
            val legacy = retainedLegacy?.inputStream() ?: SyncArchiveInputStream(localLeaves(manifest.legacyLyrics?.root), originalCache)
            DigestInputStream(legacy, legacyDigest).use { legacyInput ->
                SyncArchiveCompactRecords.pack(mainInput, legacyInput, workspace, checkActive).use { packed ->
                    val mainHash = hex(mainDigest.digest())
                    val legacyHash = hex(legacyDigest.digest())
                    verifyRetainedChecksum(retainedLegacy, retainedChecksum, legacyHash)
                    publishPacked(manifest, packed, mainHash, legacyHash, legacyProtection, checkActive)
                }
            }
        }
    }

    private fun verifyRetainedChecksum(retained: File?, checksum: String?, hash: String) {
        if (retained != null) {
            require(hash == checksum) { "Retained legacy lyric checksum mismatch" }
        }
    }

    private fun publishPacked(manifest: SyncArchiveManifest, packed: SyncArchiveCompactRecords.Packed,
        mainHash: String, legacyHash: String, legacyProtection: Set<String>, checkActive: () -> Unit): SyncPreparedArchive {
        val writer = SyncArchiveV4TreeWriter(objects, checkActive)
        val value = SyncArchiveV4Manifest(4, 1, manifest,
            writer.write(packed.mainParts), writer.write(packed.legacyParts), writer.write(packed.poolParts), mainHash, legacyHash)
        val wire = SyncArchiveV4Format.manifest(value)
        val refs = writer.objects.values.toList()
        return SyncPreparedArchive(wire, writer.objects.keys.toSet(), { excluded ->
            refs.asSequence().filter { it.path !in excluded }.map { SyncArchiveObject(it.path, objects.readCompressed(it)) }
        }, { paths -> originalCache.trim(paths + legacyProtection) })
    }

    suspend fun load(content: ByteArray, verifyRemote: Boolean, fetch: suspend (String) -> Result<ByteArray>, checkActive: () -> Unit): Loaded {
        val manifest = SyncArchiveV4Format.readManifest(content)
        val reader = SyncArchiveV4TreeReader(objects, fetch, verifyRemote)
        val main = reader.read(manifest.main)
        val legacy = reader.read(manifest.legacy)
        val pool = reader.read(manifest.pool)
        val workspace = newWorkspace()
        try {
            val mainFile = File(workspace.directory, "main.records")
            val legacyFile = File(workspace.directory, "legacy.records")
            SyncArchiveV4InputStream(main, objects).use { mainInput ->
                SyncArchiveV4InputStream(legacy, objects).use { legacyInput ->
                    SyncArchiveV4InputStream(pool, objects).use { poolInput ->
                        mainFile.outputStream().use { mainOutput -> legacyFile.outputStream().use { legacyOutput ->
                            SyncArchiveCompactRecords.unpack(mainInput, legacyInput, poolInput, mainOutput, legacyOutput,
                                manifest.original.rawDataBytes, manifest.original.legacyLyrics?.rawDataBytes ?: 0L,
                                workspace.directory, checkActive)
                        } }
                    }
                }
            }
            require(digestFile(mainFile, checkActive) == manifest.mainRawHash &&
                digestFile(legacyFile, checkActive) == manifest.legacyRawHash) { "Sync unpacked stream checksum mismatch" }
            return Loaded(manifest.original, reader.paths.toSet(), mainFile::inputStream,
                legacyFile.takeIf { manifest.original.legacyLyrics != null }, workspace)
        } catch (failure: Throwable) {
            try { workspace.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun digestFile(file: File, checkActive: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun localLeaves(root: SyncArchiveRef?): List<SyncArchiveRef> {
        return localRefs(root).filter { !it.index }
    }

    private fun localRefs(root: SyncArchiveRef?): List<SyncArchiveRef> {
        val refs = ArrayList<SyncArchiveRef>()
        fun walk(ref: SyncArchiveRef, depth: Int) {
            require(depth <= SyncArchiveLimits.MAX_TREE_DEPTH) { "Sync local index nesting exceeds safe budget" }
            refs += ref
            if (ref.index) {
                localChildren(ref).forEach { walk(it, depth + 1) }
            }
        }
        root?.let { walk(it, 0) }
        return refs
    }

    private fun localChildren(ref: SyncArchiveRef): List<SyncArchiveRef> {
        val index = ProtoBuf.decodeFromByteArray<SyncArchiveIndex>(originalCache.readRaw(ref))
        require(index.children.size in 1..SyncArchiveLimits.INDEX_FANOUT) { "Invalid sync local index fanout" }
        return index.children
    }

    internal fun newWorkspace(): SyncArchiveWorkspace = SyncArchiveWorkspace.create(directory)

    internal fun openLegacy(root: SyncArchiveRef): InputStream = SyncArchiveInputStream(localLeaves(root), originalCache)

    internal class Loaded(
        val manifest: SyncArchiveManifest,
        val paths: Set<String>,
        val main: () -> InputStream,
        val legacy: File?,
        private val workspace: SyncArchiveWorkspace
    ) : Closeable {
        override fun close() { workspace.close() }
    }
}
