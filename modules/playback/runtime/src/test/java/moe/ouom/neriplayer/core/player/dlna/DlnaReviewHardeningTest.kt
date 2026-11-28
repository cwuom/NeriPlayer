package moe.ouom.neriplayer.core.player.dlna

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.core.player.dlna.protocol.FlacHeaderInspector
import moe.ouom.neriplayer.core.player.dlna.protocol.Mp4MetadataWriter
import moe.ouom.neriplayer.core.player.dlna.protocol.UpnpControlClient
import moe.ouom.neriplayer.core.player.dlna.protocol.UpnpXmlParser
import moe.ouom.neriplayer.core.player.dlna.session.DlnaPrequeueGate
import moe.ouom.neriplayer.core.player.dlna.session.DlnaRendererReachability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

class DlnaReviewHardeningTest {

    @Test
    fun prequeueGate_doesNotRestartInFlightOrFinishedTarget() {
        val gate = DlnaPrequeueGate()
        val target = DlnaPrequeueGate.Target(connectionGen = 1L, trackGen = 5L, queueRevision = 2L)

        assertTrue(gate.tryBegin(target, jobActive = false))
        // 轮询每秒都会再次请求，同一目标的任务还在跑时不能被取消重来
        assertFalse(gate.tryBegin(target, jobActive = true))

        gate.markFinished(target)
        // 已经跑完（例如没有下一首），不能每秒重复解析 URL
        assertFalse(gate.tryBegin(target, jobActive = false))

        val nextTrack = target.copy(trackGen = 6L)
        assertTrue(gate.tryBegin(nextTrack, jobActive = false))
        val queueChanged = nextTrack.copy(queueRevision = 3L)
        assertTrue(gate.tryBegin(queueChanged, jobActive = true))
    }

    @Test
    fun prequeueGate_allowsRetryAfterCancelledAttempt() {
        val gate = DlnaPrequeueGate()
        val target = DlnaPrequeueGate.Target(1L, 5L, 2L)
        assertTrue(gate.tryBegin(target, jobActive = false))
        // 用户暂停导致任务中止时不会 markFinished，恢复播放后应能重新预取
        assertTrue(gate.tryBegin(target, jobActive = false))
    }

    @Test
    fun rendererReachability_requiresBothFailureCountAndDuration() {
        val reachability = DlnaRendererReachability(minConsecutiveFailures = 3, minUnreachableMs = 30_000L)

        assertFalse(reachability.record(reachable = false, nowMs = 0L))
        assertFalse(reachability.record(reachable = false, nowMs = 1_000L))
        // 次数够了但时长不够：Wi-Fi 短暂抖动不应断开投送
        assertFalse(reachability.record(reachable = false, nowMs = 2_000L))
        assertTrue(reachability.record(reachable = false, nowMs = 30_000L))

        // 一次成功即清零
        assertFalse(reachability.record(reachable = true, nowMs = 31_000L))
        assertFalse(reachability.record(reachable = false, nowMs = 60_000L))
        assertFalse(reachability.record(reachable = false, nowMs = 61_000L))
        assertTrue(reachability.record(reachable = false, nowMs = 200_000L))
    }

    @Test(timeout = 5_000L)
    fun inspector_largesizeOverflowDoesNotLoopForever() {
        val ftyp = mp4Box("ftyp", "M4A ".ascii() + ByteArray(4) + "M4A mp42".ascii())
        assertEquals(24, ftyp.size)
        // pos(24) + largesize 溢出到 Long.MIN_VALUE，toInt() 后回到 0，旧实现会在 0 和 24 之间无限循环
        val bogus = largesizeBox("free", Long.MAX_VALUE - ftyp.size + 1L)
        FlacHeaderInspector.inspectBytes(ftyp + bogus)
    }

