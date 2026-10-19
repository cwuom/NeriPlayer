package moe.ouom.neriplayer.data.local.playlist

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Answers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalPlaylistScannedIdentityPersistenceTest : LocalPlaylistRepositoryTestSupport() {
    private val mediaAccessField = LocalMediaHostAccess::class.java.getDeclaredField("access").apply { isAccessible = true }
    private val previousMediaAccess = mediaAccessField.get(null)
    private val audioFiles = mutableMapOf<String, File>()
    private val containerQueries = AtomicInteger()
    private lateinit var context: Context

    @Before
    fun setUpProvider() {
        context = mockContext()
        val resolver = mock(ContentResolver::class.java, Answer { invocation ->
            if (invocation.method.name == "query") {
                containerQueries.incrementAndGet()
                null
            } else Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        doReturn(resolver).`when`(context).contentResolver
        doAnswer { invocation ->
            audioFiles.getValue(invocation.getArgument<Uri>(0).toString()).inputStream()
        }.`when`(resolver).openInputStream(any(Uri::class.java))
        val downloads = mock(LocalMediaDownloadAccess::class.java)
        doReturn(emptyList<String>()).`when`(downloads).candidateFileNameTemplates(null)
        LocalMediaHostAccess.bind(downloads, mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { false })
    }

    @After
    fun restoreMediaHost() {
        mediaAccessField.set(null, previousMediaAccess)
        LocalMediaSupport.clearLyricsLookupCache()
    }

    @Test
    fun `creating a playlist from deferred scans persists raw identity before returning`() = runTest {
        val scanned = scannedSongs()
        val storage = initialStorage()
        val repository = repository(storage)

        val created = repository.createPlaylistWithScannedSongs("Imported", scanned)

        assertCorrectIdentity(created.songs, scanned)
        assertCorrectIdentity(repository(storage).playlists.value.single { it.id == created.id }.songs, scanned)
    }

    @Test
    fun `adding deferred scans to a playlist persists raw identity before returning`() = runTest {
        val scanned = scannedSongs()
        val storage = initialStorage()
        val repository = repository(storage)

        val result = repository.addScannedSongsToPlaylistWithResult(1L, scanned)

        assertEquals(3, result.addedCount)
        assertCorrectIdentity(result.addedSongs, scanned)
        assertCorrectIdentity(repository(storage).playlists.value.single { it.id == 1L }.songs, scanned)
    }

    @Test
    fun `adding deferred scans to local files persists raw identity before returning`() = runTest {
        val scanned = scannedSongs()
        val storage = initialStorage()
        val repository = repository(storage)

        val result = repository.addScannedSongsToLocalFilesPlaylistWithResult(scanned)

        assertEquals(3, result.addedCount)
        assertCorrectIdentity(result.addedSongs, scanned)
        assertCorrectIdentity(repository(storage).playlists.value.single { it.id == LocalFilesPlaylist.SYSTEM_ID }.songs, scanned)
    }

    @Test
    fun `prepared playlist insertions keep precomputed identity without another raw probe`() = runTest {
        val prepared = scannedSongs()
        val storage = initialStorage()
        val repository = repository(storage)

        val created = repository.createPlaylistWithPreparedSongs("Prepared", prepared)
        val added = repository.addPreparedSongsToPlaylistWithResult(1L, prepared)

        for (songs in listOf(created.songs, added.addedSongs)) {
            assertEquals(prepared.associate { it.id to it.name }, songs.associate { it.id to it.name })
            assertEquals(prepared.associate { it.id to it.artist }, songs.associate { it.id to it.artist })
            assertEquals(prepared.associate { it.id to it.album }, songs.associate { it.id to it.album })
        }
    }

    @Test
    fun `scanned persistence does not probe container metadata for untagged placeholder identities`() = runTest {
        val reference = "content://scanned.identity/audio/9"
        audioFiles[reference] = tempFolder.newFile("untagged.mp3").apply { writeBytes(ByteArray(32)) }
        val scanned = SongItem(
            id = 9L, name = "untagged", artist = "Unknown Artist", album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L, durationMs = 1000L, coverUrl = null, mediaUri = reference,
            localFileName = "untagged.mp3", channelId = "local", audioId = "9"
        )
        val repository = repository(initialStorage())

        val created = repository.createPlaylistWithScannedSongs("Untagged", listOf(scanned)).songs.single()
        val added = repository.addScannedSongsToPlaylistWithResult(1L, listOf(scanned)).addedSongs.single()
        val local = repository.addScannedSongsToLocalFilesPlaylistWithResult(listOf(scanned)).addedSongs.single()

        for (persisted in listOf(created, added, local)) {
            assertEquals(scanned.name, persisted.name)
            assertEquals(scanned.artist, persisted.artist)
            assertEquals(scanned.album, persisted.album)
            assertEquals(scanned.originalName, persisted.originalName)
            assertEquals(scanned.originalArtist, persisted.originalArtist)
        }
        assertEquals(0, containerQueries.get())
    }

    private fun scannedSongs(): List<SongItem> = (1L..3L).map { id ->
        val file = tempFolder.newFile("track-$id.mp3")
        val frames = frame("TIT2", "散场电影") + frame("TPE1", "许嵩") + frame("TALB", "寻宝游戏")
        file.writeBytes(byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0) + synchsafe(frames.size) + frames + ByteArray(32))
        val reference = "content://scanned.identity/audio/$id"
        audioFiles[reference] = file
        SongItem(
            id = id, name = "ミ泣ャ title", artist = "ミ泣ャ", album = "ミ泣ャ album", albumId = 0L,
            durationMs = 1000L, coverUrl = "content://retained.cover/$id", mediaUri = reference,
            localFileName = file.name, channelId = "local", audioId = id.toString(),
            originalName = if (id == 1L) "ミ泣ャ title" else "retained title",
            originalArtist = if (id == 1L) "ミ泣ャ" else "retained artist",
            customName = if (id == 3L) "manual title" else null,
            customArtist = if (id == 3L) "manual artist" else null,
            matchedLyric = "retained lyric", originalLyric = "", originalCoverUrl = "",
            logicalCreatedAtMs = 1_000L + id
        )
    }

    private fun assertCorrectIdentity(actual: List<SongItem>, scanned: List<SongItem>) {
        assertEquals(setOf(1L, 2L, 3L), actual.map { it.id }.toSet())
        actual.forEach { song ->
            val source = scanned.single { it.id == song.id }
            assertEquals(if (song.id == 3L) "manual title" else "散场电影", song.name)
            assertEquals(if (song.id == 3L) "manual artist" else "许嵩", song.artist)
            assertEquals("寻宝游戏", song.album)
            assertEquals(if (song.id == 1L) "散场电影" else "retained title", song.originalName)
            assertEquals(if (song.id == 1L) "许嵩" else "retained artist", song.originalArtist)
            assertEquals(source.customName, song.customName)
            assertEquals(source.customArtist, song.customArtist)
            assertEquals(source.coverUrl, song.coverUrl)
            assertEquals(source.originalCoverUrl, song.originalCoverUrl)
            assertEquals(source.matchedLyric, song.matchedLyric)
            assertEquals(source.originalLyric, song.originalLyric)
            assertEquals(source.logicalCreatedAtMs, song.addedAt)
        }
    }

    private fun initialStorage() = RecordingStorage(primary = Gson().toJson(listOf(
        LocalPlaylist(1L, "Existing", mutableListOf(), 1_000L, songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION),
        LocalPlaylist(LocalFilesPlaylist.SYSTEM_ID, "Local Files", mutableListOf(), 1_000L,
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION)
    )))

    private fun repository(storage: RecordingStorage) = LocalPlaylistRepository.createForTest(
        context = context,
        file = File(tempFolder.root, "scanned_identity.json"),
        storage = storage,
        syncMutationStore = RecordingSyncMutationStore()
    )

    private fun frame(name: String, text: String): ByteArray {
        val payload = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
        return name.toByteArray(Charsets.US_ASCII) + ByteArray(4) { index ->
            (payload.size ushr (8 * (3 - index))).toByte()
        } + byteArrayOf(0, 0) + payload
    }

    private fun synchsafe(value: Int) = ByteArray(4) { index -> ((value ushr (7 * (3 - index))) and 0x7F).toByte() }
}
