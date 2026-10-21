package moe.ouom.neriplayer.core.download

import android.content.Context
import android.os.Environment
import java.io.File
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ManagedDownloadClassificationDiagnosticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var defaultRoot: File
    private var previousDirectoryUri: String? = null

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC))
            .thenReturn(temporaryFolder.newFolder("Music"))
        `when`(context.filesDir).thenReturn(temporaryFolder.newFolder("files"))
        defaultRoot = ManagedDownloadRootResolver.defaultRootDirectory(context)
        previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        ManagedDownloadStorage.settings.updateDirectoryUri(null)
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ManagedDownloadStorage.settings.updateDirectoryUri(previousDirectoryUri)
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
    }

    @Test
    fun `remote identity explains managed classification without probing storage`() {
        val unusedContext = mock(Context::class.java)
        ManagedDownloadStorage.settings.updateDirectoryUri(
            "content://root-user:root-password@provider/tree/current?grant=root-secret#private"
        )
        val reference = "content://user:password@provider/tree/old/document/Song.flac?token=secret#private"
        val song = localSong(reference).copy(
            channelId = "netease",
            audioId = "42",
            sourceStableKey = "42|netease|"
        )

        assertBothModes(song, expected = true, reason = "identity_hint", context = unusedContext)

        classificationLogs().forEach { trace ->
            assertTrue(trace.contains("songId=42"))
            assertTrue(trace.contains("channel=netease"))
            assertTrue(trace.contains("audioIdPresent=true"))
            assertTrue(trace.contains("sourceStableKeyPresent=true"))
            assertTrue(trace.contains("configuredRoot=content://provider/tree/current"))
            assertTrue(trace.contains("reference=content://provider/tree/old/document/Song.flac"))
            assertFalse(trace.contains("password"))
            assertFalse(trace.contains("secret"))
            assertFalse(trace.contains("#private"))
        }
        verifyNoInteractions(unusedContext)
    }

    @Test
    fun `owned import copy reports the independent path instead of a content alias`() {
        val imported = File(defaultRoot, "Imports/Imported.flac")
        val song = localSong("content://provider/document/alias").copy(
            localFilePath = imported.absolutePath
        )

        assertBothModes(song, expected = false, reason = "import_copy")

        classificationLogs().forEach { trace ->
            assertTrue(trace.contains("reference=${imported.canonicalPath}"))
            assertTrue(trace.contains("channel=local"))
        }
    }

    @Test
    fun `path classification distinguishes private historical and unrelated files`() {
        val cases = listOf(
            Triple(File(defaultRoot, "Downloaded.flac").absolutePath, true, "private_path"),
            Triple("/storage/emulated/0/neriplayer-download/Legacy.flac", true, "historical_root"),
            Triple("/music/Unmanaged.flac", false, "unmatched")
        )
        cases.forEach { (reference, expected, reason) ->
            ShadowLog.clear()
            assertBothModes(localSong(reference), expected, reason)
            classificationLogs().forEach { trace ->
                assertTrue(trace.contains("reference=$reference"))
            }
        }
    }

    @Test
    fun `fast tree classification reports a mismatch without provider access`() {
        ManagedDownloadStorage.settings.updateDirectoryUri("content://provider/tree/current")
        val song = localSong("content://provider/tree/other/document/Song.flac")

        assertFalse(ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, song))

        val trace = classificationLogs().single()
        assertTrue(trace.contains("mode=fast"))
        assertTrue(trace.contains("result=false"))
        assertTrue(trace.contains("reason=current_tree"))
        assertTrue(trace.contains("configuredRoot=content://provider/tree/current"))
        assertTrue(trace.contains("reference=content://provider/tree/other/document/Song.flac"))
    }

    private fun assertBothModes(
        song: SongItem,
        expected: Boolean,
        reason: String,
        context: Context = this.context
    ) {
        assertEquals(expected, ManagedDownloadStorage.isLikelyManagedDownloadSong(context, song))
        assertEquals(expected, ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, song))
        val traces = classificationLogs()
        assertEquals("both classifier decisions must be observable", 2, traces.size)
        for (mode in listOf("slow", "fast")) {
            val trace = traces.single { it.contains("mode=$mode") }
            assertTrue(trace.contains("result=$expected"))
            assertTrue(trace.contains("reason=$reason"))
        }
    }

    private fun localSong(reference: String) = SongItem(
        id = 42L,
        name = "Imported",
        artist = "Artist",
        album = "__local_files__",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        mediaUri = reference,
        localFilePath = reference.takeIf { it.startsWith("/") },
        channelId = "local"
    )

    private fun classificationLogs(): List<String> = ShadowLog.getLogs()
        .map { it.msg }
        .filter { it.startsWith("LocalPlaybackTrace stage=managed_classification ") }
}
