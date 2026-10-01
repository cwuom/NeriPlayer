package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.audioimport.QuickImportedSongSeed
import moe.ouom.neriplayer.data.local.audioimport.selectHydratedLocalCoverReference
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`

class LocalMediaCoverIsolationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun bindMediaHost() {
        val downloads = mock(LocalMediaDownloadAccess::class.java)
        doReturn(emptyList<String>()).`when`(downloads).candidateFileNameTemplates(null)
        LocalMediaHostAccess.bind(
            downloads = downloads,
            covers = mock(LocalMediaCoverAccess::class.java),
            crashLogs = CrashLogCleanup { false }
        )
    }

    @Test
    fun `songs without their own cover do not inherit a shared album thumbnail`() {
        listOf("B", "C", "D").forEachIndexed { index, name ->
            val song = LocalAudioImportManager.buildQuickImportedSong(
                seed = QuickImportedSongSeed(
                    sourceRef = "content://media/external/audio/media/${index + 2}",
                    displayName = "$name.mp3",
                    title = name,
                    artist = "Artist",
                    album = "Shared Album",
                    durationMs = 120_000L,
                    mediaStoreCoverUri = "content://media/external/audio/albumart/17"
                ),
                unknownArtistLabel = "Unknown Artist"
            )

            assertNull(name, song.coverUrl)
            assertNull(name, song.originalCoverUrl)
        }
    }

    @Test
    fun `a precise sidecar remains available when the album thumbnail belongs to another song`() {
        val song = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = "content://media/external/audio/media/2",
                displayName = "B.mp3",
                title = "B",
                artist = "Artist",
                album = "Shared Album",
                durationMs = 120_000L,
                nearbyCoverUri = "file:///music/Covers/B.jpg",
                mediaStoreCoverUri = "content://media/external/audio/albumart/17"
            ),
            unknownArtistLabel = "Unknown Artist"
        )

        assertEquals("file:///music/Covers/B.jpg", song.coverUrl)
        assertEquals("file:///music/Covers/B.jpg", song.originalCoverUrl)
    }

    @Test
    fun `detailed embedded artwork replaces legacy album artwork without changing song identity`() {
        val quick = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = "content://media/external/audio/media/1",
                displayName = "A.mp3",
                title = "A",
                artist = "Artist",
                album = "Shared Album",
                durationMs = 120_000L
            ),
            unknownArtistLabel = "Unknown Artist"
        ).copy(
            coverUrl = "content://media/external/audio/albumart/17",
            originalCoverUrl = "content://media/external/audio/albumart/17"
        )
        val detailed = quick.copy(
            coverUrl = "file:///data/local_audio_covers/A.jpg",
            originalCoverUrl = "file:///data/local_audio_covers/A.jpg"
        )

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(quick, detailed)

        assertEquals("file:///data/local_audio_covers/A.jpg", merged.coverUrl)
        assertEquals("file:///data/local_audio_covers/A.jpg", merged.originalCoverUrl)
        assertEquals(quick.id, merged.id)
        assertEquals("content://media/external/audio/media/1", merged.mediaUri)
        assertEquals(quick.audioId, merged.audioId)
    }

    @Test
    fun `detailed scan clears a legacy shared thumbnail when the song has no cover`() {
        val quick = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = "content://media/external_primary/audio/media/2",
                displayName = "B.mp3",
                title = "B",
                artist = "Artist",
                album = "Shared Album",
                durationMs = 120_000L
            ),
            unknownArtistLabel = "Unknown Artist"
        ).copy(
            coverUrl = "content://media/external_primary/audio/albumart/17",
            originalCoverUrl = "content://media/external_primary/audio/albumart/17"
        )

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(
            quick,
            quick.copy(coverUrl = null, originalCoverUrl = null)
        )

        assertNull(merged.coverUrl)
        assertNull(merged.originalCoverUrl)
    }

    @Test
    fun `sidecar hydration cannot restore an album thumbnail from old metadata`() {
        assertNull(
            selectHydratedLocalCoverReference(
                sidecarCover = "content://media/external/audio/albumart/17",
                existingCover = "content://media/external_primary/audio/albumart/17",
                reboundCover = null,
                metadataFallbackCover = "content://media/0123-4567/audio/albumart/17"
            )
        )
    }

    @Test
    fun `identity hydration does not repersist legacy album artwork from a real sidecar`() {
        val audio = tempFolder.newFile("B.mp3")
        File(audio.parentFile, "B.mp3.npmeta.json").writeText(
            """
                {"name":"B","artist":"Artist","album":"Shared Album",
                 "coverUrl":"content://media/external/audio/albumart/17",
                 "originalCoverUrl":"content://media/external/audio/albumart/17"}
            """.trimIndent()
        )
        val song = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = audio.absolutePath,
                displayName = audio.name,
                title = "B",
                artist = "Artist",
                album = "Shared Album",
                durationMs = 120_000L,
                localFile = audio
            ),
            unknownArtistLabel = "Unknown Artist"
        ).copy(
            coverUrl = "content://media/external/audio/albumart/17",
            originalCoverUrl = "content://media/external/audio/albumart/17"
        )

        val hydrated = LocalAudioImportManager.hydrateLocalSongIdentityMetadata(
            mock(Context::class.java),
            song
        )

        assertNull(hydrated.coverUrl)
        assertNull(hydrated.originalCoverUrl)
    }

    @Test
    fun `text hydration clears legacy album artwork even when cover probing is deferred`() {
        val audio = tempFolder.newFile("C.mp3")
        val song = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = audio.absolutePath,
                displayName = audio.name,
                title = "C",
                artist = "Artist",
                album = "Shared Album",
                durationMs = 120_000L,
                localFile = audio
            ),
            unknownArtistLabel = "Unknown Artist"
        ).copy(
            coverUrl = "content://media/external/audio/albumart/17",
            originalCoverUrl = "content://media/external/audio/albumart/17"
        )

        val hydrated = LocalAudioImportManager.hydrateLocalSongTextMetadata(
            context = mock(Context::class.java),
            song = song,
            resolveCoverFallback = false,
            includeEmbeddedFallback = false,
            includeEmbeddedCoverFallback = false,
            resolveDurationFallback = false
        )

        assertNull(hydrated.coverUrl)
        assertNull(hydrated.originalCoverUrl)
    }

    @Test
    fun `displaying a legacy album thumbnail falls back to the default cover`() {
        assertNull(
            resolveDisplayCoverUrl(
                customCoverUrl = null,
                currentCoverUrl = "content://media/external/audio/albumart/17",
                localCoverUrl = null,
                onMainThread = true
            )
        )
        val song = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = "content://media/external/audio/media/2",
                displayName = "B.mp3",
                title = "B",
                artist = "Artist",
                album = "Shared Album",
                durationMs = 120_000L
            ),
            unknownArtistLabel = "Unknown Artist"
        ).copy(coverUrl = "content://media/external/audio/albumart/17")

        assertNull(song.displayCoverUrl())
        assertEquals(
            "content://photos/user-selected.jpg",
            song.copy(customCoverUrl = "content://photos/user-selected.jpg").displayCoverUrl()
        )
        assertEquals(
            "https://example.com/platform-cover.jpg",
            song.copy(coverUrl = "https://example.com/platform-cover.jpg").displayCoverUrl()
        )
    }

    @Test
    fun `readable album thumbnails are rejected even when their image data is valid`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        doReturn("content").`when`(uri).scheme
        doReturn("content://media/external/audio/albumart/17").`when`(uri).toString()
        doReturn(resolver).`when`(context).contentResolver
        doReturn(ByteArrayInputStream(byteArrayOf(1))).`when`(resolver).openInputStream(uri)
        mockStatic(BitmapFactory::class.java).use { decoder ->
            decoder.`when`<Any?> {
                BitmapFactory.decodeStream(any(InputStream::class.java), isNull(), any())
            }.thenAnswer { invocation ->
                invocation.getArgument<BitmapFactory.Options>(2).apply {
                    outWidth = 1
                    outHeight = 1
                }
                null
            }

            assertEquals(CoverReferenceValidation.INVALID, validateCoverReference(context, uri))
        }
    }

    @Test
    fun `fast MediaStore cover lookup does not return another song's album image`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val source = mock(Uri::class.java)
        val albumCover = mock(Uri::class.java)
        val cursor = mock(Cursor::class.java)
        doReturn("media").`when`(source).authority
        doReturn("content://media/external/audio/media/2").`when`(source).toString()
        doReturn("content").`when`(albumCover).scheme
        doReturn("content://media/external/audio/albumart/17").`when`(albumCover).toString()
        doReturn(resolver).`when`(context).contentResolver
        doReturn(tempFolder.root).`when`(context).filesDir
        `when`(resolver.query(source, arrayOf(MediaStore.Audio.Media.ALBUM_ID), null, null, null))
            .thenReturn(cursor)
        doReturn(true).`when`(cursor).moveToFirst()
        doReturn(0).`when`(cursor).getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
        doReturn(17L).`when`(cursor).getLong(0)
        doReturn(ByteArrayInputStream(byteArrayOf(1))).`when`(resolver).openInputStream(albumCover)
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse("content://media/external/audio/albumart/17") }
                .thenReturn(albumCover)
            mockStatic(BitmapFactory::class.java).use { decoder ->
                decoder.`when`<Any?> {
                    BitmapFactory.decodeStream(any(InputStream::class.java), isNull(), any())
                }.thenAnswer { invocation ->
                    invocation.getArgument<BitmapFactory.Options>(2).apply {
                        outWidth = 1
                        outHeight = 1
                    }
                    null
                }

                assertNull(LocalMediaSupport.peekMediaStoreAlbumArtUri(context, source))
            }
        }
    }
}
