package moe.ouom.neriplayer.testing

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshotSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoredLocalPlaybackThreadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun restoredRawAndFileSourcesKeepProgressUntilReadableWithoutMainThreadDiskProbes() {
        assumeTrue("仅在 API 35 模拟器运行", Build.VERSION.SDK_INT == 35 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertTrue("suspend 恢复必须从 instrumentation 线程调用", Looper.myLooper() != Looper.getMainLooper())
        val app = context.applicationContext as Application
        val preferences = readPlaybackPreferenceSnapshotSync(app)
        val savedQueue = runBlocking { SavedPlaybackQueue.read(context) }
        val directory = File(context.cacheDir, "restored-thread-${UUID.randomUUID()}")
        var failure: Throwable? = null
        try {
            check(directory.mkdirs())
            listOf(false, true).forEach { fileUri ->
                val audio = File(directory, if (fileUri) "file.wav" else "raw.wav")
                assertFalse(audio.exists())
                val reference = if (fileUri) Uri.fromFile(audio).toString() else audio.absolutePath
                val song = SongItem(
                    id = -System.nanoTime(), name = "Restored thread fixture", artist = "Offline fixture",
                    album = "Test", albumId = 0L, durationMs = 10_000L, coverUrl = null,
                    mediaUri = reference, localFilePath = audio.absolutePath.takeUnless { fileUri },
                    localFileName = audio.name, channelId = "local", audioId = audio.name
                )
                instrumentation.runOnMainSync {
                    PlayerManager.release()
                    PlayerManager.initializePreloaded(app, preferences.copy(
                        keepLastPlaybackProgress = true, maxCacheSizeBytes = 0L, usbExclusivePlayback = false
                    ), restored(song))
                }
                assertBootstrapWithoutDiskReads()
                assertNull(runBlocking { PlayerManager.resumeRestoredPlaybackIfNeeded() })
                instrumentation.runOnMainSync {
                    assertEquals(listOf(song), PlayerManager.currentQueueFlow.value)
                    assertEquals(song, PlayerManager.currentSongFlow.value)
                    assertEquals(PositionMs, PlayerManager.playbackPositionFlow.value)
                }
                writeWav(audio)
                val resumed = runBlocking { PlayerManager.resumeRestoredPlaybackIfNeeded() }
                instrumentation.runOnMainSync { PlayerManager.release() }
                assertEquals(PositionMs, resumed)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val failures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(failures::add)
            }
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup { check(!directory.exists() || directory.deleteRecursively()) }
            cleanup { instrumentation.runOnMainSync { PlayerManager.initializePreloaded(app, preferences) } }
            if (failure != null) failures.forEach(failure::addSuppressed)
            else if (failures.isNotEmpty()) {
                val first = failures.first()
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun restored(song: SongItem) = RestoredPlayerStateSnapshot(
        playlist = listOf(song), currentIndex = 0, currentMediaUrl = null,
        repeatMode = 0, shuffleEnabled = false, shuffleRestorePlaylist = null, shuffleRestoreIndex = -1,
        resumePositionMs = PositionMs, shouldResumePlayback = true, originalPlaylistSize = 1, persistedIndex = 0
    )

    private fun assertBootstrapWithoutDiskReads() {
        val violations = ConcurrentLinkedQueue<String>()
        instrumentation.runOnMainSync {
            val previous = StrictMode.getThreadPolicy()
            try {
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads()
                    .penaltyListener({ it.run() }) { violation -> violations.add(violation.javaClass.name) }
                    .build())
                assertTrue(PlayerManager.shouldBootstrapPlaybackServiceOnAppLaunch())
            } finally {
                StrictMode.setThreadPolicy(previous)
            }
        }
        instrumentation.waitForIdleSync()
        assertTrue("bootstrap 主线程磁盘探测: $violations", violations.isEmpty())
    }

    private fun writeWav(audio: File) {
        val pcm = ByteArray(8_000 * 10 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).array()
        audio.outputStream().use { it.write(header); it.write(pcm) }
    }

    private data class SavedPlaybackQueue(
        val roomPrimary: Boolean,
        val state: PersistedState?,
        val legacyFiles: Map<String, ByteArray?>
    ) {
        suspend fun restore(context: Context) {
            val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
            if (roomPrimary && state != null) store.replaceSnapshot(state) else store.clear()
            legacyFiles.forEach { (name, bytes) ->
                val file = File(context.filesDir, name)
                if (bytes != null) file.writeBytes(bytes) else check(!file.exists() || file.delete())
            }
            if (!roomPrimary) store.markLegacyJsonPrimary(System.currentTimeMillis())
        }

        companion object {
            suspend fun read(context: Context): SavedPlaybackQueue {
                val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
                return SavedPlaybackQueue(store.isRoomPrimary(), store.readIfRoomPrimary(),
                    QueueFiles.associateWith { name -> File(context.filesDir, name).takeIf(File::exists)?.readBytes() })
            }
        }
    }

    private companion object {
        const val PositionMs = 1_500L
        val QueueFiles = listOf("last_playlist.json", "last_playback_state.json")
    }
}
