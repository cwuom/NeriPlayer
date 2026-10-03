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
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.stats.PlaybackStatsCapturedState
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
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

    @Test fun oversizedMetadataFailsBeforeAnyLocalRepositoryOrLyricStorageIsApplied() = runBlocking {
        Fixture(BackupJsonLimits(maxMetadataCharacters = 64)).use { fixture ->
            fixture.input = """{"version":"2.3","playlists":[{"id":9,"name":"existing","songs":[]}],"lyricOverrides":[{"id":7,"originalLyric":"${"x".repeat(256)}"}]}""".toByteArray()
            clearInvocations(fixture.playlists, fixture.history, fixture.stats, fixture.storage)
            assertTrue(fixture.manager.importPlaylists(fixture.uri).isFailure)
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

    private inner class Fixture(limits: BackupJsonLimits = BackupJsonLimits()) : Closeable {
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
        val manager = BackupManager(context, limits) { storage }

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
