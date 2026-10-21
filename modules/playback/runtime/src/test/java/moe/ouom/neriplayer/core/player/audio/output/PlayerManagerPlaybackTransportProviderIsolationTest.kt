package moe.ouom.neriplayer.core.player.audio.output

import android.app.Application
import android.content.ContentResolver
import android.content.UriPermission
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.content.Intent
import android.os.Looper
import android.provider.DocumentsContract
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.media.LocalPlaybackMediaResolver
import moe.ouom.neriplayer.core.player.policy.storage.RestorableLocalMediaState
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.RestoredPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerManagerPlaybackTransportProviderIsolationTest {
    @Test
    fun `restored raw path bootstrap snapshots do not open its folder provider`() {
        assertSnapshotKeepsRecoveryOffProvider("/storage/emulated/0/Music/Album/restore.mp3")
    }

    @Test
    fun `restored file uri bootstrap snapshots do not open its folder provider`() {
        assertSnapshotKeepsRecoveryOffProvider("file:///storage/emulated/0/Music/Album/restore.mp3")
    }

    @Test
    fun `relative paths remain candidates without touching the provider`() {
        assertSnapshotKeepsRecoveryOffProvider("unavailable-relative-music/restore.mp3")
    }

    @Test
    fun `absolute paths containing a colon remain candidates without touching the provider`() {
        assertSnapshotKeepsRecoveryOffProvider("/storage/emulated/0/Music/Artist:Title.flac")
    }

    @Test
    fun `file uri scheme matching is case insensitive without touching the provider`() {
        assertSnapshotKeepsRecoveryOffProvider("FILE:///storage/emulated/0/Music/Album/restore.mp3")
    }

    @Test
    fun `content and resource references keep their unknown restore admission without provider opens`() {
        listOf("content://media/external/audio/media/551", "android.resource://moe.ouom.neriplayer/raw/restore")
            .forEach { assertSnapshotKeepsRecoveryOffProvider(it, expectedState = RestorableLocalMediaState.UNKNOWN) }
    }

    @Test
    fun `blank unsupported and pathless file references cannot bootstrap local restored playback`() {
        listOf("", " ", "https://example.invalid/audio", "file:", "file://missing")
            .forEach { assertSnapshotKeepsRecoveryOffProvider(it, expectedCandidate = false) }
    }

    private fun assertSnapshotKeepsRecoveryOffProvider(
        reference: String,
        expectedCandidate: Boolean = true,
        expectedState: RestorableLocalMediaState = RestorableLocalMediaState.REVOKED
    ) {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        val manager = PlayerManager
        val applicationField = PlayerManager::class.java.getDeclaredField("application").apply { isAccessible = true }
        val previousApplication = applicationField.get(null)
        val previousInitialized = manager.initialized
        val previousSong = manager._currentSongFlow.value
        val previousQueue = manager.currentQueueSnapshot()
        val previousRestore = manager.restoredPlaybackSnapshot()
        val application = mock(Application::class.java)
        val resolver = mock(ContentResolver::class.java)
        val permission = mock(UriPermission::class.java)
        val descriptor = mock(AssetFileDescriptor::class.java)
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Music/Album/restore.mp3")
        val providerCalls = mutableListOf<String>()
        doReturn(resolver).`when`(application).contentResolver
        doReturn(PackageManager.PERMISSION_DENIED).`when`(application)
            .checkUriPermission(any(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.eq(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        doReturn(listOf(permission)).`when`(resolver).persistedUriPermissions
        doReturn(tree).`when`(permission).uri
        doReturn(true).`when`(permission).isReadPermission
        doAnswer {
            providerCalls += "openAssetFileDescriptor"
            descriptor
        }.`when`(resolver).openAssetFileDescriptor(document, "r")
        val song = SongItem(
            id = 551L, name = "Restored local source", artist = "Test artist", album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L, durationMs = 180_000L, coverUrl = null,
            mediaUri = reference, localFilePath = reference
        )
        assertEquals("文件夹具必须命中失效文件分支，content 则保持原有 UNKNOWN 语义",
            expectedState, LocalPlaybackMediaResolver.restorableState(reference, application))

        try {
            manager.application = application
            manager.initialized = true
            manager.publishCurrentQueue(listOf(song), 0)
            manager._currentSongFlow.value = song
            manager.setRestoredPlayback(positionMs = 12_345L, shouldResume = true)
            val pending = manager.restoredPlaybackSnapshot()

            // 初始启动和 Compose 的服务启动重试都只能读取内存中的恢复状态
            repeat(2) {
                val snapshot = PlayerManagerPlaybackTransportPort.transportSnapshot(includeRestore = true)
                assertTrue(snapshot.hasCurrentSong)
                assertEquals(expectedCandidate, snapshot.restoredPlaybackCanAutoResume)
            }

            assertSame(pending, manager.restoredPlaybackSnapshot())
            assertTrue(manager.restoredShouldResumePlayback)
            assertEquals(listOf(song), manager.currentQueueSnapshot().playlist)
            assertEquals(0, manager.currentQueueSnapshot().currentIndex)
            assertEquals("同步启动快照不得访问 DocumentsProvider", emptyList<String>(), providerCalls)
        } finally {
            manager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
            manager._currentSongFlow.value = previousSong
            manager.initialized = previousInitialized
            applicationField.set(null, previousApplication)
            when (previousRestore) {
                RestoredPlaybackState.None -> manager.clearRestoredPlayback()
                is RestoredPlaybackState.Paused -> manager.setRestoredPlayback(previousRestore.positionMs, false)
                is RestoredPlaybackState.ResumePending -> manager.setRestoredPlayback(previousRestore.positionMs, true)
            }
        }
    }
}
