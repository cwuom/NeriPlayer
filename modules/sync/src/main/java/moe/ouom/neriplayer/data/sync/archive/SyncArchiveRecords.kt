@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer
import moe.ouom.neriplayer.data.model.sync.*
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import moe.ouom.neriplayer.data.sync.policy.sanitizeCoverUrlForSync
import moe.ouom.neriplayer.data.sync.policy.sanitizeCoverUrlsForSync
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSink
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackPageWriter
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataBudget
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataLimits

internal object SyncArchiveRecords {
    fun header(data: SyncData): SyncData = data.copy(
        playlists = emptyList(), favoritePlaylists = emptyList(), recentPlays = emptyList(),
        syncLog = emptyList(), recentPlayDeletions = emptyList(), playbackStats = emptyList(),
        playbackStatBuckets = emptyList(), playlistSongDeletions = emptyList(), playlistUsageStats = emptyList(),
        localPlaylistPlaybackStats = emptyList(), localPlaylistPlaybackBuckets = emptyList(), biliVideoSkipRules = emptyList(),
        lyricOverrides = emptyList(), playlistUsageDeletions = emptyList()
    )

    fun write(data: SyncData, stream: OutputStream, checkActive: () -> Unit = {},
        metadataBudget: SyncArchiveMetadataBudget = SyncArchiveMetadataBudget(SyncArchiveMetadataLimits())): Long {
        val writer = Writer(DataOutputStream(stream), checkActive, metadataBudget, includePlayback = true)
        writer.playlists(data)
        writer.favorites(data)
        writer.history(data)
        writer.trackStatistics(data)
        data.playlistSongDeletions.forEach { writer.record(10, it) }
        writer.playlistUsage(data)
        data.biliVideoSkipRules.forEach { writer.record(14, it) }
        SyncSongLyricMergePolicy.collectOverrides(data).forEach { writer.record(15, SyncSongLyricMergePolicy.normalize(it)) }
        data.playlistUsageDeletions.forEach { writer.record(16, it) }
        return writer.count
    }

    suspend fun writeDataset(dataset: SyncDataset, stream: OutputStream, checkActive: () -> Unit,
        metadataBudget: SyncArchiveMetadataBudget = SyncArchiveMetadataBudget(SyncArchiveMetadataLimits())): Long {
        val data = dataset.data
        val writer = Writer(DataOutputStream(stream), checkActive, metadataBudget, includePlayback = false)
        writer.playlists(data)
        writer.favorites(data)
        writer.history(data)
        dataset.playback.openTracks().use { cursor ->
            while (true) {
                val page = cursor.nextPage()
                if (page.isEmpty()) break
                page.forEach { writer.record(8, it.copy(coverUrl = sanitizeCoverUrlForSync(it.coverUrl))) }
            }
        }
        dataset.playback.openBuckets().use { cursor ->
            while (true) {
                val page = cursor.nextPage()
                if (page.isEmpty()) break
                page.forEach { writer.record(9, it.copy(coverUrl = sanitizeCoverUrlForSync(it.coverUrl))) }
            }
        }
        data.playlistSongDeletions.forEach { writer.record(10, it) }
        writer.playlistUsage(data)
        data.biliVideoSkipRules.forEach { writer.record(14, it) }
        SyncSongLyricMergePolicy.collectOverrides(data).forEach { writer.record(15, SyncSongLyricMergePolicy.normalize(it)) }
        data.playlistUsageDeletions.forEach { writer.record(16, it) }
        return writer.count
    }

    private fun song(song: SyncSong): SyncSong = SyncArchiveLyricProjection.reference(song.sanitizeCoverUrlsForSync())

