package moe.ouom.neriplayer.core.player.privileged.coloros

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorOsRootChannelTest {
    @Test fun `root channel exchanges structured frames and destroys only its owned process`() {
        val reply = ByteArrayOutputStream().also { ColorOsTransportProtocol.writeFrame(it, "{\"uid\":0,\"row\":null}") }
        val process = FakeProcess(reply.toByteArray())
        val channel = ColorOsRootChannel(process)
        assertEquals("{\"uid\":0,\"row\":null}", channel.exchange("{\"op\":\"read\"}"))
        assertEquals("{\"op\":\"read\"}", ColorOsTransportProtocol.readFrame(ByteArrayInputStream(process.written.toByteArray())))
        channel.close()
        channel.close()
        assertEquals(1, process.destroyed)
        assertTrue(process.inputClosed)
        assertTrue(process.outputClosed)
    }

    private class FakeProcess(reply: ByteArray) : Process() {
        val written = ByteArrayOutputStream()
        var inputClosed = false
        var outputClosed = false
        var destroyed = 0
        private val input = object : ByteArrayInputStream(reply) {
            override fun close() { inputClosed = true; super.close() }
        }
        private val output = object : OutputStream() {
            override fun write(value: Int) { written.write(value) }
            override fun close() { outputClosed = true }
        }
        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() { destroyed++ }
        override fun destroyForcibly(): Process { destroyed++; return this }
    }
}
