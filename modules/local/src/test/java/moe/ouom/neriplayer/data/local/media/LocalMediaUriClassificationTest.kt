package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaUriClassificationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }

    @Test
    fun `file content and absolute path uris are supported local media`() {
        assertTrue(uri(scheme = "FILE", path = "/music/a.mp3").isSupportedLocalMediaUri())
        assertTrue(uri(scheme = "content", authority = "media").isSupportedLocalMediaUri())
        assertTrue(uri(scheme = null, path = "/music/a.mp3").isSupportedLocalMediaUri())
        assertTrue(uri(scheme = "", path = "/music/a.mp3").isSupportedLocalMediaUri())
    }

    @Test
    fun `remote relative and pathless uris are not local media`() {
        assertFalse(uri(scheme = "https", path = "/music/a.mp3").isSupportedLocalMediaUri())
        assertFalse(uri(scheme = null, path = "music/a.mp3").isSupportedLocalMediaUri())
        assertFalse(uri(scheme = null, path = null).isSupportedLocalMediaUri())
    }

    @Test
    fun `media store uris require the content scheme and a media authority`() {
        assertTrue(isMediaStoreUri(uri(scheme = "CONTENT", authority = "media")))
        assertTrue(isMediaStoreUri(uri(scheme = "content", authority = "com.android.providers.media.documents")))
        assertFalse(isMediaStoreUri(uri(scheme = "content", authority = "com.example.provider")))
        assertFalse(isMediaStoreUri(uri(scheme = "https", authority = "media")))
        assertFalse(isMediaStoreUri(uri(scheme = null, authority = "media")))
    }

    @Test
    fun `external storage documents are recognized by scheme and authority`() {
        assertTrue(isExternalStorageDocumentUri(uri(scheme = "content", authority = "COM.ANDROID.EXTERNALSTORAGE.DOCUMENTS")))
        assertFalse(isExternalStorageDocumentUri(uri(scheme = "content", authority = "media")))
        assertFalse(isExternalStorageDocumentUri(uri(scheme = "content", authority = null)))
        assertFalse(isExternalStorageDocumentUri(uri(scheme = "file", authority = "com.android.externalstorage.documents")))
        assertFalse(isExternalStorageDocumentUri(uri(scheme = null, authority = "com.android.externalstorage.documents")))
    }

    @Test
    fun `document sidecar mutations are used for saf and media store sources only`() {
        assertTrue(shouldUseDocumentSidecarMutation(uri(scheme = "content", authority = "com.android.externalstorage.documents")))
        assertTrue(shouldUseDocumentSidecarMutation(uri(scheme = "content", authority = "media")))
        assertFalse(shouldUseDocumentSidecarMutation(uri(scheme = "content", authority = "com.example.provider")))
        assertFalse(shouldUseDocumentSidecarMutation(uri(scheme = "file", authority = null)))
        assertFalse(shouldUseDocumentSidecarMutation(uri(scheme = null, authority = "media")))
    }

    @Test
    fun `media store sidecar references are matched after trimming and lowercasing`() {
        assertTrue(isMediaStoreSidecarReference(" CONTENT://MEDIA/external/file/1 "))
        assertTrue(isMediaStoreSidecarReference("content://com.android.providers.media.documents/document/audio%3A1"))
        assertFalse(isMediaStoreSidecarReference("content://com.example.provider/lyrics/1"))
        assertFalse(isMediaStoreSidecarReference(null))
    }

    @Test
    fun `album art references need a volume and the audio albumart path`() {
        assertTrue(isMediaStoreCoverReference(" Content://Media/External/Audio/AlbumArt/12 "))
        assertFalse(isMediaStoreCoverReference("content://media/external/audio/albumart"))
        assertFalse(isMediaStoreCoverReference("content://media//audio/albumart/12"))
        assertFalse(isMediaStoreCoverReference("content://media/external/images/albumart/12"))
        assertFalse(isMediaStoreCoverReference("content://media/external/audio/media/12"))
        assertFalse(isMediaStoreCoverReference("https://img.example.com/albumart/12"))
    }

    @Test
    fun `cover references are redacted to scheme and hashes`() {
        val reference = "content://media/external/audio/albumart/3"

        val redacted = withParsedUris({ uri(scheme = "CONTENT", authority = "Media") }) {
            redactCoverReference(reference)
        }

        assertEquals("content/${Integer.toHexString("Media".hashCode())}#${Integer.toHexString(reference.hashCode())}", redacted)
    }

    @Test
    fun `cover references without a parsed scheme are redacted as paths`() {
        val reference = "/storage/emulated/0/Music/cover.jpg"
        val expected = "path/-#${Integer.toHexString(reference.hashCode())}"

        assertEquals(expected, redactCoverReference(reference))
        assertEquals(expected, withParsedUris({ uri(scheme = null, authority = null) }) { redactCoverReference(reference) })
        assertEquals(expected, withParsedUris({ throw IllegalArgumentException("bad uri") }) { redactCoverReference(reference) })
    }

    @Test
    fun `cover errors hide uris and storage paths`() {
        assertEquals(
            "IOException:open failed: <uri> and <path>",
            redactCoverError(IOException("open failed: content://media/external/images/1 and /storage/emulated/0/a.jpg"))
        )
        assertEquals("IllegalStateException:<no-message>", redactCoverError(IllegalStateException()))
        assertEquals("RuntimeException:${"x".repeat(160)}", redactCoverError(RuntimeException("x".repeat(200))))
    }

    @Test
    fun `content covers are validated from their stream`() {
        val cover = uri(scheme = "content", authority = "media")
        doReturn(ByteArrayInputStream(byteArrayOf(1, 2))).`when`(resolver).openInputStream(cover)

        assertEquals(CoverReferenceValidation.INVALID, validateContentCoverReference(context, cover))
    }

    @Test
    fun `decodable content covers are usable`() {
        val cover = uri(scheme = "content", authority = "media")
        doReturn(ByteArrayInputStream(byteArrayOf(1, 2))).`when`(resolver).openInputStream(cover)

        val validation = withDecodableImages { validateContentCoverReference(context, cover) }

        assertEquals(CoverReferenceValidation.USABLE, validation)
    }

    @Test
    fun `content cover failures map to their validation outcome`() {
        val denied = uri(scheme = "content", authority = "media")
        val missing = uri(scheme = "content", authority = "media")
        doThrow(SecurityException("denied")).`when`(resolver).openInputStream(denied)
        doThrow(FileNotFoundException("gone")).`when`(resolver).openInputStream(missing)

        assertEquals(CoverReferenceValidation.UNAVAILABLE, validateContentCoverReference(context, denied))
        assertEquals(CoverReferenceValidation.INVALID, validateContentCoverReference(context, missing))
    }

    @Test
    fun `content covers without a stream fall back to their descriptor`() {
        val noStream = uri(scheme = "content", authority = "media")
        val brokenStream = uri(scheme = "content", authority = "media")
        doReturn(null).`when`(resolver).openInputStream(noStream)
        doReturn(null).`when`(resolver).openFileDescriptor(noStream, "r")
        doThrow(IllegalStateException("provider crashed")).`when`(resolver).openInputStream(brokenStream)
        doThrow(SecurityException("denied")).`when`(resolver).openFileDescriptor(brokenStream, "r")

        assertEquals(CoverReferenceValidation.INVALID, validateContentCoverReference(context, noStream))
        assertEquals(CoverReferenceValidation.UNAVAILABLE, validateContentCoverReference(context, brokenStream))
    }

    @Test
    fun `cover descriptor failures map to their validation outcome`() {
        val missing = uri(scheme = "content", authority = "media")
        val crashed = uri(scheme = "content", authority = "media")
        doThrow(FileNotFoundException("gone")).`when`(resolver).openFileDescriptor(missing, "r")
        doThrow(IllegalStateException("provider crashed")).`when`(resolver).openFileDescriptor(crashed, "r")

        assertEquals(CoverReferenceValidation.INVALID, validateContentCoverDescriptor(context, missing))
        assertEquals(CoverReferenceValidation.UNAVAILABLE, validateContentCoverDescriptor(context, crashed))
    }

    @Test
    fun `cover files must exist hold data and decode`() {
        val missing = temporaryFolder.root.resolve("missing.jpg")
        val empty = temporaryFolder.newFile("empty.jpg")
        val cover = temporaryFolder.newFile("cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertFalse(isUsableCoverFile(missing))
        assertFalse(isUsableCoverFile(empty))
        assertFalse(isUsableCoverFile(cover))
        assertTrue(withDecodableImages { isUsableCoverFile(cover) })
    }

    @Test
    fun `share fallbacks prefer content media uris over the local uri`() {
        val local = uri(scheme = "content", authority = "media", text = "content://media/external/audio/media/2")

        withParsedUris({ invocation -> uri(scheme = "content", authority = "media", text = invocation) }) {
            assertEquals(
                "content://media/external/audio/media/1",
                resolveContentShareFallbackUri(local, "content://media/external/audio/media/1").toString()
            )
            assertEquals(
                "content://media/external/audio/media/2",
                resolveContentShareFallbackUri(local, "https://cdn.example.com/a.mp3").toString()
            )
            assertNull(resolveContentShareFallbackUri(null, "/music/a.mp3"))
        }
    }

    @Test
    fun `parsed share fallbacks are returned as is`() {
        val parsed = uri(scheme = "content", authority = "media", text = "content://media/external/audio/media/1")

        val fallback = withParsedUris({ parsed }) {
            resolveContentShareFallbackUri(null, "content://media/external/audio/media/1")
        }

        assertSame(parsed, fallback)
    }

    private fun <T> withParsedUris(parse: (String) -> Uri, block: () -> T): T {
        return mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { invocation -> parse(invocation.getArgument(0)) }
            block()
        }
    }

    private fun <T> withDecodableImages(block: () -> T): T {
        return mockStatic(BitmapFactory::class.java).use { decoder ->
            decoder.`when`<Any?> {
                BitmapFactory.decodeStream(any(InputStream::class.java), isNull(), any())
            }.thenAnswer { invocation ->
                invocation.getArgument<BitmapFactory.Options>(2).apply {
                    outWidth = 1
                    outHeight = 1
                }
                null
            }
            block()
        }
    }

    private fun uri(scheme: String?, authority: String? = null, path: String? = null, text: String? = null): Uri {
        val uri = mock(Uri::class.java)
        doReturn(scheme).`when`(uri).scheme
        doReturn(authority).`when`(uri).authority
        doReturn(path).`when`(uri).path
        if (text != null) {
            doReturn(text).`when`(uri).toString()
        }
        return uri
    }
}
