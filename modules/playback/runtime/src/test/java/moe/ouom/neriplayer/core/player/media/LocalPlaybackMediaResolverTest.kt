package moe.ouom.neriplayer.core.player.media

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.DocumentsContract
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
    fun `folder grant recovers a media store reference without global audio access`() {
        assertFolderGrantFallback("primary:Music", readGranted = true, expectedFallback = true)
    }

    @Test
    fun `folder fallback requires read access to the same volume and directory`() {
        assertFolderGrantFallback("primary:Music", readGranted = false, expectedFallback = false)
        assertFolderGrantFallback("1234-ABCD:Music", readGranted = true, expectedFallback = false)
        assertFolderGrantFallback("primary:Music/AlbumOther", readGranted = true, expectedFallback = false)
    }

    @Test
    fun `restoring a path only song keeps audio accessible through its folder grant`() {
        assertFolderGrantFallback("primary:Music", readGranted = true, expectedFallback = true, mediaStoreReference = false)
        assertFolderGrantFallback("primary:Music", readGranted = false, expectedFallback = false, mediaStoreReference = false)
    }

    @Test
    fun `raw and file uri records recover through the same folder grant`() {
        val path = "/storage/emulated/0/Music/Album/song.mp3"
        listOf(path, "file://$path").forEach { reference ->
            assertFolderGrantFallback("primary:Music", readGranted = true, expectedFallback = true, storedMediaReference = reference)
        }
    }

    @Test
    fun `missing paths and invalid references never manufacture a folder source`() {
        val resolver = mock(android.content.ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        val invalidFileUri = mock(Uri::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(uri.scheme).thenReturn("content")
        `when`(uri.authority).thenReturn("media")
        `when`(invalidFileUri.scheme).thenReturn("file")
        val reference = "content://media/external/audio/media/7"
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)
            uris.`when`<Uri> { Uri.parse("file:") }.thenReturn(invalidFileUri)
            uris.`when`<Uri> { Uri.parse("invalid") }.thenThrow(IllegalArgumentException("bad uri"))
            assertEquals(null, LocalPlaybackMediaResolver.source(song(), context))
            assertEquals(reference, LocalPlaybackMediaResolver.source(song(mediaUri = reference), context))
            assertEquals("invalid", LocalPlaybackMediaResolver.source(song(mediaUri = "invalid"), context))
            assertEquals("file:", LocalPlaybackMediaResolver.source(song(mediaUri = "file:"), context))
        }
    }

    private fun assertFolderGrantFallback(
        treeId: String,
        readGranted: Boolean,
        expectedFallback: Boolean,
        mediaStoreReference: Boolean = true,
        storedMediaReference: String? = null
    ) {
        val reference = "content://media/external/audio/media/7"
        val recoveredReference = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum%2Fsong.mp3"
        val mediaUri = mock(Uri::class.java)
        val treeUri = mock(Uri::class.java)
        val documentUri = mock(Uri::class.java)
        val resolver = mock(android.content.ContentResolver::class.java)
        val permission = mock(UriPermission::class.java)
        val descriptor = mock(AssetFileDescriptor::class.java)
        val fileUri = mock(Uri::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.persistedUriPermissions).thenReturn(listOf(permission))
        `when`(permission.uri).thenReturn(treeUri)
        `when`(permission.isReadPermission).thenReturn(readGranted)
        `when`(treeUri.authority).thenReturn("com.android.externalstorage.documents")
        `when`(mediaUri.scheme).thenReturn("content")
        `when`(documentUri.scheme).thenReturn("content")
        `when`(documentUri.toString()).thenReturn(recoveredReference)
        `when`(fileUri.scheme).thenReturn("file")
        `when`(fileUri.path).thenReturn("/storage/emulated/0/Music/Album/song.mp3")
        `when`(resolver.openAssetFileDescriptor(mediaUri, "r")).thenThrow(SecurityException("audio permission denied"))
        `when`(resolver.openAssetFileDescriptor(documentUri, "r")).thenReturn(descriptor)

        mockStatic(Uri::class.java).use { uris ->
            mockStatic(DocumentsContract::class.java).use { documents ->
                uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(mediaUri)
                uris.`when`<Uri> { Uri.parse(recoveredReference) }.thenReturn(documentUri)
                uris.`when`<Uri> { Uri.parse("file:///storage/emulated/0/Music/Album/song.mp3") }.thenReturn(fileUri)
                documents.`when`<String> { DocumentsContract.getTreeDocumentId(treeUri) }.thenReturn(treeId)
                documents.`when`<Uri> {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Music/Album/song.mp3")
                }.thenReturn(documentUri)

                val song = song(
                    localFilePath = "/storage/emulated/0/Music/Album/song.mp3".takeIf { storedMediaReference == null },
                    mediaUri = storedMediaReference ?: reference.takeIf { mediaStoreReference }
                )
                assertEquals(
                    if (expectedFallback) recoveredReference else song.mediaUri ?: song.localFilePath,
                    LocalPlaybackMediaResolver.source(song, context)
                )
                if (!mediaStoreReference || storedMediaReference != null) {
                    assertEquals(
                        if (expectedFallback) listOf(song) else emptyList<SongItem>(),
                        LocalPlaybackMediaResolver.sanitizeRestoredPlaylist(listOf(song), context)
                    )
                }
            }
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