    private class Writer(private val output: DataOutputStream, private val checkActive: () -> Unit,
        private val metadataBudget: SyncArchiveMetadataBudget, private val includePlayback: Boolean) {
        var count = 0L

        fun playlists(data: SyncData) {
            data.playlists.forEach { playlist ->
                record(1, playlist.copy(songs = emptyList()))
                playlist.songs.forEach { record(2, song(it)) }
            }
        }

        fun favorites(data: SyncData) {
            data.favoritePlaylists.forEach { playlist ->
                record(3, playlist.copy(songs = emptyList(), coverUrl = sanitizeCoverUrlForSync(playlist.coverUrl)))
                playlist.songs.forEach { record(4, song(it)) }
            }
        }

        fun history(data: SyncData) {
            data.recentPlays.forEach { record(5, it.copy(song = song(it.song))) }
            data.syncLog.forEach { record(6, it) }
            data.recentPlayDeletions.forEach { record(7, it) }
        }

        fun trackStatistics(data: SyncData) {
            data.playbackStats.forEach { record(8, it.copy(coverUrl = sanitizeCoverUrlForSync(it.coverUrl))) }
            data.playbackStatBuckets.forEach { record(9, it.copy(coverUrl = sanitizeCoverUrlForSync(it.coverUrl))) }
        }

        fun playlistUsage(data: SyncData) {
            data.playlistUsageStats.forEach { record(11, it.copy(coverUrl = sanitizeCoverUrlForSync(it.coverUrl))) }
            data.localPlaylistPlaybackStats.forEach { record(12, it) }
            data.localPlaylistPlaybackBuckets.forEach { record(13, it) }
        }

        inline fun <reified T> record(kind: Int, value: T) {
            if (count % 1024 == 0L) checkActive()
            val budget = if (!includePlayback && kind in 8..9) metadataBudget.isolated() else metadataBudget
            val bytes = budget.encode(serializer<T>(), value, checkActive)
            require(bytes.size <= SyncArchiveLimits.MAX_RECORD_BYTES) { "Single sync record exceeds safe decoding budget" }
            output.writeByte(kind)
            output.writeInt(bytes.size)
            output.write(bytes)
            count++
        }
    }

    fun read(header: SyncData, stream: InputStream, expectedRecords: Long, expectedBytes: Long,
        beforeRestore: (SyncData) -> Unit = {},
        metadataBudget: SyncArchiveMetadataBudget = SyncArchiveMetadataBudget(SyncArchiveMetadataLimits()),
        checkActive: () -> Unit = {}): SyncData {
        val accumulator = SyncRecordAccumulator(header)
        visitRetained(stream, expectedRecords, expectedBytes, metadataBudget, checkActive, accumulator::record)
        return SyncArchiveLyricProjection.restore(accumulator.finish(), beforeRestore)
    }

    fun visit(stream: InputStream, expectedRecords: Long, expectedBytes: Long, checkActive: () -> Unit = {}, record: (Int, ByteArray) -> Unit) {
        visitWithBudget(stream, expectedRecords, expectedBytes, checkActive, { null }, record)
    }

    fun visitRetained(stream: InputStream, expectedRecords: Long, expectedBytes: Long,
        budget: SyncArchiveMetadataBudget, checkActive: () -> Unit, record: (Int, ByteArray) -> Unit) {
        visitWithBudget(stream, expectedRecords, expectedBytes, checkActive, { budget }, record)
    }

    private fun visitWithBudget(stream: InputStream, expectedRecords: Long, expectedBytes: Long,
        checkActive: () -> Unit, budgetFor: (Int) -> SyncArchiveMetadataBudget?, record: (Int, ByteArray) -> Unit) {
        val reader = SyncRecordReader(DataInputStream(stream), expectedRecords, expectedBytes, checkActive, budgetFor)
        while (true) {
            val kind = reader.nextKind() ?: break
            record(kind, reader.payload(kind))
        }
        reader.finish()
    }

