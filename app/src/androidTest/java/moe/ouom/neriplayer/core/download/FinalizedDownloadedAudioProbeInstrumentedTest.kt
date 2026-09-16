package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinalizedDownloadedAudioProbeInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun containerDurationIsReadInMilliseconds() = runTest {
        val audio = File.createTempFile("final-audio-probe-", ".wav", context.cacheDir)
        try {
            val sampleRate = 8_000
            val dataBytes = sampleRate * 2 * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
                .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
                .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
                .putShort(2).putShort(16)
                .put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
                .array()
            audio.outputStream().use { output ->
                output.write(header)
                output.write(ByteArray(dataBytes))
            }
            val probe = GlobalDownloadManager.inspectFinalizedDownloadedAudio(context, entry(audio))
            assertTrue(probe.readable)
            assertEquals(2_000L, probe.durationMs)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun nonAudioPayloadCannotBePublishedAsReadableAudio() = runTest {
        val audio = File.createTempFile("final-invalid-probe-", ".mp3", context.cacheDir)
        try {
            audio.writeText("not an audio stream")
            val probe = GlobalDownloadManager.inspectFinalizedDownloadedAudio(context, entry(audio))
            assertFalse(probe.readable)
            assertEquals(null, probe.durationMs)
        } finally {
            audio.delete()
        }
    }

    private fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
        name = file.name,
        reference = file.absolutePath,
        mediaUri = Uri.fromFile(file).toString(),
        localFilePath = file.absolutePath,
        sizeBytes = file.length(),
        lastModifiedMs = file.lastModified()
    )
}
