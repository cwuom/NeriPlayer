package moe.ouom.neriplayer.core.player.media

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.net.Uri
import java.nio.file.Files
import moe.ouom.neriplayer.core.player.policy.storage.RestorableLocalMediaState
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalPlaybackMediaResolverTest {
    private val context = mock(Context::class.java)

    @Test
    fun `missing and blank references are neither readable nor restorable`() {
        listOf(null, " ").forEach { reference ->
            assertFalse(LocalPlaybackMediaResolver.isReadable(reference, context))
            assertEquals(
                RestorableLocalMediaState.REVOKED,
                LocalPlaybackMediaResolver.restorableState(reference, context)
            )
        }
    }

    @Test
    fun `readable file survives restore while missing file is dropped`() {
        val present = Files.createTempFile("neri-media-source", ".flac").toFile()
        try {
            val presentSong = song(localFilePath = present.absolutePath)
            val missingSong = song(localFilePath = "${present.absolutePath}.missing")
            val remoteSong = song(id = 2L, mediaUri = "https://example.org/audio")

            assertEquals(present.absolutePath, LocalPlaybackMediaResolver.source(presentSong, context))
            assertTrue(LocalPlaybackMediaResolver.isReadable(present.absolutePath, context))
            assertEquals(
                RestorableLocalMediaState.READABLE,
                LocalPlaybackMediaResolver.restorableState(present.absolutePath, context)
            )
            assertFalse(LocalPlaybackMediaResolver.isRestorableSong(missingSong, context))
            assertEquals(
                listOf(presentSong, remoteSong),
                LocalPlaybackMediaResolver.sanitizeRestoredPlaylist(
                    listOf(presentSong, missingSong, remoteSong),
                    context
                )
            )
        } finally {
            present.delete()
        }
    }

    @Test
    fun `unreadable preferred content reference falls back to readable file`() {
        val present = Files.createTempFile("neri-media-fallback", ".flac").toFile()
        val contentReference = "content://provider.example/document/missing"
        val uri = mock(Uri::class.java)
        val resolver = mock(android.content.ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(uri.scheme).thenReturn("content")
        try {
            mockStatic(Uri::class.java).use { uris ->
                uris.`when`<Uri> { Uri.parse(contentReference) }.thenReturn(uri)

                assertEquals(
                    present.absolutePath,
                    LocalPlaybackMediaResolver.source(
                        song(localFilePath = present.absolutePath, mediaUri = contentReference),
                        context
                    )
                )
                assertFalse(LocalPlaybackMediaResolver.isReadable(contentReference, context))
            }
        } finally {
            present.delete()
        }
    }

    @Test
    fun `content permission determines restorable state without requiring open descriptor`() {
        val reference = "content://provider.example/document/audio"
        val uri = mock(Uri::class.java)
        val resolver = mock(android.content.ContentResolver::class.java)
        `when`(uri.scheme).thenReturn("content")
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.persistedUriPermissions).thenReturn(emptyList())
        `when`(
            context.checkUriPermission(
                eq(uri),
                anyInt(),
                anyInt(),
                eq(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        ).thenReturn(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED)

        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)

            assertEquals(
                RestorableLocalMediaState.UNKNOWN,
                LocalPlaybackMediaResolver.restorableState(reference, context)
            )
            assertEquals(
                RestorableLocalMediaState.READABLE,
                LocalPlaybackMediaResolver.restorableState(reference, context)
            )
        }
    }

    @Test
    fun `persisted content permission keeps reference restorable`() {
        val reference = "content://provider.example/document/persisted"
        val uri = mock(Uri::class.java)
        val permission = mock(UriPermission::class.java)
        val resolver = mock(android.content.ContentResolver::class.java)
        `when`(uri.scheme).thenReturn("content")
        `when`(permission.isReadPermission).thenReturn(true)
        `when`(permission.uri).thenReturn(uri)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.persistedUriPermissions).thenReturn(listOf(permission))
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)

            assertEquals(
                RestorableLocalMediaState.READABLE,
                LocalPlaybackMediaResolver.restorableState(reference, context)
            )
        }
    }

    @Test
    fun `file uri is readable and unsupported uri is revoked`() {
        val present = Files.createTempFile("neri-media-uri", ".flac").toFile()
        val fileReference = "file://${present.absolutePath}"
        val unsupportedReference = "ftp://example.org/audio.flac"
        val fileUri = mock(Uri::class.java)
        val missingFileUri = mock(Uri::class.java)
        val unsupportedUri = mock(Uri::class.java)
        `when`(fileUri.scheme).thenReturn("file")
        `when`(fileUri.path).thenReturn(present.absolutePath)
        `when`(missingFileUri.scheme).thenReturn("file")
        `when`(unsupportedUri.scheme).thenReturn("ftp")
        try {
            mockStatic(Uri::class.java).use { uris ->
                uris.`when`<Uri> { Uri.parse(fileReference) }.thenReturn(fileUri)
                uris.`when`<Uri> { Uri.parse("file://missing") }.thenReturn(missingFileUri)
                uris.`when`<Uri> { Uri.parse(unsupportedReference) }.thenReturn(unsupportedUri)

                assertTrue(LocalPlaybackMediaResolver.isReadable(fileReference, context))
                assertEquals(
                    RestorableLocalMediaState.READABLE,
                    LocalPlaybackMediaResolver.restorableState(fileReference, context)
                )
                assertFalse(LocalPlaybackMediaResolver.isReadable("file://missing", context))
                assertEquals(
                    RestorableLocalMediaState.REVOKED,
                    LocalPlaybackMediaResolver.restorableState("file://missing", context)
                )
                assertFalse(LocalPlaybackMediaResolver.isReadable(unsupportedReference, context))
                assertEquals(
                    RestorableLocalMediaState.REVOKED,
                    LocalPlaybackMediaResolver.restorableState(unsupportedReference, context)
                )
            }
        } finally {
            present.delete()
        }
    }

    @Test
    fun `schemeless existing relative path remains readable and restorable`() {
        val file = Files.createTempFile(java.nio.file.Paths.get("."), "neri-relative-media", ".flac")
        val reference = file.toString()
        val uri = mock(Uri::class.java)
        try {
            mockStatic(Uri::class.java).use { uris ->
                uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)

                assertTrue(LocalPlaybackMediaResolver.isReadable(reference, context))
                assertEquals(
                    RestorableLocalMediaState.READABLE,
                    LocalPlaybackMediaResolver.restorableState(reference, context)
                )
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `provider descriptor makes content reference readable`() {
        val reference = "content://provider.example/document/readable"
        val uri = mock(Uri::class.java)
        val resolver = mock(android.content.ContentResolver::class.java)
        val descriptor = mock(AssetFileDescriptor::class.java)
        `when`(uri.scheme).thenReturn("content")
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.openAssetFileDescriptor(uri, "r")).thenReturn(descriptor)
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)

            assertTrue(LocalPlaybackMediaResolver.isReadable(reference, context))
        }
    }

    @Test
    fun `playable URL retains a remote reference and rejects blank input`() {
        val reference = "https://example.org/audio.m4a"
        val uri = mock(Uri::class.java)
        `when`(uri.scheme).thenReturn("https")
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)

            assertEquals(reference, LocalPlaybackMediaResolver.playableUrl(reference))
            assertEquals(null, LocalPlaybackMediaResolver.playableUrl(" "))
        }
    }

    @Test
    fun `raw and schemeless paths become file URLs`() {
        val rawPath = "/music/song.flac"
        val relativePath = "music/song.flac"
        val parsed = mock(Uri::class.java)
        val rawFileUri = mock(Uri::class.java)
        val relativeFileUri = mock(Uri::class.java)
        `when`(rawFileUri.toString()).thenReturn("file:///music/song.flac")
        `when`(relativeFileUri.toString()).thenReturn("file://music/song.flac")
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(relativePath) }.thenReturn(parsed)
            uris.`when`<Uri> { Uri.fromFile(java.io.File(rawPath)) }.thenReturn(rawFileUri)
            uris.`when`<Uri> { Uri.fromFile(java.io.File(relativePath)) }.thenReturn(relativeFileUri)

            assertEquals("file:///music/song.flac", LocalPlaybackMediaResolver.playableUrl(rawPath))
            assertEquals("file://music/song.flac", LocalPlaybackMediaResolver.playableUrl(relativePath))
        }
    }

    private fun song(
        id: Long = 1L,
        localFilePath: String? = null,
        mediaUri: String? = null
    ) = SongItem(
        id = id,
        name = "song",
        artist = "artist",
        album = "album",
        albumId = 1L,
        durationMs = 60_000L,
        coverUrl = null,
        mediaUri = mediaUri,
        localFilePath = localFilePath
    )
}