    suspend fun readDataset(
        header: SyncData,
        stream: InputStream,
        expectedRecords: Long,
        expectedBytes: Long,
        sink: SyncPlaybackSink,
        sanitizeTrack: (SyncTrackStat) -> SyncTrackStat?,
        sanitizeBucket: (SyncPlaybackStatBucket) -> SyncPlaybackStatBucket?,
        beforeRestore: (SyncData) -> Unit = {},
        metadataBudget: SyncArchiveMetadataBudget = SyncArchiveMetadataBudget(SyncArchiveMetadataLimits()),
        checkActive: () -> Unit
    ): SyncDataset {
        val accumulator = SyncRecordAccumulator(header, includePlayback = false)
        val reader = SyncRecordReader(DataInputStream(stream), expectedRecords, expectedBytes, checkActive) { kind ->
            if (kind in 8..9) metadataBudget.isolated() else metadataBudget
        }
        val tracks = SyncPlaybackPageWriter(SyncTrackStat.serializer(), sink::appendTracks)
        val buckets = SyncPlaybackPageWriter(SyncPlaybackStatBucket.serializer(), sink::appendBuckets)
        while (true) {
            val kind = reader.nextKind() ?: break
            val payload = reader.payload(kind)
            accumulator.record(kind, payload)
            when (kind) {
                8 -> sanitizeTrack(ProtoBuf.decodeFromByteArray<SyncTrackStat>(payload))?.let { stat ->
                    tracks.add(stat)
                }
                9 -> sanitizeBucket(ProtoBuf.decodeFromByteArray<SyncPlaybackStatBucket>(payload))?.let { bucket ->
                    buckets.add(bucket)
                }
            }
        }
        reader.finish()
        tracks.finish()
        buckets.finish()
        val data = SyncArchiveLyricProjection.restore(accumulator.finish(), beforeRestore)
        checkActive()
        return SyncDataset(data, sink.seal())
    }

}

private class SyncRecordReader(
    private val input: DataInputStream,
    private val expectedRecords: Long,
    private val expectedBytes: Long,
    private val checkActive: () -> Unit,
    private val budgetFor: (Int) -> SyncArchiveMetadataBudget?
) {
    private var count = 0L
    private var consumed = 0L

    fun nextKind(): Int? {
        if (count % 1024 == 0L) checkActive()
        val kind = input.read()
        if (kind == -1) return null
        validateHeader(kind)
        return kind
    }

    private fun validateHeader(kind: Int) {
        require(count < expectedRecords) { "Unexpected sync record" }
        require(kind in 1..16) { "Unexpected sync record" }
        require(expectedBytes - consumed >= 5L) { "Unexpected sync record" }
    }

    fun payload(kind: Int): ByteArray {
        val size = input.readInt()
        require(size in 0..SyncArchiveLimits.MAX_RECORD_BYTES) { "Invalid sync record size" }
        require(size.toLong() <= expectedBytes - consumed - 5L) { "Invalid sync record size" }
        val budget = budgetFor(kind)
        budget?.beginRecord(size)
        val payload = ByteArray(size).also(input::readFully)
        budget?.inspect(kind, payload, checkActive)
        count++
        consumed += size + 5L
        return payload
    }

    fun finish() {
        require(count == expectedRecords) { "Sync record count mismatch" }
        require(consumed == expectedBytes) { "Sync byte count mismatch" }
    }
}

private class SyncRecordAccumulator(private val header: SyncData, private val includePlayback: Boolean = true) {
    private val playlists = ArrayList<SyncPlaylist>()
    private val favorites = ArrayList<SyncFavoritePlaylist>()
    private val recent = ArrayList<SyncRecentPlay>()
    private val logs = ArrayList<SyncLogEntry>()
    private val recentDeletions = ArrayList<SyncRecentPlayDeletion>()
    private val stats = ArrayList<SyncTrackStat>()
    private val buckets = ArrayList<SyncPlaybackStatBucket>()
    private val songDeletions = ArrayList<SyncPlaylistSongDeletion>()
    private val usage = ArrayList<SyncPlaylistUsageStat>()
    private val localStats = ArrayList<SyncLocalPlaylistPlaybackStat>()
    private val localBuckets = ArrayList<SyncLocalPlaylistPlaybackBucket>()
    private val skip = ArrayList<SyncBiliVideoSkipRule>()
    private val lyricOverrides = ArrayList<SyncSong>()
    private val usageDeletions = ArrayList<SyncPlaylistUsageDeletion>()
    private var playlist: SyncPlaylist? = null
    private var favorite: SyncFavoritePlaylist? = null
    private var songs = ArrayList<SyncSong>()
    private var phase = 0

