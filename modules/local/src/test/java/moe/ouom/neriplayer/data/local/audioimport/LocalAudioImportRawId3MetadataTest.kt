package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalKnownSidecarReferences
import moe.ouom.neriplayer.data.local.media.NearbyLyricReferences
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalAudioImportRawId3MetadataTest {
    @get:Rule val directory = TemporaryFolder()
    private val context = mock(Context::class.java)
    private val mediaAccessField = LocalMediaHostAccess::class.java.getDeclaredField("access").apply { isAccessible = true }
    private val previousMediaAccess = mediaAccessField.get(null)

    @Before
    fun bindMediaHost() {
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
    fun `raw ID3 identity replaces a MediaStore snapshot in displayed and original metadata`() {
        val quick = quickSong(taggedFile("track.mp3"), 1L)
        val parsed = requireNotNull(LocalMediaSupport.parseId3FileMetadata(quick.localFilePath!!.let(::File)))
        val detailed = quick.copy(
            name = requireNotNull(parsed.title), artist = requireNotNull(parsed.artist), album = requireNotNull(parsed.album),
            originalName = parsed.title, originalArtist = parsed.artist
        )

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(quick, detailed)

        assertEquals("散场电影", merged.name)
        assertEquals("许嵩", merged.artist)
        assertEquals("寻宝游戏", merged.album)
        assertEquals("散场电影", merged.originalName)
        assertEquals("许嵩", merged.originalArtist)
    }

    @Test
    fun `raw identity hydration preserves separately retained originals and manual overrides`() {
        val quick = quickSong(taggedFile("edited.mp3"), 1L).copy(
            originalName = "retained title", originalArtist = "retained artist",
            customName = "manual title", customArtist = "manual artist"
        )
        val detailed = quick.copy(name = "散场电影", artist = "许嵩", originalName = "散场电影", originalArtist = "许嵩")

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(quick, detailed)

        assertEquals("retained title", merged.originalName)
        assertEquals("retained artist", merged.originalArtist)
        assertEquals("manual title", merged.customName)
        assertEquals("manual artist", merged.customArtist)
    }

    @Test
    fun `raw identity replaces stale sidecar identity snapshots and their matching originals`() {
        val audio = taggedFile("stale-sidecar.mp3")
        val quick = quickSong(audio, 20L)
        File(audio.path + ".npmeta.json").writeText(identitySnapshot(quick).toString())

        val hydrated = LocalAudioImportManager.hydrateLocalSongFastIdentity(context, quick)

        assertEquals("散场电影", hydrated.name)
        assertEquals("许嵩", hydrated.artist)
        assertEquals("寻宝游戏", hydrated.album)
        assertEquals("散场电影", hydrated.originalName)
        assertEquals("许嵩", hydrated.originalArtist)
    }

    @Test
    fun `raw identity preserves explicit sidecar overrides and independently retained originals`() {
        listOf(false, true).forEachIndexed { index, custom ->
            val audio = taggedFile("retained-sidecar-$index.mp3")
            val quick = quickSong(audio, 21L + index)
            val metadata = identitySnapshot(quick)
                .put("originalName", "retained title")
                .put("originalArtist", "retained artist")
            if (custom) metadata.put("customName", "manual title").put("customArtist", "manual artist")
            File(audio.path + ".npmeta.json").writeText(metadata.toString())

            val hydrated = LocalAudioImportManager.hydrateLocalSongFastIdentity(context, quick)

            assertEquals(if (custom) "manual title" else "散场电影", hydrated.name)
            assertEquals(if (custom) "manual artist" else "许嵩", hydrated.artist)
            assertEquals("retained title", hydrated.originalName)
            assertEquals("retained artist", hydrated.originalArtist)
            assertEquals(if (custom) "manual title" else null, hydrated.customName)
            assertEquals(if (custom) "manual artist" else null, hydrated.customArtist)
        }
    }

    @Test
    fun `partial raw identity preserves sidecar fields that have no raw result`() {
        val frames = frame("TIT2", "散场电影")
        val audio = directory.newFile("partial-sidecar.mp3").apply {
            writeBytes(byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0) + synchsafe(frames.size) + frames + ByteArray(32))
        }
        val quick = quickSong(audio, 23L)
        File(audio.path + ".npmeta.json").writeText(identitySnapshot(quick)
            .put("artist", "sidecar artist")
            .put("album", "sidecar album")
            .put("originalArtist", "retained artist")
            .toString())

        val hydrated = LocalAudioImportManager.hydrateLocalSongFastIdentity(context, quick)

        assertEquals("散场电影", hydrated.name)
        assertEquals("散场电影", hydrated.originalName)
        assertEquals("sidecar artist", hydrated.artist)
        assertEquals("sidecar album", hydrated.album)
        assertEquals("retained artist", hydrated.originalArtist)
    }

    @Test
    fun `deferred scans read raw ID3 identity even when MediaStore Unicode looks readable`() = runBlocking {
        val songs = (1L..257L).map { id -> quickSong(taggedFile("track$id.mp3"), id) }

        val result = LocalAudioImportManager.completeScannedSongs(
            context = context,
            songs = songs,
            progress = LocalAudioScanProgressEmitter(scanId = 1L, startedAt = 0L, onProgress = {}),
            visitedDirectories = 0
        )

        assertTrue(result.metadataDeferred)
        assertEquals(257, result.songs.size)
        result.songs.forEach { song ->
            assertEquals("散场电影", song.name)
            assertEquals("许嵩", song.artist)
            assertEquals("寻宝游戏", song.album)
            assertEquals("许嵩", song.originalArtist)
        }
    }

    @Test
    fun `deferred content rows do not open audio before later identity hydration reads every raw tag`(): Unit = runBlocking {
        val audio = taggedFile("deferred-content.mp3", largeCoverFrame())
        val resolver = mock(ContentResolver::class.java)
        val songs = contentRows(audio)
        doReturn(resolver).`when`(context).contentResolver
        doAnswer { ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY) }
            .`when`(resolver).openFileDescriptor(any(Uri::class.java), eq("r"))

        val result = completeContentRows(songs, null)

        assertTrue(result.metadataDeferred)
        assertEquals(songs.size, result.songs.size)
        result.songs.forEachIndexed { index, song -> assertEquals(songs[index], song) }
        verifyNoInteractions(resolver)

        val hydrated = result.songs.map { LocalAudioImportManager.hydrateLocalSongIdentityMetadata(context, it) }

        assertEquals(257, hydrated.size)
        hydrated.forEach { song ->
            assertEquals("散场电影", song.name)
            assertEquals("许嵩", song.artist)
            assertEquals("寻宝游戏", song.album)
            assertEquals("许嵩", song.originalArtist)
        }
        verify(resolver, times(257)).openFileDescriptor(any(Uri::class.java), eq("r"))
    }

    @Test
    fun `deferred content rows read only explicitly indexed metadata references`() = runBlocking {
        val resolver = mock(ContentResolver::class.java)
        val songs = contentRows(taggedFile("indexed-content.mp3"))
        val reference = "content://indexed.sidecars/metadata.json"
        val metadata = JSONObject().put("name", "indexed title").put("artist", "indexed artist")
            .put("customName", "manual indexed title").put("originalName", "retained title")
            .put("album", "indexed album").toString().toByteArray(Charsets.UTF_8)
        doReturn(resolver).`when`(context).contentResolver
        doAnswer { ByteArrayInputStream(metadata) }.`when`(resolver).openInputStream(Uri.parse(reference))

        val result = completeContentRows(songs, reference)

        assertTrue(result.metadataDeferred)
        assertEquals(257, result.songs.size)
        result.songs.forEach { song ->
            assertEquals("manual indexed title", song.name)
            assertEquals("indexed artist", song.artist)
            assertEquals("indexed album", song.album)
            assertEquals("retained title", song.originalName)
        }
        verify(resolver, times(257)).openInputStream(Uri.parse(reference))
        verifyNoMoreInteractions(resolver)
    }

    @Test
    fun `unreadable indexed metadata does not cause deferred audio probing`() = runBlocking {
        val resolver = mock(ContentResolver::class.java)
        val songs = contentRows(taggedFile("unreadable-indexed-content.mp3"))
        val reference = "content://indexed.sidecars/unreadable.json"
        doReturn(resolver).`when`(context).contentResolver
        doThrow(FileNotFoundException("indexed sidecar unavailable"))
            .`when`(resolver).openInputStream(Uri.parse(reference))

        val result = completeContentRows(songs, reference)

        assertTrue(result.metadataDeferred)
        assertEquals(songs.size, result.songs.size)
        result.songs.forEachIndexed { index, song -> assertEquals(songs[index], song) }
        verify(resolver, times(257)).openInputStream(Uri.parse(reference))
        verifyNoMoreInteractions(resolver)
    }

    @Test
    fun `indexed metadata cancellation propagates instead of looking like an unreadable reference`() {
        val resolver = mock(ContentResolver::class.java)
        val song = contentRows(taggedFile("cancelled-indexed-content.mp3")).first()
        val reference = "content://indexed.sidecars/cancelled.json"
        val cancellation = CancellationException("indexed metadata read cancelled")
        doReturn(resolver).`when`(context).contentResolver
        doThrow(cancellation).`when`(resolver).openInputStream(Uri.parse(reference))

        val failure = runCatching {
            LocalAudioImportManager.hydrateLocalSongFromIndexedMetadataSidecar(context, song, reference)
        }.exceptionOrNull()

        assertSame(cancellation, failure)
    }

    @Test
    fun `content only MediaStore identity is read from the bounded raw ID3 header`() {
        val audio = taggedFile("content-only.mp3")
        val resolver = mock(ContentResolver::class.java)
        val song = quickSong(audio, 9L).copy(localFilePath = null)
        doReturn(resolver).`when`(context).contentResolver
        doAnswer { audio.inputStream() }.`when`(resolver).openInputStream(any(Uri::class.java))

        val hydrated = LocalAudioImportManager.hydrateLocalSongFastIdentity(context, song)

        assertEquals("散场电影", hydrated.name)
        assertEquals("许嵩", hydrated.artist)
        assertEquals("寻宝游戏", hydrated.album)
        assertEquals("许嵩", hydrated.originalArtist)
    }

    @Test
    fun `fast identity skips a large preceding cover frame to read raw text`() {
        val song = quickSong(taggedFile("large-cover.mp3", largeCoverFrame()), 10L)

        val hydrated = LocalAudioImportManager.hydrateLocalSongFastIdentity(context, song)

        assertEquals("散场电影", hydrated.name)
        assertEquals("许嵩", hydrated.artist)
        assertEquals("寻宝游戏", hydrated.album)
        assertEquals("许嵩", hydrated.originalArtist)
    }

    @Test
    fun `deferred scan skips a large preceding cover frame without a later identity probe`() = runBlocking {
        val audio = taggedFile("deferred-large-cover.mp3", largeCoverFrame())
        val songs = (1L..257L).map { quickSong(audio, it) }

        val result = LocalAudioImportManager.completeScannedSongs(
            context = context,
            songs = songs,
            progress = LocalAudioScanProgressEmitter(scanId = 1L, startedAt = 0L, onProgress = {}),
            visitedDirectories = 0
        )

        assertTrue(result.metadataDeferred)
        assertEquals(257, result.songs.size)
        result.songs.forEach { song ->
            assertEquals("散场电影", song.name)
            assertEquals("许嵩", song.artist)
            assertEquals("寻宝游戏", song.album)
            assertEquals("许嵩", song.originalArtist)
        }
    }

    @Test
    fun `content descriptor skips a large preceding cover frame to read raw text`() {
        val audio = taggedFile("content-large-cover.mp3", largeCoverFrame())
        val resolver = mock(ContentResolver::class.java)
        val song = quickSong(audio, 11L).copy(localFilePath = null)
        doReturn(resolver).`when`(context).contentResolver
        doAnswer { ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY) }
            .`when`(resolver).openFileDescriptor(any(Uri::class.java), eq("r"))

        val hydrated = LocalAudioImportManager.hydrateLocalSongFastIdentity(context, song)

        assertEquals("散场电影", hydrated.name)
        assertEquals("许嵩", hydrated.artist)
        assertEquals("寻宝游戏", hydrated.album)
    }

    @Test
    fun `asset only providers fall back to an input stream when file descriptors are unsupported`() {
        val audio = taggedFile("asset-only.mp3")
        val resolver = mock(ContentResolver::class.java)
        val song = quickSong(audio, 12L).copy(localFilePath = null)
        doReturn(resolver).`when`(context).contentResolver
        doThrow(FileNotFoundException("provider only supports asset streams"))
            .`when`(resolver).openFileDescriptor(any(Uri::class.java), eq("r"))
        doAnswer { audio.inputStream() }.`when`(resolver).openInputStream(any(Uri::class.java))

        val hydrated = LocalAudioImportManager.hydrateLocalSongFastIdentity(context, song)

        assertEquals("散场电影", hydrated.name)
        assertEquals("许嵩", hydrated.artist)
        assertEquals("寻宝游戏", hydrated.album)
    }

    @Test
    fun `frame selection preserves supported versions and extended header boundaries`() {
        val cases = listOf(
            2 to byteArrayOf(),
            3 to integer(6) + ByteArray(6),
            3 to integer(10) + byteArrayOf(0x80.toByte(), 0) + ByteArray(8),
            4 to synchsafe(6) + byteArrayOf(1, 0)
        )
        cases.forEachIndexed { index, (version, extendedHeader) ->
            val audio = taggedFile("version$index.mp3", largeCoverFrame(version), version, extendedHeader)

            val hydrated = LocalAudioImportManager.hydrateLocalSongFastIdentity(context, quickSong(audio, index.toLong()))

            assertEquals("version $version", "散场电影", hydrated.name)
            assertEquals("version $version", "许嵩", hydrated.artist)
            assertEquals("version $version", "寻宝游戏", hydrated.album)
        }
    }

    @Test
    fun `identity scan leaves format transformed frames and tags unresolved`() {
        listOf(2, 3, 4).forEach { version ->
            val audio = taggedFile("transformed$version.mp3", version = version,
                tagFlags = if (version == 2) 0x40 else 0x80)
            val song = quickSong(audio, version.toLong())

            assertEquals(song.name, LocalAudioImportManager.hydrateLocalSongFastIdentity(context, song).name)
        }
        listOf(3, 4).forEach { version ->
            val audio = taggedFile("flagged$version.mp3", version = version, formatFlags = 0x80)
            val song = quickSong(audio, version.toLong())

            assertEquals(song.name, LocalAudioImportManager.hydrateLocalSongFastIdentity(context, song).name)
        }
    }

    @Test
    fun `seekable identity scan reads bounded bytes while skipping a large cover`() {
        val audio = taggedFile("counted-cover.mp3", largeCoverFrame())
        var bytesRead = 0
        val metadata = object : FileInputStream(audio) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, length).also { if (it > 0) bytesRead += it }
        }.use { readId3Identity(it, allowSeek = true) }

        assertEquals("许嵩", metadata?.artist)
        assertTrue("only headers, identity text and the ID3v1 tail should be read: $bytesRead", bytesRead < 2048)
    }

    @Test
    fun `asset streams cannot use an ID3v1 trailer outside their declared region`() {
        val frames = frame("TIT2", "散场电影")
        val asset = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0) + synchsafe(frames.size) + frames + ByteArray(1024)
        val foreignTag = ByteArray(128).apply {
            "TAG".toByteArray(Charsets.US_ASCII).copyInto(this)
            "foreign artist".toByteArray(Charsets.US_ASCII).copyInto(this, 33)
        }
        val backing = directory.newFile("asset-backing.mp3").apply { writeBytes(asset + foreignTag) }
        val resolver = mock(ContentResolver::class.java)
        val song = quickSong(backing, 15L).copy(localFilePath = null)
        doReturn(resolver).`when`(context).contentResolver
        doThrow(FileNotFoundException("asset region only"))
            .`when`(resolver).openFileDescriptor(any(Uri::class.java), eq("r"))
        doAnswer {
            AssetFileDescriptor(ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY), 0, asset.size.toLong())
                .createInputStream()
        }.`when`(resolver).openInputStream(any(Uri::class.java))

        val hydrated = LocalAudioImportManager.hydrateLocalSongFastIdentity(context, song)

        assertEquals("散场电影", hydrated.name)
        assertEquals(song.artist, hydrated.artist)
        assertEquals(song.album, hydrated.album)
    }

    @Test
    fun `non seekable streams stop within budget when a large cover precedes identity`() {
        val audio = taggedFile("stream-cover.mp3", largeCoverFrame())
        val resolver = mock(ContentResolver::class.java)
        val song = quickSong(audio, 14L).copy(localFilePath = null)
        var bytesRead = 0
        doReturn(resolver).`when`(context).contentResolver
        doAnswer {
            object : FilterInputStream(ByteArrayInputStream(audio.readBytes())) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    super.read(bytes, offset, length).also { if (it > 0) bytesRead += it }
            }
        }.`when`(resolver).openInputStream(any(Uri::class.java))

        val hydrated = LocalAudioImportManager.hydrateLocalSongFastIdentity(context, song)

        assertEquals(song.name, hydrated.name)
        assertEquals(song.artist, hydrated.artist)
        assertTrue("non seekable reads must remain bounded: $bytesRead", bytesRead <= 64 * 1024)
    }

    private fun quickSong(audio: File, id: Long): SongItem = LocalAudioImportManager.buildQuickImportedSong(
        seed = QuickImportedSongSeed(
            sourceRef = "content://media/external/audio/media/$id", displayName = audio.name,
            title = "ミ泣ャ title", artist = "ミ泣ャ", album = "ミ泣ャ album", durationMs = 1000L,
            localFile = audio
        ),
        unknownArtistLabel = "Unknown Artist"
    )

    private fun identitySnapshot(song: SongItem): JSONObject = JSONObject()
        .put("name", song.name).put("artist", song.artist).put("album", song.album)
        .put("originalName", song.originalName).put("originalArtist", song.originalArtist)

    private fun contentRows(audio: File): List<SongItem> = (1L..257L).map { id ->
        quickSong(audio, id).copy(localFilePath = null, mediaUri = "content://scan.rows/tree/root/document/track$id")
    }

    private suspend fun completeContentRows(songs: List<SongItem>, metadataReference: String?): CompletedScanSongs =
        LocalAudioImportManager.completeScannedSongs(
            context = context,
            songs = songs,
            progress = LocalAudioScanProgressEmitter(scanId = 1L, startedAt = 0L, onProgress = {}),
            visitedDirectories = 1,
            knownSidecarReferences = songs.associate { song ->
                requireNotNull(song.mediaUri) to LocalKnownSidecarReferences(
                    NearbyLyricReferences(null, null, null), metadata = metadataReference
                )
            }
        )

    private fun taggedFile(
        name: String,
        leadingFrames: ByteArray = byteArrayOf(),
        version: Int = 3,
        extendedHeader: ByteArray = byteArrayOf(),
        tagFlags: Int = 0,
        formatFlags: Int = 0
    ): File = directory.newFile(name).apply {
        val frames = extendedHeader + leadingFrames + frame("TIT2", "散场电影", version, formatFlags) +
            frame("TPE1", "许嵩", version, formatFlags) + frame("TALB", "寻宝游戏", version, formatFlags)
        val flags = tagFlags or if (extendedHeader.isNotEmpty()) 0x40 else 0
        writeBytes(byteArrayOf(0x49, 0x44, 0x33, version.toByte(), 0, flags.toByte()) + synchsafe(frames.size) + frames + ByteArray(32))
    }

    private fun frame(name: String, text: String, version: Int = 3, formatFlags: Int = 0): ByteArray {
        val payload = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
        return frame(name, payload, version, formatFlags)
    }

    private fun largeCoverFrame(version: Int = 3): ByteArray = frame("APIC", ByteArray(128 * 1024), version)

    private fun frame(name: String, payload: ByteArray, version: Int, formatFlags: Int = 0): ByteArray {
        if (version == 2) {
            val id = mapOf("TIT2" to "TT2", "TPE1" to "TP1", "TALB" to "TAL", "APIC" to "PIC").getValue(name)
            return id.toByteArray(Charsets.US_ASCII) + integer(payload.size).copyOfRange(1, 4) + payload
        }
        val size = if (version == 4) synchsafe(payload.size) else integer(payload.size)
        return name.toByteArray(Charsets.US_ASCII) + size + byteArrayOf(0, formatFlags.toByte()) + payload
    }

    private fun integer(value: Int) = ByteArray(4) { index -> (value ushr (8 * (3 - index))).toByte() }

    private fun synchsafe(value: Int) = ByteArray(4) { index -> ((value ushr (7 * (3 - index))) and 0x7F).toByte() }
}
