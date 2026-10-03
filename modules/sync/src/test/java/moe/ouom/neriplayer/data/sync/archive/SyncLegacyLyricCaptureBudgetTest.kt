@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataLimits
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncLegacyLyricCaptureBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `all lyric origins retain distinct versions nulls and source fields in original frame order`() {
        val directory = temporary.newFolder()
        val cache = SyncArchiveCache(directory)
        val archive = SyncLegacyLyricArchive(cache)
        val first = SyncSong(id = 7, album = "local", mediaUri = "content://fixture/7", channelId = "bilibili",
            audioId = "av7", subAudioId = "p2", matchedLyric = "", matchedTranslatedLyric = "译文",
            matchedRomanizedLyric = "romanized", matchedLyricSource = "fixture-source", matchedSongId = "matched-7",
            originalLyric = "original", originalTranslatedLyric = "", originalRomanizedLyric = null)
        val second = first.copy(matchedLyric = "another version", matchedLyricSource = null)
        val third = SyncSong(id = 8, album = "cloud", originalRomanizedLyric = "only original romanized")
        val fourth = SyncSong(id = 9, album = "cloud", originalLyric = "recent original")
        val expected = listOf(first, second, third, fourth)
        val data = SyncData(lyricOverrides = listOf(first.copy(name = "discarded display name", lyricSyncRevision = 88)),
            playlists = listOf(SyncPlaylist(songs = listOf(first.copy(name = "duplicate display name"), second))),
            favoritePlaylists = listOf(SyncFavoritePlaylist(songs = listOf(third))),
            recentPlays = listOf(SyncRecentPlay(song = fourth), SyncRecentPlay(song = first.copy(lyricSyncEdited = true)),
                SyncRecentPlay(song = first.copy(lyricSyncEdited = false)), SyncRecentPlay(song = SyncSong(id = 10))))

        val captured = requireNotNull(archive.capture(data))
        val raw = readRaw(cache, captured.source.root)
        assertArrayEquals(frames(expected), raw)
        assertEquals(4L, captured.source.recordCount)
        assertEquals(raw.size.toLong(), captured.source.rawDataBytes)
        assertEquals(expected, archive.read(captured.source, raw.inputStream()) {}.lyricOverrides)
        assertNoTemporaryFiles(directory)
    }

    @Test
    fun `empty or known lyric data creates no legacy source or cache objects`() {
        val directory = temporary.newFolder()
        val archive = SyncLegacyLyricArchive(SyncArchiveCache(directory))
        assertNull(archive.capture(SyncData()))
        assertNull(archive.capture(SyncData(lyricOverrides = listOf(SyncSong(id = 1),
            SyncSong(id = 2, matchedLyric = "known edit", lyricSyncEdited = true),
            SyncSong(id = 3, matchedLyric = "known cache", lyricSyncEdited = false)))))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `optimization preserves eligible bilibili originals and explicitly leaves other candidates out`() {
        val cache = SyncArchiveCache(temporary.newFolder())
        val archive = SyncLegacyLyricArchive(cache)
        val bilibili = SyncSong(id = 1, album = "bilibili", channelId = "bilibili", originalLyric = "original")
        val cloud = SyncSong(id = 2, album = "cloud", matchedLyric = "unknown cloud")
        val data = SyncData(lyricOverrides = listOf(bilibili, cloud))
        val optimized = requireNotNull(archive.capture(data, optimize = true))
        assertEquals(listOf(bilibili), archive.read(optimized.source, readRaw(cache, optimized.source.root).inputStream()) {}.lyricOverrides)
        assertNull(archive.capture(SyncData(lyricOverrides = listOf(cloud)), optimize = true))
        val retained = requireNotNull(archive.capture(data))
        assertEquals(listOf(bilibili, cloud), archive.read(retained.source, readRaw(cache, retained.source.root).inputStream()) {}.lyricOverrides)
    }

    @Test
    fun `payload and object budgets include every candidate and failure keeps previously cached source readable`() {
        val directory = temporary.newFolder()
        val cache = SyncArchiveCache(directory)
        val songs = listOf(SyncSong(id = 1, originalLyric = "first"), SyncSong(id = 2, originalLyric = "second"))
        val payloadBytes = songs.sumOf { ProtoBuf.encodeToByteArray(it).size.toLong() }
        val limits = SyncArchiveMetadataLimits(maxPayloadBytes = payloadBytes, maxObjects = 2)
        val captured = requireNotNull(SyncLegacyLyricArchive(cache, limits).capture(SyncData(lyricOverrides = songs)))
        val raw = readRaw(cache, captured.source.root)
        assertArrayEquals(frames(songs), raw)
        for (reduced in listOf(limits.copy(maxPayloadBytes = payloadBytes - 1), limits.copy(maxObjects = 1))) {
            assertThrows(IOException::class.java) {
                SyncLegacyLyricArchive(cache, reduced).capture(SyncData(lyricOverrides = songs))
            }
            assertArrayEquals(raw, readRaw(cache, captured.source.root))
            assertNoTemporaryFiles(directory)
        }
    }

    @Test
    fun `legacy raw budget includes frame overhead even with a larger metadata limit`() {
        val directory = temporary.newFolder()
        val song = SyncSong(id = 1, originalLyric = "x".repeat(SyncArchiveLimits.MAX_LEGACY_SOURCE_RAW_BYTES.toInt()))
        val limits = SyncArchiveMetadataLimits(maxPayloadBytes = 2 * SyncArchiveLimits.MAX_LEGACY_SOURCE_RAW_BYTES)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SyncLegacyLyricArchive(SyncArchiveCache(directory), limits).capture(SyncData(lyricOverrides = listOf(song)))
        }
        assertEquals("Legacy lyric source exceeds raw budget", failure.message)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `large candidate frames span bounded chunks and an index without changing any record byte`() {
        val cache = SyncArchiveCache(temporary.newFolder())
        val archive = SyncLegacyLyricArchive(cache)
        val songs = listOf(SyncSong(id = 1, originalLyric = "a".repeat(SyncArchiveLimits.MAX_RAW_BYTES + 100)),
            SyncSong(id = 2, matchedLyric = "tail", matchedLyricSource = "other source"))
        val captured = requireNotNull(archive.capture(SyncData(lyricOverrides = songs)))
        assertTrue(captured.source.root.index)
        val leaves = captured.objects.filterNot { it.index }
        assertTrue(leaves.size > 1)
        assertEquals(leaves.size.toLong(), captured.source.chunkCount)
        assertTrue(leaves.all { it.rawBytes in 1..SyncArchiveLimits.MAX_RAW_BYTES })
        val raw = readRaw(cache, captured.source.root)
        assertArrayEquals(frames(songs), raw)
        assertEquals(songs, archive.read(captured.source, raw.inputStream()) {}.lyricOverrides)
        assertEquals(captured.objects.size, captured.objects.map { it.path }.toSet().size)
    }

    @Test
    fun `cache publication failure cleans temporary files without deleting an existing destination`() {
        val directory = temporary.newFolder()
        val song = SyncSong(id = 1, originalLyric = "retained text")
        val raw = frames(listOf(song))
        val compressed = SyncArchiveCodec.compress(raw)
        val destination = File(directory, "neriplayer-sync-v3-${SyncArchiveCodec.digest(compressed)}.zst")
        assertTrue(destination.mkdir())
        val existing = File(destination, "existing")
        existing.writeText("keep")
        assertThrows(IOException::class.java) {
            SyncLegacyLyricArchive(SyncArchiveCache(directory)).capture(SyncData(lyricOverrides = listOf(song)))
        }
        assertEquals("keep", existing.readText())
        assertNoTemporaryFiles(directory)
        assertEquals(listOf(destination.name), directory.listFiles().orEmpty().map { it.name })
    }

    private fun frames(songs: List<SyncSong>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            for (song in songs) {
                val payload = ProtoBuf.encodeToByteArray(song)
                output.writeByte(15)
                output.writeInt(payload.size)
                output.write(payload)
            }
        }
    }.toByteArray()

    private fun readRaw(cache: SyncArchiveCache, ref: SyncArchiveRef): ByteArray {
        val raw = cache.readRaw(ref)
        if (!ref.index) return raw
        val output = ByteArrayOutputStream()
        ProtoBuf.decodeFromByteArray<SyncArchiveIndex>(raw).children.forEach { child -> output.write(readRaw(cache, child)) }
        return output.toByteArray()
    }

    private fun assertNoTemporaryFiles(directory: File) {
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