    fun record(kind: Int, payload: ByteArray) {
        val nextPhase = phaseOf(kind)
        require(nextPhase >= phase) { "Sync sections are out of order" }
        phase = nextPhase
        when {
            kind <= 4 -> membership(kind, payload)
            kind <= 7 -> history(kind, payload)
            kind <= 10 -> trackStatistics(kind, payload)
            kind <= 13 -> playlistUsage(kind, payload)
            else -> extras(kind, payload)
        }
    }

    private fun phaseOf(kind: Int): Int = when (kind) { 1, 2 -> 1; 3, 4 -> 3; else -> kind }

    private fun membership(kind: Int, payload: ByteArray) {
        when (kind) {
            1 -> beginPlaylist(payload)
            2 -> playlistSong(payload)
            3 -> beginFavorite(payload)
            4 -> favoriteSong(payload)
        }
    }

    private fun beginPlaylist(payload: ByteArray) {
        finishPlaylist()
        playlist = ProtoBuf.decodeFromByteArray<SyncPlaylist>(payload).also {
            require(it.songs.isEmpty()) { "Playlist header embeds songs" }
        }
    }

    private fun playlistSong(payload: ByteArray) {
        require(playlist != null) { "Song has no playlist" }
        songs.add(ProtoBuf.decodeFromByteArray(payload))
    }

    private fun beginFavorite(payload: ByteArray) {
        finishPlaylist()
        finishFavorite()
        favorite = ProtoBuf.decodeFromByteArray<SyncFavoritePlaylist>(payload).also {
            require(it.songs.isEmpty()) { "Favorite header embeds songs" }
        }
    }

    private fun favoriteSong(payload: ByteArray) {
        require(favorite != null) { "Song has no favorite playlist" }
        songs.add(ProtoBuf.decodeFromByteArray(payload))
    }

    private fun history(kind: Int, payload: ByteArray) {
        when (kind) {
            5 -> recent.add(ProtoBuf.decodeFromByteArray(payload))
            6 -> logs.add(ProtoBuf.decodeFromByteArray(payload))
            7 -> recentDeletions.add(ProtoBuf.decodeFromByteArray(payload))
        }
    }

    private fun trackStatistics(kind: Int, payload: ByteArray) {
        when (kind) {
            8 -> if (includePlayback) stats.add(ProtoBuf.decodeFromByteArray(payload))
            9 -> if (includePlayback) buckets.add(ProtoBuf.decodeFromByteArray(payload))
            10 -> songDeletions.add(ProtoBuf.decodeFromByteArray(payload))
        }
    }

    private fun playlistUsage(kind: Int, payload: ByteArray) {
        when (kind) {
            11 -> usage.add(ProtoBuf.decodeFromByteArray(payload))
            12 -> localStats.add(ProtoBuf.decodeFromByteArray(payload))
            13 -> localBuckets.add(ProtoBuf.decodeFromByteArray(payload))
        }
    }

    private fun extras(kind: Int, payload: ByteArray) {
        when (kind) {
            14 -> skip.add(ProtoBuf.decodeFromByteArray(payload))
            15 -> lyricOverrides.add(ProtoBuf.decodeFromByteArray(payload))
            16 -> usageDeletions.add(ProtoBuf.decodeFromByteArray(payload))
        }
    }

    private fun finishPlaylist() {
        playlist?.let { playlists.add(it.copy(songs = songs)); songs = ArrayList() }
        playlist = null
    }

    private fun finishFavorite() {
        favorite?.let { favorites.add(it.copy(songs = songs)); songs = ArrayList() }
        favorite = null
    }

    fun finish(): SyncData {
        finishPlaylist()
        finishFavorite()
        return header.copy(
            playlists = playlists, favoritePlaylists = favorites, recentPlays = recent, syncLog = logs,
            recentPlayDeletions = recentDeletions, playbackStats = stats, playbackStatBuckets = buckets,
            playlistSongDeletions = songDeletions, playlistUsageStats = usage,
            localPlaylistPlaybackStats = localStats, localPlaylistPlaybackBuckets = localBuckets, biliVideoSkipRules = skip,
            lyricOverrides = lyricOverrides, playlistUsageDeletions = usageDeletions
        )
    }
}
