package moe.ouom.neriplayer.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.grantRuntimePermissions
import moe.ouom.neriplayer.testutil.playbackRuntimePermissions
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class MiniPlayerPlaybackTransitionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
        grantRuntimePermissions(*playbackRuntimePermissions())
        composeRule.runOnIdle {
            PlayerManager.release()
            PlayerManager.initialize(composeRule.activity.application)
        }
    }

    @After
    fun tearDown() {
        composeRule.runOnIdle {
            PlayerManager.release()
        }
    }

    @Test
    fun playLocalSong_showsMiniPlayer_andLogsFrameStats() {
        val song = createLocalSong()
        val recorder = FrameStatsRecorder(composeRule.activity.window)

        recorder.start()
        composeRule.runOnIdle {
            PlayerManager.playPlaylist(listOf(song), startIndex = 0)
        }

        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText(song.name).fetchSemanticsNodes().isNotEmpty()
        }

        Thread.sleep(500)
        val stats = recorder.stop()
        Log.i(
            TAG,
            "mini player transition: totalFrames=${stats.totalFrames}, " +
                "jankyFrames=${stats.jankyFrames}, slowFrames50Ms=${stats.slowFrames50Ms}, " +
                "worstFrameMs=${stats.worstFrameMs}"
        )

        assertTrue("播放后没有采集到任何帧数据", stats.totalFrames > 0)
    }

    private fun createLocalSong(): SongItem {
        val context = composeRule.activity
        val file = File(context.cacheDir, "mini_player_perf_tone.wav")
        if (!file.exists()) {
            writeTestToneWav(file)
        }
        return SongItem(
            id = 42L,
            name = "mini_player_perf_tone",
            artist = "androidTest",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1_200L,
            coverUrl = null,
            mediaUri = Uri.fromFile(file).toString(),
            localFilePath = file.absolutePath
        )
    }

    private fun writeTestToneWav(file: File) {
        val sampleRate = 44_100
        val durationSeconds = 1
        val totalSamples = sampleRate * durationSeconds
        val pcmData = ByteArray(totalSamples * 2)
        val frequencyHz = 440.0
        for (sampleIndex in 0 until totalSamples) {
            val amplitude = (sin(2.0 * PI * frequencyHz * sampleIndex / sampleRate) * Short.MAX_VALUE * 0.2)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            val byteIndex = sampleIndex * 2
            pcmData[byteIndex] = (amplitude.toInt() and 0xFF).toByte()
            pcmData[byteIndex + 1] = ((amplitude.toInt() shr 8) and 0xFF).toByte()
        }

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcmData.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(pcmData.size)
        }.array()

        FileOutputStream(file).use { output ->
            output.write(header)
            output.write(pcmData)
        }
    }

    private class FrameStatsRecorder(private val window: Window) {
        private val handler = Handler(Looper.getMainLooper())
        private val frameDurationsNs = CopyOnWriteArrayList<Long>()
        private val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            frameDurationsNs += frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
        }

        fun start() {
            frameDurationsNs.clear()
            window.addOnFrameMetricsAvailableListener(listener, handler)
        }

        fun stop(): FrameStatsSnapshot {
            window.removeOnFrameMetricsAvailableListener(listener)
            val frameDurationsMs = frameDurationsNs
                .map { TimeUnit.NANOSECONDS.toMillis(it) }
                .filter { it > 0L }
            return FrameStatsSnapshot(
                totalFrames = frameDurationsMs.size,
                jankyFrames = frameDurationsMs.count { it > 16L },
                slowFrames50Ms = frameDurationsMs.count { it >= 50L },
                worstFrameMs = frameDurationsMs.maxOrNull() ?: 0L
            )
        }
    }

    private data class FrameStatsSnapshot(
        val totalFrames: Int,
        val jankyFrames: Int,
        val slowFrames50Ms: Int,
        val worstFrameMs: Long
    )

    private companion object {
        const val TAG = "MiniPlayerPerfTest"
    }
}
