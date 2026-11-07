package moe.ouom.neriplayer.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.gson.stream.JsonWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.StringWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.stats.PlaybackStatsCapturedState
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class BackupManagerRoundTripTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportAndReadBackThirteenLyricRichPlaylistsBeyondSixtyFourMiBytes() = runBlocking {
        CoverUrlMapper.installForTest(CoverUrlMapper.createForTest())
        try {
            Fixture().use { fixture ->
                val lyrics = listOf("matched", "translated", "romanized", "original", "original-translated", "original-romanized")
                    .map { "$it\n" + "l".repeat(16 * 1024) }
                val source = (1L..13L).map { playlistId ->
                    LocalPlaylist(playlistId, "playlist $playlistId", (1L..64L).map { songId ->
                        SongItem(playlistId * 100 + songId, "song $songId", "artist", "netease", 1, 100, null,
                            matchedLyric = lyrics[0], matchedTranslatedLyric = lyrics[1], matchedRomanizedLyric = lyrics[2],
                            originalLyric = lyrics[3], originalTranslatedLyric = lyrics[4], originalRomanizedLyric = lyrics[5])
                    }.toMutableList())
                }
                `when`(fixture.playlists.playlists).thenReturn(MutableStateFlow(source))
                `when`(fixture.history.syncSnapshot()).thenReturn(listOf(entry(2000).copy(originalLyric = lyrics[3])))
                val legacy = SyncSong(id = 2001, originalLyric = "legacy\n" + lyrics[3])
                val override = SyncSong(id = 2002, matchedLyric = "edited\n" + lyrics[0], lyricSyncEdited = true, lyricSyncRevision = 1)
                `when`(fixture.storage.getLegacyLyricCandidates()).thenReturn(listOf(legacy))
                `when`(fixture.storage.getLyricOverrides()).thenReturn(listOf(override))

                val result = fixture.manager.exportPlaylists(fixture.uri)
                assertTrue("Expected a complete backup, got ${result.exceptionOrNull()}", result.isSuccess)
                val json = fixture.output.toString(Charsets.UTF_8.name())
                assertTrue(fixture.output.size() > 64 * 1024 * 1024)
                BackupJsonReader(fixture.store()).read(json.reader()).use { content ->
                    val playlists = content.data.playlists.orEmpty()
                    assertEquals(source.map { it.id }, playlists.map { it.id })
                    source.zip(playlists).forEach { (expected, restored) ->
                        assertEquals(expected.songs.map { it.id }, restored.songs.map { it.id })
                        restored.songs.forEach { song ->
                            assertEquals(lyrics, listOf(song.matchedLyric, song.matchedTranslatedLyric, song.matchedRomanizedLyric,
                                song.originalLyric, song.originalTranslatedLyric, song.originalRomanizedLyric))
                        }
                    }
                    assertEquals(lyrics[3], content.data.recentPlays.orEmpty().single().song.originalLyric)
                    assertEquals(listOf(legacy), content.data.legacyLyricCandidates)
                    assertEquals(listOf(override), content.data.lyricOverrides)
                }
                assertTrue(fixture.cache.walkTopDown().none { it.isFile })
            }
        } finally {
            CoverUrlMapper.installForTest(null)
        }
    }

    @Test fun oversizedMetadataFailsBeforeAnyLocalRepositoryOrLyricStorageIsApplied() = runBlocking {
        Fixture(BackupJsonLimits(maxMetadataBytes = 64)).use { fixture ->
            fixture.input = """{"version":"2.3","playlists":[{"id":9,"name":"existing","songs":[]}],"lyricOverrides":[{"id":7,"originalLyric":"${"x".repeat(256)}"}]}""".toByteArray()
            clearInvocations(fixture.playlists, fixture.history, fixture.stats, fixture.storage)
            assertTrue(fixture.manager.importPlaylists(fixture.uri).isFailure)
            verifyNoInteractions(fixture.playlists, fixture.history, fixture.stats, fixture.storage)
            assertTrue(fixture.cache.walkTopDown().none { it.isFile })
        }
    }

    @Test fun largeBackupExportsButRestoreAndPreviewUseCurrentHeapHeadroom() = runBlocking {
        var headroom = 8L * 1024
        Fixture(heapHeadroomBytes = { headroom }).use { fixture ->
            val lyrics = "lyric\n".repeat(512)
            `when`(fixture.storage.getLegacyLyricCandidates())
                .thenReturn(listOf(SyncSong(id = 7, originalLyric = lyrics)))
            fixture.manager.exportPlaylists(fixture.uri).getOrThrow()
            fixture.input = fixture.output.toByteArray()

            clearInvocations(fixture.playlists, fixture.history, fixture.stats, fixture.storage)
            assertEquals("Backup metadata byte budget exceeded",
                fixture.manager.analyzeDifferences(fixture.uri).exceptionOrNull()?.message)
            assertEquals("Backup metadata byte budget exceeded",
                fixture.manager.importPlaylists(fixture.uri).exceptionOrNull()?.message)
            verifyNoInteractions(fixture.playlists, fixture.history, fixture.stats, fixture.storage)
            assertTrue(fixture.cache.walkTopDown().none { it.isFile })

            headroom = 128L * 1024
            assertEquals(0, fixture.manager.analyzeDifferences(fixture.uri).getOrThrow().totalMissingSongs)
            assertEquals(1, fixture.manager.importPlaylists(fixture.uri).getOrThrow().totalCount)
        }
    }

    @Test fun restoreHeapBudgetAlsoCountsCompactMetadataObjects() = runBlocking {
        Fixture(heapHeadroomBytes = { 8L * 1024 }).use { fixture ->
            val songs = (1..9).joinToString(",") { """{"id":$it}""" }
            fixture.input = """{"playlists":[{"id":9,"name":"existing","songs":[$songs]}]}""".toByteArray()
            clearInvocations(fixture.playlists, fixture.history, fixture.stats, fixture.storage)

            assertEquals("Backup metadata object budget exceeded",
                fixture.manager.importPlaylists(fixture.uri).exceptionOrNull()?.message)
            verifyNoInteractions(fixture.playlists, fixture.history, fixture.stats, fixture.storage)
            assertTrue(fixture.cache.walkTopDown().none { it.isFile })
        }
    }

    @Test fun exportCannotReportSuccessWhenTheMetadataObjectBudgetWouldRejectItsBackup() = runBlocking {
        Fixture(BackupJsonLimits(maxMetadataObjects = 2)).use { fixture ->
            `when`(fixture.history.syncSnapshot()).thenReturn(listOf(entry(1)))
            assertTrue(fixture.manager.exportPlaylists(fixture.uri).isFailure)
        }
    }

    @Test fun exportedQuotedTokensUseTheSameExactBoundaryAsTheReader() = runBlocking {
        for ((characters, allowed) in listOf(32 to true, 33 to false)) {
            val limits = BackupJsonLimits(maxTokenCharacters = 34)
            Fixture(limits).use { fixture ->
                `when`(fixture.history.syncSnapshot()).thenReturn(listOf(entry(1).copy(name = "n".repeat(characters))))
                val result = fixture.manager.exportPlaylists(fixture.uri)
                assertEquals(allowed, result.isSuccess)
                if (allowed) {
                    BackupJsonReader(fixture.store(), limits = limits).readMetadata(fixture.output.toString(Charsets.UTF_8.name()).reader())
                        .let { assertEquals("n".repeat(32), it.recentPlays.orEmpty().single().song.name) }
                }
            }
        }
    }

    @Test fun exportThenImportRetainsEveryHistoryIdentityBeyondOneThousand() = runBlocking {
        Fixture().use { fixture ->
            val source = (1L..1001L).map(::entry)
            `when`(fixture.history.syncSnapshot()).thenReturn(source)
            assertTrue(fixture.manager.exportPlaylists(fixture.uri).isSuccess)
            val json = fixture.output.toString(Charsets.UTF_8.name())
            BackupJsonReader(fixture.store()).readMetadata(json.reader()).let { data ->
                assertEquals(1001, data.recentPlays.orEmpty().size)
            }
            fixture.input = fixture.output.toByteArray()
            fixture.historyState.value = listOf(entry(2000))
            assertTrue(fixture.manager.importPlaylists(fixture.uri).isSuccess)
            assertEquals((1L..1001L).toSet() + 2000L, fixture.historyState.value.map { it.id }.toSet())
            assertEquals(1002, fixture.historyState.value.size)
            assertTrue(fixture.cache.walkTopDown().none { it.isFile })
        }
    }

    @Test fun truncatedAndFutureBackupsCannotApplyAnyLocalRepository() = runBlocking {
        Fixture().use { fixture ->
            for (json in listOf(
                """{"version":"2.3","playlists":[{"id":9,"name":"existing","songs":[]}],"playbackStats":[{"identityKey":"netease:1","id":1}]""",
                """{"version":"3.0","playlists":[{"id":9,"name":"existing","songs":[]}],"playbackStats":[]}"""
            )) {
                clearInvocations(fixture.playlists, fixture.history, fixture.stats)
                fixture.input = json.toByteArray()
                assertTrue(fixture.manager.importPlaylists(fixture.uri).isFailure)
                verifyNoInteractions(fixture.playlists, fixture.history, fixture.stats)
                assertTrue(fixture.cache.walkTopDown().none { it.isFile })
            }
        }
    }

    private fun entry(id: Long) = PlayedEntry(id = id, name = "song $id", artist = "artist", album = "netease",
        albumId = 1, durationMs = 100, coverUrl = null, playedAt = id)

    private inner class Fixture(
        limits: BackupJsonLimits = BackupJsonLimits(),
        heapHeadroomBytes: () -> Long = { 1024L * 1024 * 1024 }
    ) : Closeable {
        val cache = temporary.newFolder()
        val uri = mock(Uri::class.java)
        val output = ByteArrayOutputStream()
        var input = ByteArray(0)
        val playlists = mock(LocalPlaylistRepository::class.java)
        val history = mock(PlayHistoryRepository::class.java)
        val stats = mock(PlaybackStatsRepository::class.java)
        val historyState = MutableStateFlow<List<PlayedEntry>>(emptyList())
        private val resolver = mock(ContentResolver::class.java)
        private val context = mock(Context::class.java) { invocation ->
            if (invocation.method.name == "getString") "message" else RETURNS_DEFAULTS.answer(invocation)
        }
        private val owners = listOf(
            replaceSingleton(LocalPlaylistRepository::class.java, playlists),
            replaceSingleton(PlayHistoryRepository::class.java, history),
            replaceSingleton(PlaybackStatsRepository::class.java, stats)
        )
        val storage = mock(SecureTokenStorage::class.java)
        val manager = BackupManager(context, limits, heapHeadroomBytes) { storage }

        init {
            `when`(context.cacheDir).thenReturn(cache)
            `when`(storage.setLyricOverridesIfMutationVersion(anyLong(), anyList())).thenReturn(true)
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.contentResolver).thenReturn(resolver)
            `when`(resolver.openOutputStream(uri)).thenReturn(output)
            `when`(resolver.openInputStream(uri)).thenAnswer { ByteArrayInputStream(input) }
            `when`(playlists.playlists).thenReturn(MutableStateFlow(listOf(LocalPlaylist(9, "existing"))))
            `when`(history.historyFlow).thenReturn(historyState)
            runBlocking {
                `when`(history.awaitInitialized()).thenReturn(true)
                `when`(stats.awaitInitialized()).thenReturn(true)
                doAnswer { invocation ->
                    val writer = invocation.getArgument<JsonWriter>(0)
                    writer.name("playbackStats").beginArray().endArray()
                    writer.name("playbackStatBuckets").beginArray().endArray()
                    PlaybackStatsCapturedState(0, 0)
                }.`when`(stats).writeBackupStatistics(any(JsonWriter::class.java) ?: JsonWriter(StringWriter()))
                doAnswer { invocation ->
                    historyState.value = invocation.getArgument(0)
                    Unit
                }.`when`(history).updateHistory(anyList())
            }
        }

        fun store() = moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore(cache)

        override fun close() {
            owners.reversed().forEach { it.close() }
        }
    }

    private fun <T> replaceSingleton(type: Class<T>, replacement: T): Closeable {
        val field = type.getDeclaredField("INSTANCE").also { it.isAccessible = true }
        val previous = field.get(null)
        field.set(null, replacement)
        return Closeable { field.set(null, previous) }
    }
}
