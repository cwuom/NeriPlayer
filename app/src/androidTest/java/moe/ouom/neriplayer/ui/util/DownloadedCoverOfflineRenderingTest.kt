package moe.ouom.neriplayer.ui.util

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaCovers
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaDownloads
import moe.ouom.neriplayer.core.download.manager.catalog.publishDownloadedSongs
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadedCoverOfflineRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun requireVisibleComposeHost() {
        assumeComposeHostAvailable()
    }

    @Test
    fun downloadedM4aWithOnlyEmbeddedArtworkReplacesAnUnavailableRemoteCover() = runBlocking {
        assertDownloadedArtworkRenders(
            assetName = "metadata/synthetic-free-first-8.m4a",
            extension = "m4a",
            expectedColor = AndroidColor.RED
        )
    }

    @Test
    fun downloadedOpusWithOnlyEmbeddedArtworkReplacesAnUnavailableRemoteCover() = runBlocking {
        assertDownloadedArtworkRenders(
            assetName = "metadata/issue618-silent.opus",
            extension = "opus",
            expectedColor = AndroidColor.BLUE
        )
    }

    private suspend fun assertDownloadedArtworkRenders(
        assetName: String,
        extension: String,
        expectedColor: Int
    ) = GlobalDownloadManager.startupRecoveryMutex.withLock {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val baseContext = instrumentation.targetContext
        val ownedDirectory = File(baseContext.cacheDir, "downloaded-cover-offline-${UUID.randomUUID()}")
        check(ownedDirectory.mkdir())
        val context = IsolatedCoverContext(baseContext, ownedDirectory)
        val accessField = LocalMediaHostAccess::class.java.getDeclaredField("access").apply {
            isAccessible = true
        }
        val previousAccess = accessField.get(null)
        val previousSongs = GlobalDownloadManager.downloadedSongs.value
        val previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val mounted = mutableStateOf(true)
        var contentSet = false
        val offlineClient = OkHttpClient.Builder().addInterceptor {
            throw IOException("The downloaded artwork fixture forbids network access")
        }.build()
        val imageLoader = ImageLoader.Builder(context)
            .okHttpClient(offlineClient)
            .memoryCache(null)
            .diskCache(null)
            .allowHardware(false)
            .build()
        try {
            ManagedDownloadStorage.settings.updateDirectoryUri(null)
            ManagedDownloadStorage.snapshotCacheStore.invalidate()
            ManagedDownloadStorage.treeChildRegistry.clear()
            val audioDirectory = AndroidLocalMediaDownloads.defaultRootDirectory(context).apply {
                check(mkdirs())
            }
            val audio = File(audioDirectory, "finalized.$extension")
            instrumentation.context.assets.open(assetName).use { input ->
                audio.outputStream().use(input::copyTo)
            }
            embedArtwork(audio, expectedColor)
            assertEquals(listOf(audio.name), requireNotNull(audioDirectory.list()).toList())
            assertFalse(File(context.filesDir, "local_audio_covers").exists())

            val token = UUID.randomUUID().toString()
            val song = SongItem(
                id = System.nanoTime(),
                name = "Offline embedded $extension $token",
                artist = "Issue 618 fixture",
                album = "youtube_music",
                albumId = 0L,
                durationMs = 100L,
                coverUrl = "https://offline-artwork.invalid/$token.png",
                mediaUri = "https://music.youtube.com/watch?v=$token"
            )
            GlobalDownloadManager.publishDownloadedSongs(
                context,
                listOf(DownloadedSong(
                    id = song.id,
                    name = song.name,
                    artist = song.artist,
                    album = song.album,
                    filePath = audio.absolutePath,
                    fileSize = audio.length(),
                    downloadTime = 1L,
                    mediaUri = audio.toURI().toString(),
                    stableKey = song.stableKey(),
                    sourceIdentityAlbum = song.album,
                    sourceMediaUri = song.mediaUri,
                    localFileName = audio.name
                )),
                persistCatalog = false
            )
            assertTrue(GlobalDownloadManager.hasDownloadedSongCached(song))
            LocalMediaHostAccess.bind(
                downloads = AndroidLocalMediaDownloads,
                covers = AndroidLocalMediaCovers,
                crashLogs = CrashLogCleanup { false }
            )
            assertExternalImportArtwork(context, ownedDirectory, audio, song, expectedColor)
            publishFinalizedAudioSnapshot(context, audio, song)
            assertEquals(audio.absolutePath, ManagedDownloadStorage.peekDownloadedAudio(song)?.reference)
            assertNull(AudioDownloadManager.peekLocalCoverUri(song))
            assertFalse(File(context.filesDir, "local_audio_covers").exists())

            val resolvedCover = AtomicReference<String?>()
            val loadedCover = AtomicReference<String?>()
            composeRule.setContent {
                if (mounted.value) {
                    CompositionLocalProvider(LocalContext provides context) {
                        val cover = rememberSongDisplayCoverUrl(song)
                        SideEffect { resolvedCover.set(cover) }
                        val request = remember(cover) {
                            ImageRequest.Builder(context)
                                .data(cover)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            imageLoader = imageLoader,
                            contentDescription = "Downloaded embedded artwork",
                            contentScale = ContentScale.FillBounds,
                            onSuccess = { loadedCover.set(it.result.request.data.toString()) },
                            modifier = Modifier.size(96.dp).background(Color.Black).testTag(CoverTag)
                        )
                    }
                }
            }
            contentSet = true
            composeRule.waitUntil(timeoutMillis = 10_000L) {
                val cover = resolvedCover.get()
                cover?.toUri()?.scheme == "file" && loadedCover.get() == cover
            }
            val cover = requireNotNull(resolvedCover.get())
            val extracted = File(requireNotNull(cover.toUri().path))
            assertEquals(File(context.filesDir, "local_audio_covers").canonicalFile, extracted.parentFile?.canonicalFile)
            assertArtworkColor(cover, expectedColor)
            val rendered = composeRule.onNodeWithTag(CoverTag).captureToImage().asAndroidBitmap()
            assertEquals(expectedColor, rendered.getPixel(rendered.width / 2, rendered.height / 2))
            assertEquals(listOf(audio.name), requireNotNull(audioDirectory.list()).toList())
        } finally {
            try {
                if (contentSet) {
                    composeRule.runOnIdle { mounted.value = false }
                    composeRule.waitForIdle()
                }
            } finally {
                imageLoader.shutdown()
                offlineClient.dispatcher.executorService.shutdown()
                offlineClient.connectionPool.evictAll()
                ManagedDownloadStorage.snapshotCacheStore.invalidate()
                ManagedDownloadStorage.settings.updateDirectoryUri(previousDirectoryUri)
                ManagedDownloadStorage.treeChildRegistry.clear()
                accessField.set(null, previousAccess)
                GlobalDownloadManager.publishDownloadedSongs(baseContext, previousSongs, persistCatalog = false)
                check(ownedDirectory.deleteRecursively())
            }
        }
    }

    private suspend fun publishFinalizedAudioSnapshot(context: Context, audio: File, song: SongItem) {
        val entry = ManagedDownloadStorage.StoredEntry(
            name = audio.name,
            reference = audio.absolutePath,
            mediaUri = audio.toURI().toString(),
            localFilePath = audio.absolutePath,
            sizeBytes = audio.length(),
            lastModifiedMs = audio.lastModified()
        )
        val metadata = DownloadedAudioMetadata(
            stableKey = song.stableKey(),
            songId = song.id,
            identityAlbum = song.album,
            name = song.name,
            artist = song.artist,
            mediaUri = song.mediaUri,
            coverPath = null,
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
        )
        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(entry),
            metadataEntries = emptyList(),
            metadataByAudioName = mapOf(entry.name to metadata),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )
        val store = ManagedDownloadStorage.snapshotCacheStore
        store.putSnapshot(context, ManagedDownloadStorage.currentSnapshotCacheKey(context), snapshot)
        // 本例只需要驻留索引，取消本次延迟落盘以免更改应用已有的迁移标记
        val persistField = store.javaClass.getDeclaredField("snapshotPersistJob").apply { isAccessible = true }
        (persistField.get(store) as? Job)?.cancelAndJoin()
    }

    private suspend fun assertExternalImportArtwork(
        baseContext: Context,
        ownedDirectory: File,
        downloadedAudio: File,
        remoteSong: SongItem,
        expectedColor: Int
    ) {
        val importDirectory = File(ownedDirectory, "external-import").apply { check(mkdir()) }
        val importContext = IsolatedCoverContext(baseContext, importDirectory)
        val importedAudio = downloadedAudio.copyTo(File(importDirectory, "imported.${downloadedAudio.extension}"))
        val localSong = remoteSong.copy(
            mediaUri = importedAudio.toURI().toString(),
            localFilePath = importedAudio.absolutePath,
            localFileName = importedAudio.name,
            channelId = "local",
            coverUrl = null
        )
        val directCover = withContext(Dispatchers.IO) {
            LocalMediaSupport.resolveCoverUri(importContext, importedAudio.toURI().toString().toUri())
        }
        assertArtworkColor(requireNotNull(directCover), expectedColor)
        val displayedCover = withContext(Dispatchers.IO) { localSong.displayCoverUrl(importContext) }
        assertArtworkColor(requireNotNull(displayedCover), expectedColor)
    }

    private fun assertArtworkColor(reference: String, expectedColor: Int) {
        assertEquals("file", reference.toUri().scheme)
        val decoded = requireNotNull(BitmapFactory.decodeFile(requireNotNull(reference.toUri().path)))
        try {
            assertEquals(expectedColor, decoded.getPixel(decoded.width / 2, decoded.height / 2))
        } finally {
            decoded.recycle()
        }
    }

    private fun embedArtwork(audio: File, color: Int) {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            assertTrue(TagLib.savePictures(descriptor.dup().detachFd(), arrayOf(
                Picture(data = bytes, description = "", pictureType = "Front Cover", mimeType = "image/png")
            )))
        }
        ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            assertTrue(TagLib.getPictures(descriptor.dup().detachFd()).any { it.data.contentEquals(bytes) })
        }
    }

    private class IsolatedCoverContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        override fun getExternalFilesDir(type: String?): File =
            File(directory, "external/${type ?: "root"}").apply { mkdirs() }
    }

    private companion object {
        const val CoverTag = "downloaded-embedded-cover"
    }
}
