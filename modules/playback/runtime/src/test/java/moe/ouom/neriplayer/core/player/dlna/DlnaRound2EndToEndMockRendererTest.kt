package moe.ouom.neriplayer.core.player.dlna

import moe.ouom.neriplayer.core.player.dlna.protocol.DidlLiteBuilder
import moe.ouom.neriplayer.core.player.dlna.protocol.DlnaProtocolInfo
import moe.ouom.neriplayer.core.player.dlna.protocol.SsdpDiscoveryClient
import moe.ouom.neriplayer.core.player.dlna.protocol.UpnpControlClient
import moe.ouom.neriplayer.core.player.dlna.protocol.UpnpXmlParser
import moe.ouom.neriplayer.core.player.dlna.protocol.WavId3TagBuilder
import moe.ouom.neriplayer.core.player.dlna.server.DlnaMediaServer
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaPlaybackSnapshot
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaStreamMode
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Round 2 Simulation Tests:
 * End-to-end integration against a real local Mock Shanling EM7 UPnP Renderer:
 * - Device XML probing, ConnectionManager GetProtocolInfo
 * - Original FLAC direct delivery with SHA-256 byte-identity check by the renderer
 * - Lossless PCM -> WAV + ID3v2.3 (UTF-16LE BOM) hybrid delivery with HEAD, Range probes across
 *   RIFF/ID3/PCM seams, and byte-exact PCM verification
 * - UPnP AVTransport Play/Pause/Seek/Stop & RenderingControl safe GetVolume/SetVolume
 * - Generation snapshot isolation & 410 Gone on retired track URLs
 */
class DlnaRound2EndToEndMockRendererTest {

