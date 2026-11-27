package moe.ouom.neriplayer.core.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.kyant.taglib.AudioProperties
import com.kyant.taglib.AudioPropertiesReadStyle
import com.kyant.taglib.TagLib
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.manager.commit.inspectFinalizedDownloadedAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowParcelFileDescriptor

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    instrumentedPackages = ["com.kyant.taglib"],
    shadows = [
        FinalizedDownloadedAudioProbeTest.ProbeExtractor::class,
        FinalizedDownloadedAudioProbeTest.ProbeRetriever::class,
        FinalizedDownloadedAudioProbeTest.ProbeTagLib::class,
        FinalizedDownloadedAudioProbeTest.ProbeDescriptor::class
    ]
)
class FinalizedDownloadedAudioProbeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetProbes() {
        extractorFailure = null
        extractorMime = null
        extractorDurationUs = null
        extractorReleased = false
        containerProperties = null
        containerFailure = null
        retrieverHasAudio = null
        retrieverDurationMs = null
        retrieverReleased = false
        transferredDescriptors.clear()
    }

    @After
    fun closeTransferredDescriptors() {
        transferredDescriptors.values.forEach(ParcelFileDescriptor::close)
        transferredDescriptors.clear()
    }

    @Test
    fun `FLAC in MP4 remains readable when platform rejects it and TagLib only finds duration`() = runTest {
        extractorFailure = IllegalArgumentException()
        containerProperties = AudioProperties(2_000, 0, 0, 0)

        val probe = inspect("flac-in-mp4.m4a")

        assertTrue(probe.readable)
        assertEquals(2_000L, probe.durationMs)
    }

    @Test
    fun `video only MP4 cannot use movie header duration as audio evidence`() = runTest {
        extractorFailure = IllegalArgumentException()
        containerProperties = AudioProperties(2_000, 0, 0, 0)

        val probe = inspect("video-only.mp4")

        assertFalse(probe.readable)
        assertEquals(null, probe.durationMs)
    }

    @Test
    fun `MP4 fallback resolves duration when platform only confirms an audio track`() = runTest {
        extractorMime = "audio/flac"

        val probe = inspect("flac-in-mp4.m4a")

        assertTrue(probe.readable)
        assertEquals(2_000L, probe.durationMs)
    }

    @Test
    fun `movie duration without audio properties cannot override the audio track duration`() = runTest {
        extractorMime = "audio/flac"
        extractorDurationUs = 1_000_000L
        containerProperties = AudioProperties(2_000, 0, 0, 0)

        val probe = inspect("flac-in-mp4.m4a")

        assertTrue(probe.readable)
        assertEquals(1_000L, probe.durationMs)
    }

    @Test
    fun `container audio properties remain valid when the platform extractor throws`() = runTest {
        extractorFailure = IllegalArgumentException()
        containerProperties = AudioProperties(164_955, 1_411, 44_100, 2)

        val probe = inspect()

        assertTrue(probe.readable)
        assertEquals(164_955L, probe.durationMs)
        assertTrue(extractorReleased)
    }

    @Test
    fun `container audio properties remain valid when extractor reports no tracks`() = runTest {
        containerProperties = AudioProperties(164_955, 1_411, 44_100, 2)

        val probe = inspect()

        assertTrue(probe.readable)
        assertEquals(164_955L, probe.durationMs)
    }

    @Test
    fun `retriever confirms audio when neither other parser supports the container`() = runTest {
        extractorFailure = IllegalArgumentException()
        retrieverHasAudio = "yes"
        retrieverDurationMs = "164955"

        val probe = inspect()

        assertTrue(probe.readable)
        assertEquals(164_955L, probe.durationMs)
        assertTrue(retrieverReleased)
    }

    @Test
    fun `retriever duration without audio evidence does not make video readable`() = runTest {
        extractorMime = "video/avc"
        retrieverHasAudio = "no"
        retrieverDurationMs = "164955"

        val probe = inspect()

        assertFalse(probe.readable)
        assertEquals(null, probe.durationMs)
    }

    @Test
    fun `invalid container audio properties cannot validate a non audio payload`() = runTest {
        listOf(
            AudioProperties(164_955, 0, 0, 0),
            AudioProperties(164_955, 1_411, 44_100, 0),
            AudioProperties(0, 1_411, 44_100, 2)
        ).forEach { invalidProperties ->
            containerProperties = invalidProperties

            val probe = inspect()

            assertFalse(probe.readable)
            assertEquals(null, probe.durationMs)
        }
    }

    @Test
    fun `container milliseconds take priority over estimated MP3 extractor duration`() = runTest {
        extractorMime = "audio/mpeg"
        extractorDurationUs = 165_900_000L
        containerProperties = AudioProperties(164_955, 320, 44_100, 2)

        val probe = inspect()

        assertTrue(probe.readable)
        assertEquals(164_955L, probe.durationMs)
    }

    @Test
    fun `valid extractor audio remains available when container parsing fails`() = runTest {
        extractorMime = "audio/opus"
        extractorDurationUs = 164_955_000L
        containerFailure = IllegalArgumentException("unsupported container")

        val probe = inspect()

        assertTrue(probe.readable)
        assertEquals(164_955L, probe.durationMs)
    }

    @Test
    fun `non audio content stays unreadable when all parsers lack audio evidence`() = runTest {
        val probe = inspect()

        assertFalse(probe.readable)
        assertEquals(null, probe.durationMs)
    }

    @Test(expected = CancellationException::class)
    fun `extractor cancellation is never converted into fallback success`() = runTest {
        extractorFailure = CancellationException("cancelled")
        containerProperties = AudioProperties(164_955, 1_411, 44_100, 2)

        inspect()
    }

    @Test(expected = CancellationException::class)
    fun `container parser cancellation is never swallowed by duration fallback`() = runTest {
        extractorMime = "audio/mpeg"
        extractorDurationUs = 164_955_000L
        containerFailure = CancellationException("cancelled")

        inspect()
    }

    private suspend fun inspect(fixture: String? = null): GlobalDownloadManager.FinalizedDownloadedAudioProbe {
        val file = temporaryFolder.newFile("probe-${temporaryFolder.root.list().orEmpty().size}.m4a")
        if (fixture == null) {
            file.writeBytes(byteArrayOf(1, 2, 3))
        } else {
            checkNotNull(javaClass.getResourceAsStream("/download/finalized-audio/$fixture")).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return GlobalDownloadManager.inspectFinalizedDownloadedAudio(context, entry(file))
    }

    private fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
        name = file.name,
        reference = file.absolutePath,
        mediaUri = Uri.fromFile(file).toString(),
        localFilePath = file.absolutePath,
        sizeBytes = file.length(),
        lastModifiedMs = file.lastModified()
    )

    @Implements(MediaExtractor::class)
    class ProbeExtractor {
        @Implementation
        fun setDataSource(context: Context, uri: Uri, headers: Map<String, String>?) {
            require(context.packageName.isNotBlank() && uri.scheme == "file" && headers == null)
            extractorFailure?.let { throw it }
        }

        @Implementation
        fun getTrackCount(): Int = if (extractorMime == null) 0 else 1

        @Implementation
        fun getTrackFormat(index: Int): MediaFormat {
            require(index == 0)
            return MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, extractorMime)
                extractorDurationUs?.let { setLong(MediaFormat.KEY_DURATION, it) }
            }
        }

        @Implementation
        fun release() {
            extractorReleased = true
        }
    }

    @Implements(MediaMetadataRetriever::class)
    class ProbeRetriever {
        @Implementation
        fun setDataSource(context: Context, uri: Uri) {
            require(context.packageName.isNotBlank() && uri.scheme == "file")
        }

        @Implementation
        fun extractMetadata(key: Int): String? = when (key) {
            MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO -> retrieverHasAudio
            MediaMetadataRetriever.METADATA_KEY_DURATION -> retrieverDurationMs
            else -> null
        }

        @Implementation
        fun release() {
            retrieverReleased = true
        }
    }

    @Implements(value = TagLib::class, isInAndroidSdk = false)
    class ProbeTagLib {
        companion object {
            @JvmStatic
            @Implementation
            fun __staticInitializer__() = Unit

            @JvmStatic
            @Implementation
            fun getAudioProperties(fd: Int, readStyle: AudioPropertiesReadStyle): AudioProperties? {
                require(readStyle == AudioPropertiesReadStyle.Average)
                checkNotNull(transferredDescriptors.remove(fd)).use {
                    containerFailure?.let { throw it }
                    return containerProperties
                }
            }
        }
    }

    @Implements(ParcelFileDescriptor::class)
    class ProbeDescriptor : ShadowParcelFileDescriptor() {
        @RealObject
        private lateinit var descriptor: ParcelFileDescriptor

        @Implementation
        fun detachFd(): Int = descriptor.fd.also { fd ->
            transferredDescriptors[fd] = descriptor
        }
    }

    companion object {
        private var extractorFailure: Throwable? = null
        private var extractorMime: String? = null
        private var extractorDurationUs: Long? = null
        private var extractorReleased = false
        private var containerProperties: AudioProperties? = null
        private var containerFailure: Throwable? = null
        private var retrieverHasAudio: String? = null
        private var retrieverDurationMs: String? = null
        private var retrieverReleased = false
        private val transferredDescriptors = mutableMapOf<Int, ParcelFileDescriptor>()
    }
}
