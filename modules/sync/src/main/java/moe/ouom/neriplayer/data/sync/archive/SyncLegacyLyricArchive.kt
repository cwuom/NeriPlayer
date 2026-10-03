@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.DataOutputStream
import java.io.InputStream
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.Serializable
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataBudget
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataLimits

interface SyncLegacyLyricRecovery {
    fun isCompleted(sourceHash: String): Boolean
    fun recover(sourceHash: String, data: SyncData)
    fun preservedLyrics(): List<SyncSong> = emptyList()
    fun optimizeLegacyLyrics(): Boolean = false
}

@Serializable
internal data class SyncLegacyLyricSource(
    @ProtoNumber(1) val root: SyncArchiveRef,
    @ProtoNumber(2) val recordCount: Long,
    @ProtoNumber(3) val rawDataBytes: Long,
    @ProtoNumber(4) val chunkCount: Long
) {
    val hash: String get() = SyncArchiveCodec.digest(ProtoBuf.encodeToByteArray(this))

    fun manifest(): SyncArchiveManifest = SyncArchiveManifest(3, SyncData(), root, recordCount, rawDataBytes, chunkCount)

    fun validate() {
        require(recordCount > 0L) { "Empty legacy lyric source" }
        require(rawDataBytes <= SyncArchiveLimits.MAX_LEGACY_SOURCE_RAW_BYTES) { "Legacy lyric source exceeds raw budget" }
        require(chunkCount <= SyncArchiveLimits.MAX_LEGACY_SOURCE_CHUNKS) { "Legacy lyric source contains excessive chunks" }
        SyncArchiveManifestValidation.validate(manifest())
        SyncArchiveCodec.validate(root)
    }
}

internal data class CapturedLegacyLyrics(val source: SyncLegacyLyricSource, val objects: List<SyncArchiveRef>)

internal class SyncLegacyLyricArchive(private val cache: SyncArchiveCache,
    private val metadataLimits: SyncArchiveMetadataLimits = SyncArchiveMetadataLimits()) {
    fun capture(data: SyncData, optimize: Boolean = false): CapturedLegacyLyrics? {
        val budget = SyncArchiveMetadataBudget(metadataLimits)
        val chunks = ArrayList<SyncArchiveRef>()
        val objects = LinkedHashMap<String, SyncArchiveRef>()
        val chunker = SyncContentChunker { raw ->
            require(chunks.size.toLong() < SyncArchiveLimits.MAX_LEGACY_SOURCE_CHUNKS) { "Legacy lyric source contains excessive chunks" }
            val ref = cache.store(raw, index = false)
            chunks += ref
            objects[ref.path] = ref
        }
        var count = 0L
        DataOutputStream(chunker).use { output ->
            candidates(data, optimize).forEach { candidate ->
                val bytes = budget.encode(SyncSong.serializer(), candidate) {}
                require(bytes.size <= SyncArchiveLimits.MAX_RECORD_BYTES) { "Single legacy lyric record exceeds safe budget" }
                require(bytes.size.toLong() + FRAME_BYTES <= SyncArchiveLimits.MAX_LEGACY_SOURCE_RAW_BYTES - chunker.totalBytes) {
                    "Legacy lyric source exceeds raw budget"
                }
                output.writeByte(LEGACY_RECORD_KIND)
                output.writeInt(bytes.size)
                output.write(bytes)
                count++
            }
        }
        if (count == 0L) return null
        var level = chunks.toList()
        while (level.size > 1) {
            level = level.chunked(SyncArchiveLimits.INDEX_FANOUT).map { children ->
                cache.store(ProtoBuf.encodeToByteArray(SyncArchiveIndex(children)), index = true)
                    .also { objects[it.path] = it }
            }
        }
        val source = SyncLegacyLyricSource(level.single(), count, chunker.totalBytes, chunks.size.toLong())
        source.validate()
        return CapturedLegacyLyrics(source, objects.values.toList())
    }

    fun read(source: SyncLegacyLyricSource, input: InputStream,
        budget: SyncArchiveMetadataBudget = SyncArchiveMetadataBudget(metadataLimits), checkActive: () -> Unit): SyncData {
        val candidates = ArrayList<SyncSong>()
        SyncArchiveRecords.visitRetained(input, source.recordCount, source.rawDataBytes, budget, checkActive) { kind, bytes ->
            require(kind == LEGACY_RECORD_KIND) { "Legacy lyric source contains unrelated records" }
            val candidate = ProtoBuf.decodeFromByteArray<SyncSong>(bytes)
            require(hasUnknownLyrics(candidate) && candidate.lyricSyncRevision == 0L) { "Invalid legacy lyric candidate" }
            candidates += candidate
        }
        return SyncData(lyricOverrides = candidates)
    }

    fun verifyBudget(source: SyncLegacyLyricSource, input: InputStream, budget: SyncArchiveMetadataBudget, checkActive: () -> Unit) {
        SyncArchiveRecords.visitRetained(input, source.recordCount, source.rawDataBytes, budget, checkActive) { kind, _ ->
            require(kind == LEGACY_RECORD_KIND) { "Legacy lyric source contains unrelated records" }
        }
    }

    private fun candidates(data: SyncData, optimize: Boolean): Sequence<SyncSong> {
        val songs = data.lyricOverrides.asSequence() + data.playlists.asSequence().flatMap { it.songs.asSequence() } +
            data.favoritePlaylists.asSequence().flatMap { it.songs.asSequence() } + data.recentPlays.asSequence().map { it.song }
        return songs.filter(::hasUnknownLyrics)
            .filter { !optimize || SyncSongLyricMergePolicy.prepareLegacy(it, optimize).lyricSyncEdited == true }.map { song ->
            SyncSong(
                id = song.id, album = song.album, mediaUri = song.mediaUri,
                channelId = song.channelId, audioId = song.audioId, subAudioId = song.subAudioId,
                matchedLyric = song.matchedLyric, matchedTranslatedLyric = song.matchedTranslatedLyric,
                matchedRomanizedLyric = song.matchedRomanizedLyric, matchedLyricSource = song.matchedLyricSource,
                matchedSongId = song.matchedSongId, originalLyric = song.originalLyric,
                originalTranslatedLyric = song.originalTranslatedLyric, originalRomanizedLyric = song.originalRomanizedLyric,
                lyricSyncEdited = null, lyricSyncRevision = 0L
            )
        }.distinct()
    }

    private fun hasUnknownLyrics(song: SyncSong): Boolean = song.lyricSyncEdited == null &&
        (song.matchedLyric != null || song.matchedTranslatedLyric != null || song.matchedRomanizedLyric != null ||
            song.originalLyric != null || song.originalTranslatedLyric != null || song.originalRomanizedLyric != null)

    private companion object {
        const val LEGACY_RECORD_KIND = 15
        const val FRAME_BYTES = 5L
    }
}
