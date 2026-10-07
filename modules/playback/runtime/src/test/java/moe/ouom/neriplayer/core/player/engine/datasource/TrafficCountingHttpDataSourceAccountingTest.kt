@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import android.util.Log
import java.io.IOException
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.traffic.TrafficUsageSource
import moe.ouom.neriplayer.data.traffic.TrafficStatsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.isNull
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`

class TrafficCountingHttpDataSourceAccountingTest {

    private data class Recorded(
        val networkType: TrafficNetworkType,
        val bytes: Long,
        val source: TrafficUsageSource
    )

    private val repository = mock(TrafficStatsRepository::class.java).also {
        `when`(it.currentNetworkType()).thenReturn(TrafficNetworkType.MOBILE)
    }
    private val flacUri = mockHttpUri("https://m701.music.126.net/20260101/abc/song.flac")
    private val plainUri = mockHttpUri("https://cdn.example.com/song.mp3")

    @Test
    fun `bytes read are reported once when the stream closes`() {
        val server = ScriptedHttpServer { ScriptedHttpResponse(body = ByteArray(300)) }
        val source = TrafficCountingHttpDataSource(server.createDataSource(), repository)

        source.open(httpGet(plainUri))
        assertEquals(300, source.readToEnd().size)
        assertEquals(emptyList<Recorded>(), recorded())

        source.close()
        source.close()

        assertEquals(listOf(Recorded(TrafficNetworkType.MOBILE, 300L, TrafficUsageSource.PLAYBACK)), recorded())
    }

    @Test
    fun `reopening flushes the previous stream under the network type sampled at its open`() {
        `when`(repository.currentNetworkType()).thenReturn(
            TrafficNetworkType.ROAMING,
            TrafficNetworkType.WIFI,
            TrafficNetworkType.MOBILE
        )
        val server = ScriptedHttpServer { ScriptedHttpResponse(body = ByteArray(90)) }
        val source = TrafficCountingHttpDataSource(
            server.createDataSource(),
            repository,
            TrafficUsageSource.DOWNLOAD
        )

        source.open(httpGet(plainUri))
        source.read(ByteArray(40), 0, 40)
        source.open(httpGet(plainUri))
        source.read(ByteArray(50), 0, 50)
        source.close()

        assertEquals(
            listOf(
                Recorded(TrafficNetworkType.WIFI, 40L, TrafficUsageSource.DOWNLOAD),
                Recorded(TrafficNetworkType.MOBILE, 50L, TrafficUsageSource.DOWNLOAD)
            ),
            recorded()
        )
    }

    @Test
    fun `failed delegate close still flushes counted bytes`() {
        val server = ScriptedHttpServer {
            ScriptedHttpResponse(headers = mapOf("Content-Type" to listOf("audio/flac")), body = ByteArray(120))
        }
        val closeFailure = IOException("socket closed")
        server.closeFailure = closeFailure
        val flacStream = TrafficCountingHttpDataSource(server.createDataSource(), repository)
        val idleStream = TrafficCountingHttpDataSource(server.createDataSource(), repository)

        flacStream.open(httpGet(flacUri))
        flacStream.read(ByteArray(64), 0, 64)

        assertSame(closeFailure, assertThrows(IOException::class.java) { flacStream.close() })
        assertSame(closeFailure, assertThrows(IOException::class.java) { idleStream.close() })
        assertEquals(listOf(Recorded(TrafficNetworkType.MOBILE, 64L, TrafficUsageSource.PLAYBACK)), recorded())
    }

    @Test
    fun `netease flac open failures keep the error and redact urls from diagnostics`() {
        mockStatic(Log::class.java).use { log ->
            val detailed = IOException("GET https://m701.music.126.net/x/song.flac?token=secret failed\nretry")
            val silent = IOException()
            val unrelated = IOException("https://cdn.example.com/song.mp3 refused")

            assertSame(detailed, openFailure(flacUri, detailed))
            assertSame(silent, openFailure(flacUri, silent))
            assertSame(unrelated, openFailure(plainUri, unrelated))

            log.verify(
                { Log.w(anyString(), contains("errorType=IOException, message=GET <redacted-url> failed retry"), isNull()) },
                times(1)
            )
            log.verify({ Log.w(anyString(), contains("errorType=IOException, message="), isNull()) }, times(2))
            log.verify({ Log.w(anyString(), contains("secret"), isNull()) }, never())
            log.verify({ Log.w(anyString(), contains("cdn.example.com"), isNull()) }, never())
        }
        assertEquals(emptyList<Recorded>(), recorded())
    }

    @Test
    fun `first netease flac payload is fingerprinted once per stream`() {
        mockStatic(Log::class.java).use { log ->
            readFlacStream("fLaC....".toByteArray())
            readFlacStream("OggS....".toByteArray())
            readFlacStream("fLaX....".toByteArray())
            readFlacStream("fXaC....".toByteArray())
            readFlacStream("fLXC....".toByteArray())
            readFlacStream("fLa".toByteArray())

            log.verifySignature("fLaC", count = 1)
            log.verifySignature("other", count = 4)
            log.verifySignature("short_read", count = 1)
        }
    }

    @Test
    fun `end of input replaces the closed early diagnostic`() {
        mockStatic(Log::class.java).use { log ->
            val server = ScriptedHttpServer {
                ScriptedHttpResponse(
                    code = 206,
                    headers = mapOf(
                        "Content-Type" to listOf("audio/flac; charset=binary"),
                        "Content-Range" to listOf(" ")
                    ),
                    body = ByteArray(32)
                )
            }
            val completed = TrafficCountingHttpDataSource(server.createDataSource(), repository)
            completed.open(httpGet(flacUri))
            completed.readToEnd()
            completed.read(ByteArray(8), 0, 8)
            completed.close()

            val abandoned = TrafficCountingHttpDataSource(server.createDataSource(), repository)
            abandoned.open(httpGet(mockHttpUri("https://music.126.net/stream?id=1")))
            abandoned.read(ByteArray(8), 0, 8)
            abandoned.close()

            log.verify({ Log.w(anyString(), contains("Netease FLAC stream EOF"), isNull()) }, times(1))
            log.verify({ Log.d(anyString(), contains("closed before EOF"), isNull()) }, times(1))
            log.verify(
                {
                    Log.d(
                        anyString(),
                        contains("responseCode=206, contentType=audio/flac, contentLength=null, contentRange=null"),
                        isNull()
                    )
                },
                times(2)
            )
        }
        assertEquals(listOf(32L, 8L), recorded().map { it.bytes })
    }

    private fun openFailure(uri: android.net.Uri, failure: IOException): IOException {
        val server = ScriptedHttpServer { ScriptedHttpResponse(failure = failure) }
        val source = TrafficCountingHttpDataSource(server.createDataSource(), repository)
        return assertThrows(IOException::class.java) { source.open(httpGet(uri)) }
    }

    private fun readFlacStream(body: ByteArray) {
        val server = ScriptedHttpServer { ScriptedHttpResponse(body = body) }
        val source = TrafficCountingHttpDataSource(server.createDataSource(), repository)
        source.open(httpGet(flacUri))
        source.readToEnd(chunkSize = 4)
        source.close()
    }

    private fun MockedStatic<Log>.verifySignature(signature: String, count: Int) {
        verify({ Log.d(anyString(), contains("signature=$signature"), isNull()) }, times(count))
    }

    private fun recorded(): List<Recorded> = mockingDetails(repository).invocations
        .filter { it.method.name == "recordNetworkBytes" }
        .map { Recorded(it.getArgument(0), it.getArgument(1), it.getArgument(2)) }
}
