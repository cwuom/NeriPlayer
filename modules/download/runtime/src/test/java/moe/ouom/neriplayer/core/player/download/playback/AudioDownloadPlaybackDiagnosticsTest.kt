package moe.ouom.neriplayer.core.player.download.playback

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.core.player.download.ownership.AudioDownloadReferenceRegistry
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioDownloadPlaybackDiagnosticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var importedAudio: File
    private var previousDirectoryUri: String? = null
    private val coordinator = AudioDownloadPlaybackCoordinator(
        completedAudioReferenceRegistry = AudioDownloadReferenceRegistry(30_000L, 16),
        isSongDownloadActive = { false },
        directoryMutationWaitMs = 1L
    )

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC))
            .thenReturn(temporaryFolder.newFolder("Music"))
        `when`(context.filesDir).thenReturn(temporaryFolder.newFolder("files"))
        previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        ManagedDownloadStorage.settings.updateDirectoryUri(null)
        importedAudio = File(
            ManagedDownloadRootResolver.defaultRootDirectory(context),
            "Imports/Imported.flac"
        )
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ManagedDownloadStorage.settings.updateDirectoryUri(previousDirectoryUri)
    }

    @Test
    fun `missing independent import reports its terminal evidence at warning level`() = runBlocking {
        val result = coordinator.resolvePermittedLocalPlayback(
            context,
            localSong(),
            "content://user:password@media/external/audio/media/42?token=secret#fragment"
        )

        assertEquals(LocalPlaybackReferenceResolution.Missing, result)
        val trace = traceLogs().single()
        assertEquals(Log.WARN, trace.type)
        assertTrue(trace.msg.contains("id=42"))
        assertTrue(trace.msg.contains("channel=local"))
        assertTrue(trace.msg.contains("albumId=17"))
        assertTrue(trace.msg.contains("result=missing"))
        assertTrue(trace.msg.contains("reason=independent_import"))
        assertTrue(trace.msg.contains("rawEvidence=missing"))
        assertTrue(trace.msg.contains("rawProbeExecuted=true"))
        assertTrue(trace.msg.contains("rawReference=${importedAudio.canonicalPath}"))
        assertTrue(!trace.msg.contains("secret"))
        assertTrue(!trace.msg.contains("password"))
    }

    @Test
    fun `readable independent import reports the selected local copy once`() = runBlocking {
        check(importedAudio.parentFile!!.mkdirs())
        importedAudio.writeBytes(byteArrayOf(1, 2, 3))

        val result = coordinator.resolvePermittedLocalPlayback(context, localSong(), null)

        assertEquals(LocalPlaybackReferenceResolution.Playable(importedAudio.canonicalPath), result)
        val trace = traceLogs().single()
        assertTrue(trace.msg.contains("result=playable"))
        assertTrue(trace.msg.contains("reason=independent_import"))
        assertTrue(trace.msg.contains("rawEvidence=present"))
        assertTrue(trace.msg.contains("reference=${importedAudio.canonicalPath}"))
    }

    @Test
    fun `closed directory mutation gate reports timeout before inspecting the provider`() = runBlocking {
        val lease = ManagedDownloadDirectoryMutationFence.closeAndDrain()
        try {
            val result = coordinator.resolvePermittedLocalPlayback(
                context,
                localSong().copy(channelId = "netease"),
                "content://user:password@provider/document/song?token=secret#fragment"
            )

            assertTrue(result is LocalPlaybackReferenceResolution.TemporarilyUnavailable)
            val trace = traceLogs().single()
            assertEquals(Log.WARN, trace.type)
            assertTrue(trace.msg.contains("result=temporary"))
            assertTrue(trace.msg.contains("reason=directory_mutation_timeout"))
            assertTrue(trace.msg.contains("managedFast=true"))
            assertTrue(trace.msg.contains("rawEvidence=not_checked"))
            assertTrue(trace.msg.contains("rawProbeExecuted=false"))
            assertTrue(trace.msg.contains("fence=timeout"))
            assertTrue(!trace.msg.contains("secret"))
            assertTrue(!trace.msg.contains("password"))
        } finally {
            lease.close()
        }
    }

    @Test
    fun `provider failure reports the category without leaking the exception message`() = runBlocking {
        val reference = "content://user:password@provider/document/song?token=secret#fragment"
        doThrow(IllegalStateException("provider failed for $reference"))
            .`when`(context).contentResolver

        val result = coordinator.resolvePermittedLocalPlayback(
            context,
            localSong().copy(localFilePath = null, mediaUri = reference),
            reference
        )

        assertTrue(result is LocalPlaybackReferenceResolution.TemporarilyUnavailable)
        val trace = traceLogs().single()
        assertEquals(Log.WARN, trace.type)
        assertTrue(trace.msg.contains("result=temporary"))
        assertTrue(trace.msg.contains("rawEvidence=provider_failure"))
        assertTrue(trace.msg.contains("rawErrorType=java.lang.IllegalStateException"))
        assertTrue(trace.msg.contains("managedFast=false"))
        assertTrue(trace.msg.contains("managedFull=false"))
        assertTrue(trace.msg.contains("rawReferenceCompatible=true"))
        assertTrue(!trace.msg.contains("provider failed for"))
        assertTrue(!trace.msg.contains("secret"))
        assertTrue(!trace.msg.contains("password"))
        assertEquals(null, trace.throwable)
    }

    @Test
    fun `provider cancellation still propagates without being turned into a decision`() {
        val reference = "content://provider/document/song"
        val cancellation = CancellationException("provider request cancelled")
        doThrow(cancellation).`when`(context).contentResolver

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator.resolvePermittedLocalPlayback(
                    context,
                    localSong().copy(localFilePath = null, mediaUri = reference),
                    reference
                )
            }
        }

        assertEquals(cancellation.message, thrown.message)
        assertTrue(thrown === cancellation || thrown.cause === cancellation)
        assertTrue(traceLogs().isEmpty())
    }

    private fun localSong() = SongItem(
        id = 42L,
        name = "Imported",
        artist = "Artist",
        album = "__local_files__",
        albumId = 17L,
        durationMs = 1_000L,
        coverUrl = null,
        channelId = "local",
        localFilePath = importedAudio.absolutePath,
        mediaUri = "content://media/external/audio/media/42"
    )

    private fun traceLogs() = ShadowLog.getLogs().filter {
        it.msg.startsWith("LocalPlaybackTrace stage=download_permitted ")
    }
}
