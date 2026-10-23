package moe.ouom.neriplayer.core.player.privileged.coloros

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorOsTransportProtocolTest {
    @Test fun `frame preserves escaped xml and unicode`() {
        val message = "{\"xml\":\"<x a='中文'>\\n</x>\"}"
        val bytes = ByteArrayOutputStream()
        ColorOsTransportProtocol.writeFrame(bytes, message)
        assertEquals(message, ColorOsTransportProtocol.readFrame(ByteArrayInputStream(bytes.toByteArray())))
    }

    @Test fun `oversized frame is rejected before allocation`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(ColorOsTransportProtocol.MAX_FRAME_BYTES + 1)
        val error = assertThrows(ColorOsConfigException::class.java) { ColorOsTransportProtocol.readFrame(ByteArrayInputStream(bytes.toByteArray())) }
        assertEquals("protocol_malformed", error.detailCode)
    }

    @Test fun `fixed request rejects arbitrary uri and operation`() {
        assertThrows(ColorOsConfigException::class.java) { ColorOsTransportProtocol.validateRequest("{\"op\":\"read\",\"uri\":\"content://other\"}") }
        assertThrows(ColorOsConfigException::class.java) { ColorOsTransportProtocol.validateRequest("{\"op\":\"shell\",\"command\":\"id\"}") }
        assertThrows(ColorOsConfigException::class.java) { ColorOsTransportProtocol.validateRequest("{\"op\":17}") }
        assertEquals("read", ColorOsTransportProtocol.validateRequest(ColorOsTransportProtocol.encodeRequest("read")))
    }

    @Test fun `root command quotes apk as a single shell word`() {
        val command = ColorOsTransportProtocol.rootCommand("/data/a'b $(id)/base.apk", 10)
        assertEquals(listOf("su", "-c"), command.take(2))
        assertTrue(command[2].contains("CLASSPATH='/data/a'\"'\"'b $(id)/base.apk'"))
        assertTrue(command[2].endsWith("ColorOsRootMain 10"))
    }

    @Test fun `reload uses only fixed action filter and explicit user`() {
        assertEquals(listOf("am", "broadcast", "--user", "10", "-a", "oplus.intent.action.ROM_UPDATE_CONFIG_SUCCESS", "--esal", "ROM_UPDATE_CONFIG_LIST", "app_systemui_oplus_media_controller_config"), ColorOsTransportProtocol.reloadCommand(10))
    }

    @Test fun `reload permission denial fails even at exit zero`() {
        val denied = assertThrows(ColorOsConfigException::class.java) { ColorOsTransportProtocol.verifyReload(0, "Permission Denial: not allowed") }
        assertEquals(ColorOsConfigStatus.PERMISSION_DENIED, denied.status)
        val unconfirmed = assertThrows(ColorOsConfigException::class.java) { ColorOsTransportProtocol.verifyReload(0, "Broadcasting: Intent") }
        assertEquals(ColorOsConfigStatus.RELOAD_FAILED, unconfirmed.status)
        ColorOsTransportProtocol.verifyReload(0, "Broadcast completed: result=0")
    }

    @Test fun `service requires the exact application uid`() {
        assertThrows(SecurityException::class.java) { ColorOsTransportProtocol.authorize(2000, 10234) }
        assertThrows(SecurityException::class.java) { ColorOsTransportProtocol.authorize(0, 10234) }
        ColorOsTransportProtocol.authorize(10234, 10234)
    }

    @Test fun `destroy authorizes only app or own privileged server uid`() {
        ColorOsServiceContract.authorizeDestroy(10234, 10234, 2000)
        ColorOsServiceContract.authorizeDestroy(2000, 10234, 2000)
        assertThrows(SecurityException::class.java) { ColorOsServiceContract.authorizeDestroy(0, 10234, 2000) }
        assertThrows(SecurityException::class.java) { ColorOsServiceContract.authorizeDestroy(10235, 10234, 2000) }
    }
}
