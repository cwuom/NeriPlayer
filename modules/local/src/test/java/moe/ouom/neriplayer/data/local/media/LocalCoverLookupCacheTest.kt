package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.InputStream
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LocalCoverCacheHit
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.QueriedContentInfo
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.ResolvedInspectableLocalMedia
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalCoverLookupCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)
    private val sourceUri = uri("content://provider/document/song")

    @Before
    fun bindFilesDir() {
        LocalMediaSupport.clearCoverLookupCache()
        doReturn(temporaryFolder.root).`when`(context).filesDir
    }

    @After
    fun clearCoverCaches() {
        LocalMediaSupport.clearCoverLookupCache()
    }

    @Test
    fun `cover lookups without a remembered entry miss`() {
        assertNull(LocalMediaSupport.cachedLocalCoverLookup(context, "song|1|2"))
    }

    @Test
    fun `negative cover lookups are dropped instead of being served`() {
        LocalMediaSupport.rememberLocalCoverLookup("song|1|2", "   ")
        assertTrue(cachedKeys().contains("song|1|2"))

        assertNull(LocalMediaSupport.cachedLocalCoverLookup(context, "song|1|2"))
        assertFalse(cachedKeys().contains("song|1|2"))
    }

    @Test
    fun `remembered usable covers are served trimmed`() {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { uri(it.getArgument(0)) }

            LocalMediaSupport.rememberLocalCoverLookup("song|1|2", " https://img.example.com/cover.jpg ")

            assertEquals(
                LocalCoverCacheHit("https://img.example.com/cover.jpg"),
                LocalMediaSupport.cachedLocalCoverLookup(context, "song|1|2")
            )
        }
    }

    @Test
    fun `remembered covers that are no longer usable are evicted`() {
        LocalMediaSupport.rememberLocalCoverLookup("song|1|2", "content://media/external/audio/albumart/3")

        assertNull(LocalMediaSupport.cachedLocalCoverLookup(context, "song|1|2"))
        assertFalse(cachedKeys().contains("song|1|2"))
    }

    @Test
    fun `remembering no cover replaces a previous cover`() {
        LocalMediaSupport.rememberLocalCoverLookup("song|1|2", "https://img.example.com/cover.jpg")
        LocalMediaSupport.rememberLocalCoverLookup("song|1|2", null)

        assertEquals(mapOf("song|1|2" to null), cachedEntries())
    }

    @Test
    fun `cover lookup keys prefer the resolved file identity`() {
        val file = temporaryFolder.newFile("song.flac").apply { writeBytes(ByteArray(12)) }

        assertEquals(
            "${file.absolutePath}|12|${file.lastModified()}",
            LocalMediaSupport.localCoverLookupKey(sourceUri, resolved(file = file, sizeBytes = 99L, lastModifiedMs = 5L))
        )
    }

    @Test
    fun `cover lookup keys fall back to provider metadata without a file`() {
        assertEquals(
            "content://provider/document/song|99|5",
            LocalMediaSupport.localCoverLookupKey(sourceUri, resolved(file = null, sizeBytes = 99L, lastModifiedMs = 5L))
        )
        assertEquals(
            "content://provider/document/song|-1|-1",
            LocalMediaSupport.localCoverLookupKey(sourceUri, resolved(file = null, sizeBytes = null, lastModifiedMs = null))
        )
    }

    @Test
    fun `missing embedded cover cache entries miss`() {
        assertNull(LocalMediaSupport.findCachedEmbeddedCover(context, "content://provider/document/song"))
    }

    @Test
    fun `undecodable embedded cover cache files are removed`() {
        val cached = embeddedCover("content://provider/document/song", byteArrayOf(1, 2, 3))

        assertNull(LocalMediaSupport.findCachedEmbeddedCover(context, "content://provider/document/song"))
        assertFalse(cached.exists())
    }

    @Test
    fun `decodable embedded cover cache files are reused`() {
        val cached = embeddedCover("content://provider/document/song", byteArrayOf(1, 2, 3))

        withDecodableImages {
            assertEquals(
                cached.toURI().toString(),
                LocalMediaSupport.findCachedEmbeddedCover(context, "content://provider/document/song")
            )
        }
        assertTrue(cached.exists())
    }

    @Test
    fun `retriever extraction reuses the cover cached for the resolved path`() {
        val cached = embeddedCover("/music/song.flac", byteArrayOf(4, 5, 6))

        withDecodableImages {
            assertEquals(
                cached.toURI().toString(),
                LocalMediaSupport.extractEmbeddedCoverWithRetriever(
                    context,
                    sourceUri,
                    resolved(file = null, sizeBytes = null, lastModifiedMs = null, resolvedPath = "/music/song.flac")
                )
            )
        }
    }

    @Test
    fun `retriever extraction without embedded art caches nothing`() {
        assertNull(
            LocalMediaSupport.extractEmbeddedCoverWithRetriever(
                context,
                sourceUri,
                resolved(file = null, sizeBytes = null, lastModifiedMs = null)
            )
        )
        assertFalse(File(temporaryFolder.root, "local_audio_covers").exists())
    }

    private fun embeddedCover(uriKey: String, bytes: ByteArray): File {
        return LocalMediaSupport.embeddedCoverFile(context, uriKey).apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun withDecodableImages(block: () -> Unit) {
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
            block()
        }
    }

    private fun cachedKeys(): Set<String> = cachedEntries().keys

    private fun cachedEntries(): Map<String, String?> = synchronized(LocalMediaSupport.localCoverLookupCache) {
        LinkedHashMap(LocalMediaSupport.localCoverLookupCache)
    }

    private fun resolved(
        file: File?,
        sizeBytes: Long?,
        lastModifiedMs: Long?,
        resolvedPath: String? = file?.absolutePath
    ) = ResolvedInspectableLocalMedia(
        queried = QueriedContentInfo(
            displayName = "song.flac",
            sizeBytes = sizeBytes,
            mimeType = "audio/flac",
            lastModifiedMs = lastModifiedMs,
            filePath = null,
            relativePath = null,
            title = null,
            artist = null,
            album = null,
            durationMs = null
        ),
        resolvedPath = resolvedPath,
        file = file,
        playableUri = sourceUri,
        displayName = "song.flac",
        fallbackTitle = "song",
        fileExtension = "flac"
    )

    private fun uri(value: String): Uri {
        val uri = mock(Uri::class.java)
        doReturn(value).`when`(uri).toString()
        return uri
    }
}