    private lateinit var mediaServer: DlnaMediaServer
    private lateinit var mockEm7: MockShanlingEm7Renderer
    private lateinit var tempDir: File
    private val httpClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    private val ssdpClient = SsdpDiscoveryClient()
    private val controlClient = UpnpControlClient()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("dlna_round2_").toFile()
        mediaServer = DlnaMediaServer()
        mediaServer.start()
        mockEm7 = MockShanlingEm7Renderer(httpClient)
        mockEm7.start()
    }

    @After
    fun tearDown() {
        mockEm7.stop()
        mediaServer.stop()
        tempDir.deleteRecursively()
    }

    @Test
    fun endToEnd_originalFlacDirectDelivery_verifiesSha256ByteIdentityAndArtwork() {
        val device = ssdpClient.probeManualDevice("http://127.0.0.1:${mockEm7.port}/desc.xml").getOrThrow()
        assertEquals("Shanling EM7", device.friendlyName)
        assertTrue(device.isLikelyShanlingEm7)

        val sinkProto = controlClient.getProtocolInfo(device).getOrThrow()
        assertTrue(DlnaProtocolInfo.supportsFlac(sinkProto, 96_000))

        // Safe initial volume check: never auto-maxed!
        assertEquals(35, controlClient.getVolume(device).getOrThrow())

        // Create synthetic FLAC file
        val flacBytes = ByteArray(128 * 1024) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        flacBytes[0] = 'f'.code.toByte(); flacBytes[1] = 'L'.code.toByte()
        flacBytes[2] = 'a'.code.toByte(); flacBytes[3] = 'C'.code.toByte()
        val flacFile = File(tempDir, "track1.flac").apply { writeBytes(flacBytes) }
        val expectedSha = sha256(flacBytes)

        val coverBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()
        )
        val protocolInfo = DlnaProtocolInfo.protocolInfoFor(DlnaStreamMode.ORIGINAL_FLAC, sinkProto)
        val streamPath = mediaServer.registerStream(
            "flac",
            DlnaMediaServer.StreamContent.FileContent(
                file = flacFile,
                contentType = "audio/x-flac",
                contentFeatures = DlnaProtocolInfo.contentFeaturesFromProtocolInfo(protocolInfo)
            )
        )
        val coverPath = mediaServer.registerArtwork(coverBytes)!!
        val streamUrl = mediaServer.buildUrl("127.0.0.1", streamPath)
        val coverUrl = mediaServer.buildUrl("127.0.0.1", coverPath)

        val snapshot = DlnaPlaybackSnapshot(
            generationId = 1L,
            songKey = "local|track1",
            title = "无损测试曲目",
            artist = "山灵测试",
            album = "EM7 参考专辑",
            durationMs = 180_000L,
            streamPath = streamPath,
            coverPath = coverPath,
            contentType = "audio/x-flac",
            protocolInfo = protocolInfo,
            totalBytes = flacFile.length(),
            sampleRateHz = 96_000,
            bitDepth = 24,
            channelCount = 2,
            streamMode = DlnaStreamMode.ORIGINAL_FLAC
        )
        val didl = DidlLiteBuilder.buildDidlLite(snapshot, streamUrl, coverUrl)

        controlClient.setAvTransportUri(device, streamUrl, didl).getOrThrow()
        controlClient.play(device).getOrThrow()

        // Mock EM7 pulls full stream & cover!
        val pulledAudio = mockEm7.pullFullCurrentStream()
        val pulledCover = mockEm7.pullCurrentCover()
        assertEquals(expectedSha, sha256(pulledAudio))
        assertArrayEquals(coverBytes, pulledCover)

        // Transport controls + safe volume update
        assertEquals(DlnaTransportState.PLAYING, controlClient.getTransportState(device).getOrThrow())
        controlClient.seekRelTime(device, 45_000L).getOrThrow()
        assertEquals(45_000L, controlClient.getPositionInfo(device).getOrThrow().positionMs)
        controlClient.pause(device).getOrThrow()
        assertEquals(DlnaTransportState.PAUSED_PLAYBACK, controlClient.getTransportState(device).getOrThrow())
        controlClient.setVolume(device, 42).getOrThrow()
        assertEquals(42, controlClient.getVolume(device).getOrThrow())
        controlClient.stop(device).getOrThrow()
        assertEquals(DlnaTransportState.STOPPED, controlClient.getTransportState(device).getOrThrow())
    }

    @Test
    fun endToEnd_wavId3HybridDelivery_handlesHeadRangesAndTrackSwitchRetirement() {
        val device = ssdpClient.probeManualDevice("http://127.0.0.1:${mockEm7.port}/desc.xml").getOrThrow()

        val pcmPayload = ByteArray(64 * 1024) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }
        val pcmFile = File(tempDir, "track2.pcm").apply { writeBytes(pcmPayload) }
        val id3 = WavId3TagBuilder.buildId3Chunk("EM7 标题", "EM7 歌手", "EM7 专辑")
        val layout = WavId3TagBuilder.buildWavLayout(
            sampleRateHz = 48_000,
            bitsPerSample = 24,
            channels = 2,
            pcmDataBytes = pcmPayload.size.toLong(),
            id3Chunk = id3
        )

        val proto = DlnaProtocolInfo.protocolInfoFor(DlnaStreamMode.LOSSLESS_WAV_ID3, device.sinkProtocolInfo)
        val pathGen1 = mediaServer.registerStream(
            "wav",
            DlnaMediaServer.StreamContent.HybridWavContent(
                headerPrefix = layout.headerBytes,
                pcmFile = pcmFile,
                pcmDataOffsetInFile = 0L,
                pcmDataLength = pcmPayload.size.toLong(),
                contentType = "audio/wav",
                contentFeatures = DlnaProtocolInfo.contentFeaturesFromProtocolInfo(proto)
            )
        )
        val urlGen1 = mediaServer.buildUrl("127.0.0.1", pathGen1)

        // 1. HEAD consistency
        httpClient.newCall(Request.Builder().url(urlGen1).head().build()).execute().use { head ->
            assertEquals(200, head.code)
            assertEquals(layout.totalBytes.toString(), head.header("Content-Length"))
            assertEquals("audio/wav", head.header("Content-Type"))
            assertEquals("bytes", head.header("Accept-Ranges"))
        }

        // 2. Range probe spanning ID3 -> PCM seam (bytes (pcmStart - 4) .. (pcmStart + 7))
        val seamStart = layout.pcmStartOffset - 4
        val seamEnd = layout.pcmStartOffset + 7
        httpClient.newCall(
            Request.Builder().url(urlGen1).header("Range", "bytes=$seamStart-$seamEnd").get().build()
        ).execute().use { seam ->
            assertEquals(206, seam.code)
            assertEquals("bytes $seamStart-$seamEnd/${layout.totalBytes}", seam.header("Content-Range"))
            val body = seam.body.bytes()
            val expectedSeam = layout.headerBytes.copyOfRange(seamStart.toInt(), layout.headerBytes.size) +
                pcmPayload.copyOfRange(0, 8)
            assertArrayEquals(expectedSeam, body)
        }

        // 3. Range starting exactly at pcmStartOffset -> must equal full untouched PCM payload!
        httpClient.newCall(
            Request.Builder().url(urlGen1).header("Range", "bytes=${layout.pcmStartOffset}-").get().build()
        ).execute().use { pcmResp ->
            assertEquals(206, pcmResp.code)
            assertArrayEquals(pcmPayload, pcmResp.body.bytes())
        }

        // 4. Switch to Gen 2 -> Gen 1 URL retires and returns 410 Gone
        mediaServer.unregisterStream(pathGen1)
        httpClient.newCall(Request.Builder().url(urlGen1).get().build()).execute().use { stale ->
            assertEquals(410, stale.code)
            assertEquals("5", stale.header("Retry-After"))
        }
    }

    @Test
    fun endToEnd_adaptiveM4aDirectDelivery_streamsExactBytesAndMetadata() {
        val device = ssdpClient.probeManualDevice("http://127.0.0.1:${mockEm7.port}/desc.xml").getOrThrow()
        val m4aBytes = byteArrayOf(
            0, 0, 0, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), '5'.code.toByte()
        ) + ByteArray(32 * 1024) { idx -> ((idx * 19 + 11) and 0xFF).toByte() }
        val m4aFile = File(tempDir, "bili_track.m4a").apply { writeBytes(m4aBytes) }
        val expectedSha = sha256(m4aBytes)

        val proto = DlnaProtocolInfo.protocolInfoFor(
            mode = DlnaStreamMode.ADAPTIVE_DIRECT,
            sinkProtocolInfo = device.sinkProtocolInfo,
            mimeOverride = "audio/mp4"
        )
        assertTrue(proto.startsWith("http-get:*:audio/mp4:"))

        val streamPath = mediaServer.registerStream(
            "m4a",
            DlnaMediaServer.StreamContent.FileContent(
                file = m4aFile,
                contentType = "audio/mp4",
                contentFeatures = DlnaProtocolInfo.contentFeaturesFromProtocolInfo(proto)
            )
        )
        val streamUrl = mediaServer.buildUrl("127.0.0.1", streamPath)
        val snapshot = DlnaPlaybackSnapshot(
            generationId = 3L,
            songKey = "bilibili|BV1xx",
            title = "B站测试音频",
            artist = "UP主",
            album = "Bilibili|BV1xx",
            durationMs = 150_000L,
            streamPath = streamPath,
            coverPath = null,
            contentType = "audio/mp4",
            protocolInfo = proto,
            totalBytes = m4aFile.length(),
            sampleRateHz = 48_000,
            bitDepth = 0,
            channelCount = 2,
            streamMode = DlnaStreamMode.ADAPTIVE_DIRECT
        )
        val didl = DidlLiteBuilder.buildDidlLite(snapshot, streamUrl, null)
        controlClient.setAvTransportUri(device, streamUrl, didl).getOrThrow()
        controlClient.play(device).getOrThrow()

        val pulled = mockEm7.pullFullCurrentStream()
        assertEquals(expectedSha, sha256(pulled))
    }

    @Test
    fun endToEnd_setNextAvTransportUri_prequeuesAndTransitionsCleanly() {
        val firstFlac = byteArrayOf(0x66, 0x4C, 0x61, 0x43) + ByteArray(8192) { (it and 0x7F).toByte() }
        val secondFlac = byteArrayOf(0x66, 0x4C, 0x61, 0x43) + ByteArray(8192) { ((it * 3 + 7) and 0x7F).toByte() }
        val f1 = File(tempDir, "track1.flac").apply { writeBytes(firstFlac) }
        val f2 = File(tempDir, "track2.flac").apply { writeBytes(secondFlac) }

        val device = ssdpClient.probeManualDevice("http://127.0.0.1:${mockEm7.port}/desc.xml").getOrThrow()
        val proto = DlnaProtocolInfo.protocolInfoFor(DlnaStreamMode.ORIGINAL_FLAC, device.sinkProtocolInfo)
        val features = DlnaProtocolInfo.contentFeaturesFromProtocolInfo(proto)
        val path1 = mediaServer.registerStream(
            "flac",
            DlnaMediaServer.StreamContent.FileContent(f1, "audio/x-flac", features)
        )
        val path2 = mediaServer.registerStream(
            "flac",
            DlnaMediaServer.StreamContent.FileContent(f2, "audio/x-flac", features)
        )
        val url1 = mediaServer.buildUrl("127.0.0.1", path1)
        val url2 = mediaServer.buildUrl("127.0.0.1", path2)

        val didl1 = DidlLiteBuilder.buildDidlLite(
            DlnaPlaybackSnapshot(
                generationId = 1L,
                songKey = "s1",
                title = "Track One",
                artist = "Artist One",
                album = "Album One",
                durationMs = 180_000L,
                streamPath = path1,
                coverPath = null,
                contentType = "audio/x-flac",
                protocolInfo = proto,
                totalBytes = firstFlac.size.toLong(),
                sampleRateHz = 96_000,
                bitDepth = 24,
                channelCount = 2,
                streamMode = DlnaStreamMode.ORIGINAL_FLAC
            ),
            url1,
            null
        )
        val didl2 = DidlLiteBuilder.buildDidlLite(
            DlnaPlaybackSnapshot(
                generationId = 1L,
                songKey = "s2",
                title = "Track Two",
                artist = "Artist Two",
                album = "Album Two",
                durationMs = 190_000L,
                streamPath = path2,
                coverPath = null,
                contentType = "audio/x-flac",
                protocolInfo = proto,
                totalBytes = secondFlac.size.toLong(),
                sampleRateHz = 96_000,
                bitDepth = 24,
                channelCount = 2,
                streamMode = DlnaStreamMode.ORIGINAL_FLAC
            ),
            url2,
            null
        )

        assertTrue(controlClient.setAvTransportUri(device, url1, didl1).isSuccess)
        assertTrue(controlClient.play(device).isSuccess)
        assertTrue(controlClient.setNextAvTransportUri(device, url2, didl2).isSuccess)
        assertEquals(url2, mockEm7.nextUri)
        assertTrue(mockEm7.nextMeta.contains("Track Two"))

        // Simulate renderer auto-transitioning to NextURI at track1 completion
        mockEm7.currentUri = mockEm7.nextUri
        mockEm7.currentMeta = mockEm7.nextMeta
        val posInfo = controlClient.getPositionInfo(device).getOrThrow()
        assertEquals(url2, posInfo.trackUri)
        val pulled2 = mockEm7.pullFullCurrentStream()
        assertArrayEquals(secondFlac, pulled2)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Minimal UPnP MediaRenderer simulating Shanling EM7 HTTP/SOAP/Stream pull behavior.
     */
    private class MockShanlingEm7Renderer(private val client: OkHttpClient) {
        private var serverSocket: ServerSocket? = null
        private val running = AtomicBoolean(false)
        private val pool = Executors.newCachedThreadPool()

        var port: Int = 0
            private set

        @Volatile
        var currentUri: String = ""
        @Volatile
        var currentMeta: String = ""
        @Volatile
        var nextUri: String = ""
        @Volatile
        var nextMeta: String = ""
        @Volatile
        var transportState: String = "NO_MEDIA_PRESENT"
        @Volatile
        var positionSec: Long = 0L
        @Volatile
        var volume: Int = 35

        fun start() {
            val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            running.set(true)
            pool.execute {
                while (running.get()) {
                    val sock = try { ss.accept() } catch (_: Exception) { break }
                    pool.execute { handle(sock) }
                }
            }
        }

        fun stop() {
            running.set(false)
            runCatching { serverSocket?.close() }
            pool.shutdownNow()
        }

        fun pullFullCurrentStream(): ByteArray {
            return client.newCall(Request.Builder().url(currentUri).get().build()).execute().use {
                assertEquals(200, it.code)
                it.body.bytes()
            }
        }

        fun pullCurrentCover(): ByteArray {
            val start = currentMeta.indexOf("<upnp:albumArtURI>") + "<upnp:albumArtURI>".length
            val end = currentMeta.indexOf("</upnp:albumArtURI>")
            val artUrl = currentMeta.substring(start, end)
                .replace("&amp;", "&")
            return client.newCall(Request.Builder().url(artUrl).get().build()).execute().use {
                assertEquals(200, it.code)
                it.body.bytes()
            }
        }

        private fun handle(sock: Socket) {
            sock.use { s ->
                val input = java.io.BufferedInputStream(s.getInputStream())
                val headerBytes = java.io.ByteArrayOutputStream()
                var matched = 0
                while (true) {
                    val b = input.read()
                    if (b < 0) return
                    headerBytes.write(b)
                    matched = when {
                        matched == 0 && b == '\r'.code -> 1
                        matched == 1 && b == '\n'.code -> 2
                        matched == 2 && b == '\r'.code -> 3
                        matched == 3 && b == '\n'.code -> 4
                        b == '\r'.code -> 1
                        else -> 0
                    }
                    if (matched == 4) break
                }
                val headerLines = headerBytes.toString(Charsets.UTF_8.name()).split("\r\n")
                val reqLine = headerLines.firstOrNull() ?: return
                var contentLength = 0
                var soapAction = ""
                for (line in headerLines.drop(1)) {
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    } else if (line.startsWith("SOAPACTION:", ignoreCase = true)) {
                        soapAction = line.substringAfter(':').trim().trim('"')
                    }
                }
                val bodyBytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bodyBytes, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                val body = String(bodyBytes, 0, read, Charsets.UTF_8)
                val target = reqLine.split(' ').getOrNull(1).orEmpty()

                val respBody = when {
                    target == "/desc.xml" -> """
                        <?xml version="1.0"?>
                        <root xmlns="urn:schemas-upnp-org:device-1-0">
                          <device>
                            <friendlyName>Shanling EM7</friendlyName>
                            <manufacturer>Shanling</manufacturer>
                            <modelName>EM7</modelName>
                            <UDN>uuid:shanling-em7-mock</UDN>
                            <serviceList>
                              <service>
                                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                                <controlURL>/upnp/control/AVTransport</controlURL>
                              </service>
                              <service>
                                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                                <controlURL>/upnp/control/RenderingControl</controlURL>
                              </service>
                              <service>
                                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                                <controlURL>/upnp/control/ConnectionManager</controlURL>
                              </service>
                            </serviceList>
                          </device>
                        </root>
                    """.trimIndent()
                    soapAction.endsWith("#GetProtocolInfo") -> wrapSoap(
                        "ConnectionManager", "GetProtocolInfo",
                        "<Source></Source><Sink>http-get:*:audio/x-flac:*,http-get:*:audio/wav:*</Sink>"
                    )
                    soapAction.endsWith("#SetAVTransportURI") -> {
                        currentUri = extractTag(body, "CurrentURI")
                        currentMeta = unescapeXml(extractTag(body, "CurrentURIMetaData"))
                        transportState = "STOPPED"
                        wrapSoap("AVTransport", "SetAVTransportURI", "")
                    }
                    soapAction.endsWith("#SetNextAVTransportURI") -> {
                        nextUri = extractTag(body, "NextURI")
                        nextMeta = unescapeXml(extractTag(body, "NextURIMetaData"))
                        wrapSoap("AVTransport", "SetNextAVTransportURI", "")
                    }
                    soapAction.endsWith("#Play") -> {
                        transportState = "PLAYING"
                        wrapSoap("AVTransport", "Play", "")
                    }
                    soapAction.endsWith("#Pause") -> {
                        transportState = "PAUSED_PLAYBACK"
                        wrapSoap("AVTransport", "Pause", "")
                    }
                    soapAction.endsWith("#Stop") -> {
                        transportState = "STOPPED"
                        wrapSoap("AVTransport", "Stop", "")
                    }
                    soapAction.endsWith("#Seek") -> {
                        val unit = extractTag(body, "Unit")
                        if (unit != "REL_TIME") {
                            throw IllegalArgumentException("Missing or invalid Seek Unit: $unit")
                        }
                        val t = extractTag(body, "Target")
                        positionSec = (UpnpXmlParser.parseUpnpTimeToMs(t) ?: 0L) / 1000L
                        wrapSoap("AVTransport", "Seek", "")
                    }
                    soapAction.endsWith("#GetTransportInfo") -> wrapSoap(
                        "AVTransport", "GetTransportInfo",
                        "<CurrentTransportState>$transportState</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"
                    )
                    soapAction.endsWith("#GetPositionInfo") -> wrapSoap(
                        "AVTransport", "GetPositionInfo",
                        "<RelTime>${UpnpXmlParser.formatUpnpTime(positionSec * 1000L)}</RelTime><TrackDuration>00:03:00</TrackDuration><TrackURI>${DidlLiteBuilder.escapeXml(currentUri)}</TrackURI>"
                    )
                    soapAction.endsWith("#GetVolume") -> wrapSoap(
                        "RenderingControl", "GetVolume",
                        "<CurrentVolume>$volume</CurrentVolume>"
                    )
                    soapAction.endsWith("#SetVolume") -> {
                        volume = extractTag(body, "DesiredVolume").toIntOrNull() ?: volume
                        wrapSoap("RenderingControl", "SetVolume", "")
                    }
                    else -> ""
                }
                val bytes = respBody.toByteArray(Charsets.UTF_8)
                val hdr = "HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                s.getOutputStream().write(hdr.toByteArray(Charsets.US_ASCII))
                s.getOutputStream().write(bytes)
                s.getOutputStream().flush()
            }
        }

        private fun wrapSoap(service: String, action: String, inner: String): String =
            """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">$inner</u:${action}Response></s:Body></s:Envelope>"""

        private fun extractTag(xml: String, tag: String): String {
            val open = "<$tag>"
            val close = "</$tag>"
            val i = xml.indexOf(open)
            val j = xml.indexOf(close)
            return if (i >= 0 && j > i) xml.substring(i + open.length, j) else ""
        }

        private fun unescapeXml(text: String): String =
            text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&")
    }
}
