@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.resolver.netease

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.IOException
import moe.ouom.neriplayer.core.player.resolver.youtube.ConditionalChunkedHttpDataSource
import moe.ouom.neriplayer.core.player.resolver.youtube.shouldUseResumableChunkedHttpRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeteasePlaybackRangeSupportTest {

    @Test
    fun trustedNeteaseFlacUsesResumableRanges() {
        val dataSpec = DataSpec.Builder()
            .setUri("https://m701.music.126.net/audio/track.flac?redacted=1")
            .build()

        assertTrue(shouldUseNeteaseFlacResumableRange(dataSpec.uri))
        assertTrue(shouldUseResumableChunkedHttpRange(dataSpec))
    }

    @Test
    fun neteaseFlacCorrectsCdnMimeBeforeExtractorSelection() {
        val fixture = RangeFixture(
            content = sampleContent(),
            contentTypeHeaderName = "content-type"
        )
        val dataSource = createDataSource(fixture)
        val dataSpec = neteaseFlacDataSpec()

        val responseHeaders = try {
            dataSource.open(dataSpec)
            dataSource.responseHeaders
        } finally {
            dataSource.close()
        }

        assertEquals(listOf("audio/flac"), responseHeaders["Content-Type"])
        val firstExtractorName = DefaultExtractorsFactory()
            .createExtractors(dataSpec.uri, responseHeaders)
            .first()
            .underlyingImplementation
            .javaClass
            .simpleName
        assertTrue(firstExtractorName.contains("Flac", ignoreCase = true))
    }

    @Test
    fun nonFlacKeepsCdnMimeForExtractorSelection() {
        val fixture = RangeFixture(content = sampleContent())
        val dataSource = createDataSource(fixture)
        val dataSpec = DataSpec.Builder()
            .setUri("https://m701.music.126.net/audio/track.mp3?redacted=1")
            .build()

        val responseHeaders = try {
            dataSource.open(dataSpec)
            dataSource.responseHeaders
        } finally {
            dataSource.close()
        }

        assertEquals(listOf("audio/mpeg"), responseHeaders["Content-Type"])
    }

    @Test
    fun nonFlacLookalikeAndExplicitRangeDoNotEnableManagedRanges() {
        val flacUri = Uri.parse("https://m701.music.126.net/audio/track.flac")
        val explicitRange = DataSpec.Builder()
            .setUri(flacUri)
            .setHttpRequestHeaders(mapOf("Range" to "bytes=0-1023"))
            .build()

        assertFalse(
            shouldUseNeteaseFlacResumableRange(
                Uri.parse("https://music.126.net.example/audio/track.flac")
            )
        )
        assertFalse(
            shouldUseNeteaseFlacResumableRange(
                Uri.parse("https://m701.music.126.net/audio/track.mp3")
            )
        )
        assertFalse(shouldUseResumableChunkedHttpRange(explicitRange))
    }

    @Test
    fun earlyUpstreamEofResumesTheOriginalFlacBytes() {
        val fixture = RangeFixture(
            content = sampleContent(),
            readableBytesForOpen = { openIndex, requestedLength ->
                if (openIndex == 0) minOf(39, requestedLength) else requestedLength
            }
        )
        val dataSource = createDataSource(fixture)

        val read = try {
            dataSource.open(neteaseFlacDataSpec())
            readAll(dataSource)
        } finally {
            dataSource.close()
        }

        assertArrayEquals(fixture.content, read)
        assertEquals(listOf(0L, 39L), fixture.requestPositions)
    }

    @Test
    fun completeFlacEndsWithoutRetryingTheSameRange() {
        val fixture = RangeFixture(content = sampleContent())
        val dataSource = createDataSource(fixture)

        val read = try {
            dataSource.open(neteaseFlacDataSpec())
            readAll(dataSource)
        } finally {
            dataSource.close()
        }

        assertArrayEquals(fixture.content, read)
        assertEquals(listOf(0L), fixture.requestPositions)
    }

    @Test
    fun zeroByteRangeIsRetriedOnlyOnce() {
        val fixture = RangeFixture(
            content = sampleContent(),
            readableBytesForOpen = { _, _ -> 0 }
        )
        val dataSource = createDataSource(fixture)

        val failure = try {
            dataSource.open(neteaseFlacDataSpec())
            runCatching { readAll(dataSource) }.exceptionOrNull()
        } finally {
            dataSource.close()
        }

        assertTrue(failure is IOException)
        assertEquals(listOf(0L, 0L), fixture.requestPositions)
    }

    @Test
    fun zeroByteRangeRetryIsResetAfterThePreviousRangeMakesProgress() {
        val fixture = RangeFixture(
            content = sampleContent(size = 2 * 1024 * 1024),
            readableBytesForOpen = { openIndex, requestedLength ->
                if (openIndex == 0 || openIndex == 2) 0 else requestedLength
            }
        )
        val dataSource = createDataSource(fixture)

        val read = try {
            dataSource.open(neteaseFlacDataSpec())
            readAll(dataSource)
        } finally {
            dataSource.close()
        }

        assertArrayEquals(fixture.content, read)
        assertEquals(
            listOf(0L, 0L, 1024L * 1024L, 1024L * 1024L),
            fixture.requestPositions
        )
    }

    private fun createDataSource(fixture: RangeFixture): HttpDataSource {
        return ConditionalChunkedHttpDataSource(
            upstreamFactory = FixtureHttpDataSourceFactory(fixture),
            transformDataSpec = { it }
        )
    }

    private fun neteaseFlacDataSpec(): DataSpec {
        return DataSpec.Builder()
            .setUri("https://m701.music.126.net/audio/track.flac?redacted=1")
            .build()
    }

    private fun sampleContent(size: Int = 256 * 1024): ByteArray {
        return ByteArray(size) { index -> (index % 251).toByte() }
    }

    private fun readAll(dataSource: HttpDataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) {
                return output.toByteArray()
            }
            output.write(buffer, 0, read)
        }
    }
}

