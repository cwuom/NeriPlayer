package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_FILE_PREFIX
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_FILE_SUFFIX
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class ManagedDownloadImportedAudioClassificationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var defaultRoot: File
    private lateinit var importsDirectory: File
    private lateinit var uriMethods: MockedStatic<Uri>
    private var previousDirectoryUri: String? = null

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        val externalMusicDirectory = temporaryFolder.newFolder("Music")
        val filesDirectory = temporaryFolder.newFolder("files")
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC))
            .thenReturn(externalMusicDirectory)
        `when`(context.filesDir).thenReturn(filesDirectory)
        defaultRoot = ManagedDownloadRootResolver.defaultRootDirectory(context)
            .apply { check(mkdirs()) }
        importsDirectory = File(defaultRoot, "Imports").apply { check(mkdirs()) }

        previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        ManagedDownloadStorage.settings.updateDirectoryUri(null)
        ManagedDownloadStorage.snapshotCacheStore.invalidate()

        uriMethods = mockStatic(Uri::class.java)
        uriMethods.`when`<Uri> { Uri.parse(anyString()) }.thenAnswer { invocation ->
            val reference = invocation.getArgument<String>(0)
            val parsed = URI(reference)
            mock(Uri::class.java).also { uri ->
                `when`(uri.path).thenReturn(parsed.path)
                `when`(uri.scheme).thenReturn(parsed.scheme)
                `when`(uri.authority).thenReturn(parsed.authority)
                `when`(uri.toString()).thenReturn(reference)
            }
        }
        uriMethods.`when`<String> { Uri.decode(anyString()) }.thenAnswer { invocation ->
            val reference = invocation.getArgument<String>(0)
            URLDecoder.decode(reference.replace("+", "%2B"), StandardCharsets.UTF_8)
        }
    }

    @After
    fun tearDown() {
        if (::uriMethods.isInitialized) uriMethods.close()
        ManagedDownloadStorage.settings.updateDirectoryUri(previousDirectoryUri)
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
    }

    @Test
    fun `ordinary external imports are not managed downloads for paths or file uris`() {
        for (fileName in listOf("Imported.flac", "Imported.m4a", "Imported.mp3")) {
            val audio = File(importsDirectory, fileName)
            assertClassification(audio.absolutePath, expectedManaged = false)
            assertClassification(audio.toURI().toASCIIString(), expectedManaged = false)
        }
    }

    @Test
    fun `import namespace also accepts hidden names and mime fallback extensions`() {
        for (fileName in listOf(".Imported.mp3", "Imported.audio", "Imported")) {
            assertClassification(
                File(importsDirectory, fileName).absolutePath,
                expectedManaged = false
            )
        }
    }

    @Test
    fun `private downloads and other subdirectories keep managed classification`() {
        val relativePaths = listOf(
            "Downloaded.flac",
            "ImportsBackup/Song.flac",
            "Imports/nested/Song.flac"
        )
        for (relativePath in relativePaths) {
            assertClassification(
                File(defaultRoot, relativePath).absolutePath,
                expectedManaged = true
            )
        }
    }

    @Test
    fun `parent traversal cannot borrow the import namespace exemption`() {
        for (relativePath in listOf("../Downloaded.flac", "../../outside.flac")) {
            val escaped = File(importsDirectory, relativePath)
            assertClassification(escaped.absolutePath, expectedManaged = true)
            assertClassification(escaped.toURI().toASCIIString(), expectedManaged = true)
        }
    }

    @Test
    fun `legacy downloads remain managed even with a local channel`() {
        assertClassification(
            "/storage/emulated/0/neriplayer-download/Legacy.flac",
            expectedManaged = true
        )
    }

    @Test
    fun `explicit remote download identity takes precedence over import location`() {
        val song = localSong(File(importsDirectory, "Downloaded.flac").absolutePath)
            .copy(channelId = "netease", audioId = "42", sourceStableKey = "42|netease|")

        assertClassification(song, expectedManaged = true)
    }

    @Test
    fun `unfinished import references do not bypass managed completion gates`() {
        val fileNames = listOf(
            "Song.flac.npdl_pending.operation.pending",
            "Song.flac.npdl_pending.operation.flac",
            ".Song.flac.operation.partial",
            ".Song.flac.operation.stale",
            DOWNLOAD_STAGING_FILE_PREFIX + "Song" + DOWNLOAD_STAGING_FILE_SUFFIX
        )
        for (fileName in fileNames) {
            val unfinished = File(importsDirectory, fileName)
            assertClassification(unfinished.absolutePath, expectedManaged = true)
            assertClassification(unfinished.toURI().toASCIIString(), expectedManaged = true)
        }
    }

    @Test
    fun `import symlink pointing to a managed download stays managed`() {
        val downloaded = File(defaultRoot, "Downloaded.flac").apply { writeBytes(byteArrayOf(1)) }
        val link = File(importsDirectory, "Imported.flac")
        Files.createSymbolicLink(link.toPath(), downloaded.toPath())

        assertClassification(link.absolutePath, expectedManaged = true)
        assertClassification(link.toURI().toASCIIString(), expectedManaged = true)
    }

    private fun assertClassification(reference: String, expectedManaged: Boolean) {
        assertClassification(localSong(reference), expectedManaged)
    }

    private fun assertClassification(song: SongItem, expectedManaged: Boolean) {
        assertEquals(
            "slow classifier: ${song.mediaUri}",
            expectedManaged,
            ManagedDownloadStorage.isLikelyManagedDownloadSong(context, song)
        )
        assertEquals(
            "fast classifier: ${song.mediaUri}",
            expectedManaged,
            ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, song)
        )
    }

    private fun localSong(reference: String): SongItem = SongItem(
        id = 42L,
        name = "Imported",
        artist = "Artist",
        album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
        albumId = 0L,
        durationMs = 60_000L,
        coverUrl = null,
        mediaUri = reference,
        localFilePath = reference.takeIf { it.startsWith("/") },
        channelId = "local",
        audioId = "42"
    )
}