    @Test(timeout = 5_000L)
    fun mp4Writer_largesizeOverflowInsideStblIsRejected() {
        val dir = Files.createTempDirectory("dlna-mp4-overflow").toFile()
        try {
            // 用 co64：旧实现每圈都给偏移加 delta，要累加到 Long 溢出才退出，等同于死循环
            val co64 = mp4Box("co64", ByteArray(4) + be32(1L) + be64(64L))
            // moov payload 内绝对位置：trak(0) mdia(8) minf(16) stbl(24) co64(32)，bogus 紧随其后，回绕落点正好是 co64
            val co64Pos = 32L
            val bogusPos = co64Pos + co64.size
            val bogus = largesizeBox("free", Long.MAX_VALUE - (bogusPos - co64Pos) + 1L)
            val moov = mp4Box("moov", mp4Box("trak", mp4Box("mdia", mp4Box("minf", mp4Box("stbl", co64 + bogus)))))
            val ftyp = mp4Box("ftyp", "M4A ".ascii() + ByteArray(4) + "M4A mp42".ascii())
            val mdat = mp4Box("mdat", ByteArray(32))
            val source = File(dir, "src.m4a").apply { writeBytes(ftyp + moov + mdat) }
            val target = File(dir, "out.m4a")

            assertFalse(Mp4MetadataWriter.writeTags(source, target, "T", "A", "B"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deviceDescription_dropsEndpointsOnForeignHosts() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <URLBase>http://10.0.0.99:1400/</URLBase>
              <device>
                <UDN>uuid:foreign-endpoints</UDN>
                <friendlyName>Spoofed Renderer</friendlyName>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>http://127.0.0.1:8080/admin</controlURL>
                    <eventSubURL>http://203.0.113.5/evt</eventSubURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/RenderingControl/ctrl</controlURL>
                    <eventSubURL>http://192.168.1.50:49152/RenderingControl/evt</eventSubURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        val parsed = UpnpXmlParser.parseDeviceDescription(xml, "http://192.168.1.50:49152/desc.xml")!!
        val avTransport = parsed.services.first { it.serviceType.contains("AVTransport") }
        val rendering = parsed.services.first { it.serviceType.contains("RenderingControl") }

        assertEquals("", avTransport.controlUrl)
        assertEquals("", avTransport.eventSubUrl)
        // URLBase 指向别的主机时退回 LOCATION 解析相对路径
        assertEquals("http://192.168.1.50:49152/RenderingControl/ctrl", rendering.controlUrl)
        assertEquals("http://192.168.1.50:49152/RenderingControl/evt", rendering.eventSubUrl)
    }

    @Test
    fun soapCall_rejectsOversizedResponsesAndDoesNotFollowRedirects() {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            val client = UpnpControlClient()
            val service = "urn:schemas-upnp-org:service:RenderingControl:1"

            server.enqueue(MockResponse.Builder().code(200).body(soapVolumeResponse(service)).build())
            val ok = client.callSoap(server.url("/ctl").toString(), service, "GetVolume", "")
            assertEquals("42", ok.getOrThrow()["CurrentVolume"])

            val padding = " ".repeat(300 * 1024)
            server.enqueue(MockResponse.Builder().code(200).body(soapVolumeResponse(service, padding)).build())
            val oversized = client.callSoap(server.url("/ctl").toString(), service, "GetVolume", "")
            assertTrue(oversized.isFailure)
            assertTrue(oversized.exceptionOrNull()?.message.orEmpty().contains("exceeds"))

            server.enqueue(
                MockResponse.Builder().code(302).setHeader("Location", server.url("/elsewhere").toString()).build()
            )
            server.enqueue(MockResponse.Builder().code(200).body(soapVolumeResponse(service)).build())
            val redirected = client.callSoap(server.url("/ctl").toString(), service, "GetVolume", "")
            assertTrue(redirected.isFailure)
            assertEquals(3, server.requestCount)
        }
    }

    private fun soapVolumeResponse(service: String, padding: String = ""): String =
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<u:GetVolumeResponse xmlns:u=\"$service\"><CurrentVolume>42</CurrentVolume></u:GetVolumeResponse>" +
            "$padding</s:Body></s:Envelope>"

    private fun String.ascii(): ByteArray = toByteArray(Charsets.ISO_8859_1)

    private fun be32(value: Long): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    private fun be64(value: Long): ByteArray = be32(value ushr 32) + be32(value and 0xFFFFFFFFL)

    private fun mp4Box(type: String, payload: ByteArray): ByteArray =
        be32((8 + payload.size).toLong()) + type.ascii() + payload

    private fun largesizeBox(type: String, largesize: Long): ByteArray =
        be32(1L) + type.ascii() + be64(largesize)
}
