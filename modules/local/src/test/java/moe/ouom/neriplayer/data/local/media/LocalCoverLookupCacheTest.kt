package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.storage.cleanup.LocalImportCache
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LocalCoverCacheHit
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.QueriedContentInfo
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.ResolvedInspectableLocalMedia
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import org.mockito.Mockito.doThrow
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
    fun `reusing a candidate while references load protects it from cleanup`() = runBlocking {
        val cached = embeddedCover("content://provider/document/song", byteArrayOf(1, 2, 3))
        val cache = LocalImportCache(cached.parentFile!!, {
            withDecodableImages { LocalMediaSupport.findCachedEmbeddedCover(context, "content://provider/document/song") }
            emptySet()
        }, fileAccessLock = LocalMediaSupport.embeddedCoverCacheLock) {}

        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), cache.clear())
        assertTrue(cached.exists())
    }

    @Test
    fun `a reused cover remains owned before its song is published`() = runBlocking {
        val cached = embeddedCover("content://provider/document/song", byteArrayOf(1, 2, 3))
        withDecodableImages {
            assertEquals(
                cached.toURI().toString(),
                LocalMediaSupport.findCachedEmbeddedCover(context, "content://provider/document/song")
            )
        }
        val cache = LocalImportCache(
            cached.parentFile!!,
            { emptySet() },
            fileAccessLock = LocalMediaSupport.embeddedCoverCacheLock,
            isCoverInUse = LocalMediaSupport::isEmbeddedCoverInUse
        ) { LocalMediaSupport.clearCoverLookupCache() }

        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), cache.clear())
        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), cache.clear())
        assertTrue(cached.exists())
    }

    @Test
    fun `a remembered embedded cover is retained when a lookup returns it`() = runBlocking {
        val cached = embeddedCover("content://provider/document/song", byteArrayOf(1, 2, 3))
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { uri(it.getArgument(0)) }
            withDecodableImages {
                LocalMediaSupport.rememberLocalCoverLookup("song|1|2", cached.toURI().toString())
                assertEquals(
                    LocalCoverCacheHit(cached.toURI().toString()),
                    LocalMediaSupport.cachedLocalCoverLookup(context, "song|1|2")
                )
            }
        }
        LocalMediaSupport.clearCoverLookupCache()
        val cache = LocalImportCache(
            cached.parentFile!!,
            { emptySet() },
            fileAccessLock = LocalMediaSupport.embeddedCoverCacheLock,
            isCoverInUse = LocalMediaSupport::isEmbeddedCoverInUse
        ) {}

        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), cache.clear())
        assertTrue(cached.exists())
    }

    @Test
    fun `a cover removed before retention is not returned as a usable reference`() {
        val cached = embeddedCover("content://provider/document/song", byteArrayOf(1, 2, 3))
        assertTrue(cached.delete())

        assertNull(LocalMediaSupport.retainEmbeddedCoverReference(context, cached.toURI().toString()))
        assertFalse(LocalMediaSupport.isEmbeddedCoverInUse(cached))
    }

    @Test
    fun `external and unknown cover files are not retained`() {
        val external = temporaryFolder.newFile("${"a".repeat(64)}.jpg").apply { writeBytes(byteArrayOf(1)) }
        val unknown = File(temporaryFolder.root, "local_audio_covers/selected.jpg").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(byteArrayOf(2))
        }
        listOf(external, unknown).forEach { file ->
            assertEquals(file.path, LocalMediaSupport.retainEmbeddedCoverReference(context, file.path))
            assertFalse(LocalMediaSupport.isEmbeddedCoverInUse(file))
        }
    }

    @Test
    fun `remote references do not access local storage`() {
        val remoteContext = mock(Context::class.java)
        doThrow(IllegalStateException("local storage unavailable")).`when`(remoteContext).filesDir

        assertEquals(
            "https://img.example.com/cover.jpg",
            LocalMediaSupport.retainEmbeddedCoverReference(remoteContext, "https://img.example.com/cover.jpg")
        )
        assertEquals(
            "content://provider/cover",
            LocalMediaSupport.retainEmbeddedCoverReference(remoteContext, "content://provider/cover")
        )
        assertNull(LocalMediaSupport.retainEmbeddedCoverReference(remoteContext, null))
    }

    @Test
    fun `empty covers and symbolic links are not returned as retained covers`() {
        val cached = embeddedCover("empty", ByteArray(0))
        val target = temporaryFolder.newFile("outside.jpg").apply { writeBytes(byteArrayOf(1)) }
        val link = LocalMediaSupport.embeddedCoverFile(context, "linked")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        assertNull(LocalMediaSupport.retainEmbeddedCoverReference(context, cached.path))
        assertNull(LocalMediaSupport.retainEmbeddedCoverReference(context, link.path))
        assertFalse(LocalMediaSupport.isEmbeddedCoverInUse(cached))
        assertFalse(LocalMediaSupport.isEmbeddedCoverInUse(target))
    }

    @Test
    fun `all local cover fields are retained without changing the song`() = runBlocking {
        val covers = listOf("cover", "custom", "original").map { embeddedCover(it, byteArrayOf(1, 2, 3)) }
        val orphan = embeddedCover("orphan", byteArrayOf(4))
        val song = SongItem(
            id = 1L, name = "Song", artist = "Artist", album = "Album", albumId = 0L, durationMs = 1L,
            coverUrl = covers[0].toURI().toString(), customCoverUrl = covers[1].path,
            originalCoverUrl = covers[2].toURI().toString()
        )
        assertSame(song, LocalMediaSupport.retainEmbeddedSongCovers(context, song))
        LocalMediaSupport.clearCoverLookupCache()
        val cache = LocalImportCache(
            covers.first().parentFile!!,
            { emptySet() },
            fileAccessLock = LocalMediaSupport.embeddedCoverCacheLock,
            isCoverInUse = LocalMediaSupport::isEmbeddedCoverInUse
        ) {}

        assertEquals(ExtraCacheClearResult(true, 1, 0, 1), cache.clear())
        assertFalse(orphan.exists())
        covers.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun `missing extracted cover fields are removed while other song metadata is preserved`() {
        val missing = LocalMediaSupport.embeddedCoverFile(context, "missing").toURI().toString()
        val song = SongItem(
            id = 1L, name = "Song", artist = "Artist", album = "Album", albumId = 0L, durationMs = 1L,
            coverUrl = missing, customCoverUrl = "https://img.example.com/custom.jpg", originalCoverUrl = missing
        )

        assertEquals(
            song.copy(coverUrl = null, originalCoverUrl = null),
            LocalMediaSupport.retainEmbeddedSongCovers(context, song)
        )
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

    @Test
    fun `invalidating a resolved song drops its file and uri lookups and resolved embedded covers`() {
        val file = temporaryFolder.newFile("song.flac")
        LocalMediaSupport.rememberLocalCoverLookup("${file.absolutePath}|12|3", "cover-a")
        LocalMediaSupport.rememberLocalCoverLookup("content://provider/document/song|1|2", "cover-b")
        LocalMediaSupport.rememberLocalCoverLookup("content://provider/document/other|1|2", "cover-c")
        val resolvedCovers = listOf("/music/song.flac", "/music/song.flac#taglib").map { embeddedCover(it, byteArrayOf(1)) }
        val uriCover = embeddedCover("content://provider/document/song", byteArrayOf(2))

        LocalMediaSupport.invalidateLocalCoverLookupCache(
            context,
            sourceUri,
            resolved(file = file, sizeBytes = null, lastModifiedMs = null, resolvedPath = "/music/song.flac")
        )

        assertEquals(setOf("content://provider/document/other|1|2"), cachedKeys())
        assertTrue(resolvedCovers.none(File::exists))
        assertTrue(uriCover.exists())
    }

    @Test
    fun `invalidating an unresolved song uses its uri for lookups and embedded covers`() {
        LocalMediaSupport.rememberLocalCoverLookup("content://provider/document/song|1|2", "cover-b")
        LocalMediaSupport.rememberLocalCoverLookup("/music/song.flac|1|2", "cover-a")
        val uriCovers = listOf("content://provider/document/song", "content://provider/document/song#taglib")
            .map { embeddedCover(it, byteArrayOf(1)) }

        LocalMediaSupport.invalidateLocalCoverLookupCache(context, sourceUri, null)

        assertEquals(setOf("/music/song.flac|1|2"), cachedKeys())
        assertTrue(uriCovers.none(File::exists))
    }

    private fun embeddedCover(uriKey: String, bytes: ByteArray): File {
        return LocalMediaSupport.embeddedCoverFile(context, uriKey).apply {
            requireNotNull(parentFile).mkdirs()
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
        val parsed = URI(value)
        doReturn(value).`when`(uri).toString()
        doReturn(parsed.scheme).`when`(uri).scheme
        doReturn(parsed.path).`when`(uri).path
        return uri
    }
}
