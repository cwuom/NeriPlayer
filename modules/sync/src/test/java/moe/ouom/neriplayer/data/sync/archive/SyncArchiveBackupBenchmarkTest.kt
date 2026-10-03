@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.identity.stableKey
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import moe.ouom.neriplayer.data.sync.policy.sanitizeLocalCoverUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveBackupBenchmarkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun legacyBackupRetainsAllVariantsAndMeasuresActualArchiveTransfers() = runBlocking {
        val samplePath = System.getenv("NERI_SYNC_SAMPLE_FILE")?.takeIf { it.isNotBlank() }
        val content = samplePath?.let { path ->
            val file = File(path)
            assertTrue("sample must be a readable bounded backup", file.isFile && file.canRead() && file.length() in 1..12L * 1024 * 1024)
            file.readBytes()
        } ?: SyncDataSerializer.serialize(syntheticData(), useDataSaver = true)
        assertTrue("benchmark requires a gzip backup", content.size >= 2 && content[0] == 0x1f.toByte() && content[1] == 0x8b.toByte())
        val decodeStarted = System.nanoTime()
        val decoded = runCatching { SyncDataSerializer.deserialize(content) }
        assertTrue("backup decoding failed", decoded.isSuccess)
        val raw = requireNotNull(decoded.getOrNull())
        val decodeNanos = System.nanoTime() - decodeStarted
        val rawBytes = GZIPInputStream(ByteArrayInputStream(content)).use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
        val expectedCandidates = songs(raw).filter { it.lyricSyncEdited == null && lyricFields(it).any { field -> field != null } }
            .map(::minimalCandidate).distinct().toList()
        val canonical = SyncSongLyricMergePolicy.prepareLegacy(raw).sanitizeLocalCoverUrls()
        verifyCanonicalLyrics(raw, canonical)
        val writer = SyncArchiveRepository(temporary.newFolder())
        val captureStarted = System.nanoTime()
        writer.captureLegacyLyrics(raw)
        val captureNanos = System.nanoTime() - captureStarted
        val first = publish(writer, canonical, emptySet(), "main-and-legacy-first")
        val recovery = MemoryRecovery()
        val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
        val readStarted = System.nanoTime()
        val restored = reader.read(first.manifest) { path -> Result.success(first.objects.getValue(path)) }.getOrThrow()
        val readNanos = System.nanoTime() - readStarted
        assertTrue("canonical snapshot differs after archive round trip", canonical == restored)
        assertTrue("source lost an original lyric variant or nullable field", expectedCandidates.toSet() == recovery.retained.toSet())
        assertEquals(if (expectedCandidates.isEmpty()) 0 else 1, recovery.calls)
        assertEquals(expectedCandidates.size.toLong(), SyncArchiveRepository.originalManifest(first.manifest).legacyLyrics?.recordCount ?: 0L)
        if (samplePath != null) {
            val completeWireBytes = first.objects.values.sumOf { it.size.toLong() } + first.manifest.size
            assertTrue("complete lossless archive used $completeWireBytes bytes for a ${content.size} byte gzip backup",
                completeWireBytes <= content.size.toLong() * 2 / 5)
        }
        if (samplePath == null) {
            assertTrue("synthetic fixture must exercise multiple legacy lyric versions", versionCount(expectedCandidates) > 0)
            assertTrue("synthetic fixture must exercise all six nonempty lyric fields", (0..5).all { index ->
                expectedCandidates.any { !lyricFields(it)[index].isNullOrEmpty() }
            })
        }

        val warmReads = (1..2).map {
            val started = System.nanoTime()
            val reread = reader.read(first.manifest) { error("warm read unexpectedly fetched a remote object") }.getOrThrow()
            assertTrue("warm read changed the complete canonical snapshot", canonical == reread)
            System.nanoTime() - started
        }
        assertEquals(if (expectedCandidates.isEmpty()) 0 else 1, recovery.calls)
        val mainOnlyWriter = SyncArchiveRepository(temporary.newFolder())
        val mainOnly = publish(mainOnlyWriter, canonical, emptySet(), "main-only-first")
        assertTrue("main-only archive contains a legacy source", SyncArchiveRepository.originalManifest(mainOnly.manifest).legacyLyrics == null)
        val mainOnlyRestored = SyncArchiveRepository(temporary.newFolder()).read(mainOnly.manifest) { path ->
            Result.success(mainOnly.objects.getValue(path))
        }.getOrThrow()
        assertTrue("main-only archive lost canonical lyric fields", canonical == mainOnlyRestored)

        val warmOne = publish(reader, canonical, first.objects.keys, "unchanged-warm-1")
        val warmTwo = publish(reader, canonical, first.objects.keys, "unchanged-warm-2")
        assertEquals(0, warmOne.row.newObjects)
        assertEquals(0L, warmOne.row.newObjectBytes)
        assertEquals(0, warmTwo.row.newObjects)
        assertTrue("unchanged archive manifest must be deterministic", first.manifest.contentEquals(warmOne.manifest))
        assertTrue("repeated warm prepare changed its manifest", warmOne.manifest.contentEquals(warmTwo.manifest))
        val metadataData = changeOneMetadata(canonical)
        val metadata = publish(reader, metadataData, first.objects.keys, "one-metadata-edit")
        verifyChangedRoundTrip(metadataData, metadata, first.objects)
        val lyricData = changeOneLyric(canonical)
        val lyrics = publish(reader, lyricData, first.objects.keys, "one-lyric-edit")
        verifyChangedRoundTrip(lyricData, lyrics, first.objects)
        report(samplePath != null, content.size, rawBytes, decodeNanos, captureNanos, readNanos,
            raw, canonical, expectedCandidates, warmReads, listOf(first.row, mainOnly.row, warmOne.row, warmTwo.row, metadata.row, lyrics.row))
    }

    private fun publish(repository: SyncArchiveRepository, data: SyncData, known: Set<String>, name: String): Published {
        val started = System.nanoTime()
        return repository.prepare(data).use { prepared ->
            val objects = prepared.objects(known).associate { it.path to it.content }
            assertEquals(4, SyncArchiveRepository.protocolVersion(prepared.content))
            val manifest = SyncArchiveRepository.originalManifest(prepared.content)
            assertTrue("archive emitted an oversized object", objects.values.all { it.size <= SyncArchiveLimits.MAX_OBJECT_BYTES })
            assertTrue("manifest exceeded its object budget", prepared.content.size <= SyncArchiveLimits.MAX_OBJECT_BYTES)
            val row = Measurement(name, objects.size, objects.values.sumOf { it.size.toLong() }, prepared.content.size,
                manifest.recordCount, manifest.rawDataBytes, manifest.chunkCount, System.nanoTime() - started)
            Published(prepared.content, objects, row)
        }
    }

    private suspend fun verifyChangedRoundTrip(expected: SyncData, changed: Published, previous: Map<String, ByteArray>) {
        val remote = previous + changed.objects
        val actual = SyncArchiveRepository(temporary.newFolder(), MemoryRecovery()).read(changed.manifest) { path ->
            Result.success(remote.getValue(path))
        }.getOrThrow()
        assertTrue("changed archive did not round trip or old source overrode a newer edit", expected == actual)
        assertTrue("a changed snapshot must publish at least one new object", changed.row.newObjects > 0)
    }

    private fun verifyCanonicalLyrics(raw: SyncData, canonical: SyncData) {
        val versions = songs(raw).filter { it.lyricSyncEdited == null && lyricFields(it).any { field -> field != null } }
            .groupBy { it.stableKey() }
        canonical.lyricOverrides.filter { it.lyricSyncRevision == 1L }.forEach { current ->
            val candidates = versions[current.stableKey()].orEmpty()
            if (candidates.isNotEmpty()) {
                assertTrue("canonical lyrics do not preserve all six fields of any original version", candidates.any { candidate ->
                    lyricFields(current) == listOf(candidate.matchedLyric ?: candidate.originalLyric,
                        candidate.matchedTranslatedLyric ?: candidate.originalTranslatedLyric,
                        candidate.matchedRomanizedLyric ?: candidate.originalRomanizedLyric,
                        candidate.originalLyric, candidate.originalTranslatedLyric, candidate.originalRomanizedLyric)
                })
            }
        }
        versions.keys.forEach { key ->
            assertTrue("a legacy lyric identity is missing from canonical overrides", canonical.lyricOverrides.any { it.stableKey() == key })
        }
    }

    private fun changeOneMetadata(data: SyncData): SyncData {
        val playlistIndex = data.playlists.indexOfFirst { it.songs.isNotEmpty() }
        assertTrue("benchmark fixture needs a playlist member", playlistIndex >= 0)
        val playlist = data.playlists[playlistIndex]
        val songIndex = playlist.songs.size / 2
        val updated = playlist.copy(songs = playlist.songs.mapIndexed { index, song ->
            if (index == songIndex) song.copy(customName = song.customName.orEmpty() + " #") else song
        })
        return data.copy(lastModified = data.lastModified + 1,
            playlists = data.playlists.mapIndexed { index, value -> if (index == playlistIndex) updated else value })
    }

    private fun changeOneLyric(data: SyncData): SyncData {
        val original = data.lyricOverrides.firstOrNull { it.lyricSyncEdited == true && it.matchedLyric != null }
        assertTrue("benchmark fixture needs a preserved lyric", original != null)
        val selected = requireNotNull(original)
        val revision = data.lyricOverrides.maxOf { it.lyricSyncRevision } + 1L
        val edited = selected.copy(matchedLyric = selected.matchedLyric.orEmpty() + "\n#", lyricSyncRevision = revision)
        return SyncSongLyricMergePolicy.converge(data.copy(lastModified = data.lastModified + 1,
            lyricOverrides = data.lyricOverrides.map { if (it.stableKey() == selected.stableKey()) edited else it }))
    }

    private fun minimalCandidate(song: SyncSong): SyncSong = SyncSong(
        id = song.id, album = song.album, mediaUri = song.mediaUri,
        channelId = song.channelId, audioId = song.audioId, subAudioId = song.subAudioId,
        matchedLyric = song.matchedLyric, matchedTranslatedLyric = song.matchedTranslatedLyric,
        matchedRomanizedLyric = song.matchedRomanizedLyric, originalLyric = song.originalLyric,
        originalTranslatedLyric = song.originalTranslatedLyric, originalRomanizedLyric = song.originalRomanizedLyric,
        matchedLyricSource = song.matchedLyricSource, matchedSongId = song.matchedSongId
    )

    private fun songs(data: SyncData): Sequence<SyncSong> = data.lyricOverrides.asSequence() +
        data.playlists.asSequence().flatMap { it.songs.asSequence() } +
        data.favoritePlaylists.asSequence().flatMap { it.songs.asSequence() } +
        data.recentPlays.asSequence().map { it.song }

    private fun lyricFields(song: SyncSong): List<String?> = listOf(song.matchedLyric, song.matchedTranslatedLyric,
        song.matchedRomanizedLyric, song.originalLyric, song.originalTranslatedLyric, song.originalRomanizedLyric)

    private fun versionCount(candidates: List<SyncSong>): Int = candidates.groupBy { it.stableKey() }
        .count { (_, versions) -> versions.map(::lyricFields).distinct().size > 1 }

    private fun syntheticData(): SyncData {
        val songs = (1L..192L).map { id ->
            fun lyric(kind: String) = (0 until 48).joinToString("\n") { line -> "[00:$line] synthetic $id $kind line-$line" }
            SyncSong(id = id, album = "Netease", channelId = "netease", audioId = id.toString(), name = "synthetic-$id",
                matchedLyric = lyric("matched"), matchedTranslatedLyric = lyric("translated"), matchedRomanizedLyric = lyric("romanized"),
                originalLyric = lyric("original"), originalTranslatedLyric = lyric("original-translated"), originalRomanizedLyric = lyric("original-romanized"))
        }
        val otherVersion = songs.first().copy(matchedLyric = "other version", originalLyric = "other baseline")
        val baselineOnly = SyncSong(id = 10_000, album = "Netease", originalLyric = "baseline only",
            originalTranslatedLyric = "baseline translation", originalRomanizedLyric = "baseline romanized")
        val empty = SyncSong(id = 10_001, album = "Netease", matchedLyric = "", originalLyric = "")
        val cache = songs.first().copy(id = 10_002, audioId = "10002", lyricSyncEdited = false)
        val reset = SyncSong(id = 10_003, album = "Netease", lyricSyncEdited = false, lyricSyncRevision = 100)
        return SyncData(deviceId = "synthetic", deviceName = "fixture", lastModified = 100,
            playlists = listOf(SyncPlaylist(id = 1, songs = songs + baselineOnly + empty + cache + reset)),
            favoritePlaylists = listOf(SyncFavoritePlaylist(id = 2, songs = listOf(otherVersion) + songs.take(16))),
            recentPlays = listOf(SyncRecentPlay(songId = songs.first().id, song = songs.first(), playedAt = 99)))
    }

    private fun report(external: Boolean, inputBytes: Int, rawBytes: Long, decodeNanos: Long, captureNanos: Long,
        readNanos: Long, raw: SyncData, canonical: SyncData, candidates: List<SyncSong>, warmReads: List<Long>, measurements: List<Measurement>) {
        val report = buildJsonObject {
            put("externalSample", if (external) 1 else 0)
            put("inputGzipBytes", inputBytes)
            put("decodedProtobufBytes", rawBytes)
            put("songOccurrences", songs(raw).count())
            put("distinctSongIdentities", songs(raw).map { it.stableKey() }.distinct().count())
            put("canonicalLyricOverrides", canonical.lyricOverrides.size)
            put("legacySourceCandidates", candidates.size)
            put("legacyIdentitiesWithMultipleVersions", versionCount(candidates))
            put("decodeNanos", decodeNanos)
            put("captureLegacySourceNanos", captureNanos)
            put("firstReadNanos", readNanos)
            put("warmReadNanos", buildJsonArray { warmReads.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("measurements", buildJsonArray {
                measurements.forEach { value ->
                    add(buildJsonObject {
                        put("name", value.name)
                        put("newObjects", value.newObjects)
                        put("newObjectBytes", value.newObjectBytes)
                        put("manifestBytes", value.manifestBytes)
                        put("wireBytes", value.newObjectBytes + value.manifestBytes)
                        put("records", value.records)
                        put("rawBytes", value.rawBytes)
                        put("dataChunks", value.dataChunks)
                        put("elapsedNanos", value.elapsedNanos)
                    })
                }
            })
        }
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
        check(root != null) { "benchmark report root not found" }
        val directory = File(root, ".report/sync-v3/lyrics-preservation")
        check(directory.isDirectory || directory.mkdirs()) { "benchmark report directory unavailable" }
        File(directory, "kotlin-archive-backup-benchmark-${if (external) "sample" else "synthetic"}.json").writeText(report.toString())
        measurements.forEach { value ->
            println("SYNC_BACKUP_BENCHMARK name=${value.name} wireBytes=${value.newObjectBytes + value.manifestBytes} objects=${value.newObjects} elapsedNanos=${value.elapsedNanos}")
        }
    }

    private data class Measurement(val name: String, val newObjects: Int, val newObjectBytes: Long, val manifestBytes: Int,
        val records: Long, val rawBytes: Long, val dataChunks: Long, val elapsedNanos: Long)
    private data class Published(val manifest: ByteArray, val objects: Map<String, ByteArray>, val row: Measurement)

    private class MemoryRecovery : SyncLegacyLyricRecovery {
        private val completed = mutableSetOf<String>()
        val retained = mutableListOf<SyncSong>()
        var calls = 0
            private set
        override fun isCompleted(sourceHash: String): Boolean = sourceHash in completed
        override fun recover(sourceHash: String, data: SyncData) {
            retained += data.lyricOverrides
            completed += sourceHash
            calls++
        }
        override fun preservedLyrics(): List<SyncSong> = retained.toList()
    }
}