private class RangeFixture(
    val content: ByteArray,
    val readableBytesForOpen: (openIndex: Int, requestedLength: Int) -> Int = { _, length ->
        length
    },
    val contentTypeHeaderName: String = "Content-Type",
    val contentType: String = "audio/mpeg"
) {
    val requestPositions = mutableListOf<Long>()
}

private class FixtureHttpDataSourceFactory(
    private val fixture: RangeFixture
) : HttpDataSource.Factory {

    override fun createDataSource(): HttpDataSource = FixtureHttpDataSource(fixture)

    override fun setDefaultRequestProperties(
        defaultRequestProperties: Map<String, String>
    ): HttpDataSource.Factory = this
}

private class FixtureHttpDataSource(
    private val fixture: RangeFixture
) : BaseDataSource(true), HttpDataSource {

    private var opened = false
    private var currentUri: Uri? = null
    private var currentResponseHeaders: Map<String, List<String>> = emptyMap()
    private var currentResponseCode = -1
    private var readPosition = 0
    private var readableEndPosition = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val startPosition = dataSpec.position.toInt()
        val availableLength = (fixture.content.size - startPosition).coerceAtLeast(0)
        val requestedLength = when (dataSpec.length) {
            C.LENGTH_UNSET.toLong() -> availableLength
            else -> minOf(dataSpec.length, availableLength.toLong()).toInt()
        }
        val openIndex = fixture.requestPositions.size
        fixture.requestPositions += dataSpec.position
        val readableLength = fixture.readableBytesForOpen(openIndex, requestedLength)
            .coerceIn(0, requestedLength)
        val endPosition = startPosition + requestedLength
        currentUri = dataSpec.uri
        currentResponseCode = 206
        currentResponseHeaders = mapOf(
            fixture.contentTypeHeaderName to listOf(fixture.contentType),
            "Content-Length" to listOf(requestedLength.toString()),
            "Content-Range" to listOf(
                "bytes $startPosition-${endPosition - 1}/${fixture.content.size}"
            )
        )
        readPosition = startPosition
        readableEndPosition = startPosition + readableLength
        opened = true
        transferStarted(dataSpec)
        return requestedLength.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (readPosition >= readableEndPosition) return C.RESULT_END_OF_INPUT

        val readLength = minOf(length, readableEndPosition - readPosition)
        fixture.content.copyInto(
            destination = buffer,
            destinationOffset = offset,
            startIndex = readPosition,
            endIndex = readPosition + readLength
        )
        readPosition += readLength
        bytesTransferred(readLength)
        return readLength
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        currentUri = null
        currentResponseHeaders = emptyMap()
        currentResponseCode = -1
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = currentResponseHeaders

    override fun getResponseCode(): Int = currentResponseCode

    override fun setRequestProperty(name: String, value: String) = Unit

    override fun clearRequestProperty(name: String) = Unit

    override fun clearAllRequestProperties() = Unit
}
