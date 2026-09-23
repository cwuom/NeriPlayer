package moe.ouom.neriplayer.core.download

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.manager.catalog.findConfirmedMissingDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.catalog.statDownloadedSongReference
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.storage.backend.StorageLookupResult
import moe.ouom.neriplayer.core.download.storage.backend.StorageReference
import moe.ouom.neriplayer.core.download.storage.backend.StorageStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class DownloadedSongMissingReferenceProbeTest {
    @Test
    fun `only songs whose independent local references are all missing are removed`() = runBlocking {
        val missing = song("/music/gone.flac", "content://provider/document/gone")
        val stillPresent = song("/music/live.flac", "content://provider/document/missing")
        val uncertain = song("/music/uncertain.flac", "content://provider/document/denied")
        val empty = song("", null)
        val observed = ConcurrentHashMap.newKeySet<String>()

        val result = findConfirmedMissingDownloadedSongs(
            listOf(missing, stillPresent, uncertain, empty)
        ) { reference ->
            observed += reference
            when (reference) {
                "/music/live.flac" -> found(reference)
                "content://provider/document/denied" -> StorageLookupResult.PermissionLost
                else -> StorageLookupResult.Missing
            }
        }

        assertEquals(listOf(missing), result)
        assertTrue(observed.containsAll(listOf(missing.filePath, missing.mediaUri!!)))
    }

    @Test
    fun `missing media uri cannot override an existing file path`() = runBlocking {
        val track = song("/music/live.flac", "content://provider/document/gone")
        val result = findConfirmedMissingDownloadedSongs(listOf(track)) { reference ->
            if (reference == track.filePath) found(reference) else StorageLookupResult.Missing
        }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `missing file path cannot override an existing media uri`() = runBlocking {
        val track = song("/music/gone.flac", "content://provider/document/live")
        val result = findConfirmedMissingDownloadedSongs(listOf(track)) { reference ->
            if (reference == track.mediaUri) found(reference) else StorageLookupResult.Missing
        }

        assertTrue(result.isEmpty())
    }

    @Test
    fun `every uncertain typed result preserves the catalog entry`() = runBlocking {
        val uncertainty = listOf(
            StorageLookupResult.PermissionLost,
            StorageLookupResult.OutOfScope,
            StorageLookupResult.Unsupported("unsupported reference"),
            StorageLookupResult.ProviderFailure(IOException("provider offline"))
        )
        uncertainty.forEach { outcome ->
            val track = song("/music/gone.flac", "content://provider/document/uncertain")
            val result = findConfirmedMissingDownloadedSongs(listOf(track)) { reference ->
                if (reference == track.filePath) StorageLookupResult.Missing else outcome
            }
            assertTrue("$outcome must preserve the song", result.isEmpty())
        }
    }

    @Test
    fun `opaque document ids and authorities are never replaced by stable song keys`() = runBlocking {
        val missing = song("content://one/tree/root/document/opaque%3A17")
        val present = missing.copy(filePath = "content://two/tree/root/document/opaque%3A17")
        val replacement = missing.copy(filePath = "content://one/tree/root/document/opaque%3A18")
        val observed = ConcurrentHashMap.newKeySet<String>()
        val result = findConfirmedMissingDownloadedSongs(listOf(missing, present, replacement)) { ref ->
            observed += ref
            if (ref == missing.filePath) StorageLookupResult.Missing else found(ref)
        }

        assertEquals(listOf(missing), result)
        assertEquals(setOf(missing.filePath, present.filePath, replacement.filePath), observed)
    }

    @Test
    fun `same raw reference in both fields is probed once`() = runBlocking {
        val track = song("content://one/tree/root/document/opaque%3A17")
            .let { it.copy(mediaUri = it.filePath) }
        val calls = AtomicInteger()
        val result = findConfirmedMissingDownloadedSongs(listOf(track)) {
            calls.incrementAndGet()
            StorageLookupResult.Missing
        }

        assertEquals(listOf(track), result)
        assertEquals(1, calls.get())
    }

    @Test
    fun `one provider exception preserves that song and other proven missing songs still resolve`() = runBlocking {
        val uncertain = song("content://provider/document/error")
        val missing = song("content://provider/document/gone")
        val result = findConfirmedMissingDownloadedSongs(listOf(uncertain, missing)) { reference ->
            if (reference == uncertain.filePath) throw IOException("provider offline")
            StorageLookupResult.Missing
        }

        assertEquals(listOf(missing), result)
    }

    @Test
    fun `a thousand songs use at most eight fixed workers and retain input order`() = runBlocking {
        val tracks = List(1000) { song("content://provider/document/opaque-$it") }
        val workers = ConcurrentHashMap.newKeySet<Job>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val result = findConfirmedMissingDownloadedSongs(tracks) {
            workers += requireNotNull(currentCoroutineContext()[Job])
            val count = active.incrementAndGet()
            peak.accumulateAndGet(count, ::maxOf)
            try {
                delay(1L)
                StorageLookupResult.Missing
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(tracks, result)
        assertTrue("probes must run concurrently", peak.get() > 1)
        assertTrue("at most eight concurrent probes", peak.get() <= 8)
        assertTrue("no coroutine per song", workers.size <= 8)
        assertEquals(0, active.get())
    }

    @Test
    fun `probe cancellation propagates instead of returning a partial deletion list`() = runBlocking {
        try {
            findConfirmedMissingDownloadedSongs(listOf(song("/music/gone.flac"))) {
                throw CancellationException("cancelled probe")
            }
            fail("cancellation must propagate")
        } catch (error: CancellationException) {
            assertEquals("cancelled probe", error.message)
        }
    }

    @Test
    fun `file adapter confirms missing paths without discarding existing files`() = runBlocking {
        val directory = Files.createTempDirectory("missing-download-probe").toFile()
        try {
            val present = File(directory, "present.flac").also { it.writeText("audio") }
            val spaced = File(directory, "present with space.flac ").also { it.writeText("audio") }
            val missing = song(File(directory, "missing.flac").absolutePath)
            val missingUri = song(File(directory, "missing-uri.flac").toURI().toString())
            val live = song(present.absolutePath, present.toURI().toString())
            val invalid = song("relative-name.flac")
            val ambiguous = song("relative-name.flac", missing.filePath)
            val blankReference = song(" ", missing.filePath)

            assertEquals(
                listOf(missing, missingUri),
                findConfirmedMissingDownloadedSongs(
                    mock(Context::class.java),
                    listOf(missing, missingUri, live, song(spaced.absolutePath), invalid, ambiguous, blankReference)
                )
            )
            assertEquals("audio", present.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `saf adapter uses the complete original uri in a stat query without opening audio`() = runBlocking<Unit> {
        val reference = "content://provider.example/tree/root/document/opaque%3A17%2Faudio"
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        val cursor = mock(Cursor::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.query(eq(uri), any(), isNull(), isNull(), isNull())).thenReturn(cursor)
        `when`(cursor.moveToFirst()).thenReturn(false)

        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)
            assertEquals(StorageLookupResult.Missing, statDownloadedSongReference(context, reference))
        }
        verify(resolver).query(eq(uri), any(), isNull(), isNull(), isNull())
        verify(cursor).close()
        verify(resolver, never()).openInputStream(any())
        verify(resolver, never()).openFileDescriptor(any(), any<String>())
    }

    private fun found(reference: String): StorageLookupResult<StorageStat> = StorageLookupResult.Found(
        StorageStat(StorageReference.FileRef(reference), "audio", 10L, 1L, false)
    )

    private fun song(filePath: String, mediaUri: String? = null) = DownloadedSong(
        id = 1L,
        name = "song",
        artist = "artist",
        album = "album",
        filePath = filePath,
        fileSize = 10L,
        downloadTime = 1L,
        mediaUri = mediaUri,
        stableKey = "same-remote-song"
    )
}
