package moe.ouom.neriplayer.core.player.privileged.coloros

import android.os.Binder
import android.os.Parcel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ColorOsShizukuChannelTest {
    @Test fun `revoking permission rejects the next read and destroys its service`() = runBlocking {
        val permission = AtomicBoolean(true)
        val service = RecordingService()
        val unbound = AtomicInteger()
        val channel = ColorOsShizukuChannel(service, { unbound.incrementAndGet() }, permission::get)
        val backend = ColorOsTransportBackend(2000, channel)
        backend.read()
        permission.set(false)
        val error = runCatching { backend.read() }.exceptionOrNull() as? ColorOsConfigException
        assertEquals(ColorOsConfigStatus.PERMISSION_DENIED, error?.status)
        assertEquals(1, service.exchanges.get())
        assertEquals(1, service.destroyed.get())
        assertEquals(1, unbound.get())
    }

    @Test fun `revoking permission rejects CAS before provider mutation and unbinds`() = runBlocking {
        val permission = AtomicBoolean(true)
        val service = RecordingService()
        val unbound = AtomicInteger()
        val channel = ColorOsShizukuChannel(service, { unbound.incrementAndGet() }, permission::get)
        val backend = ColorOsTransportBackend(2000, channel)
        backend.read()
        permission.set(false)
        val row = ColorOsRusRow("1", "<x/>")
        val error = runCatching { backend.compareAndSet(row, row.copy(version = "2")) }.exceptionOrNull() as? ColorOsConfigException
        assertEquals(ColorOsConfigStatus.PERMISSION_DENIED, error?.status)
        assertEquals(0, service.updated.get())
        assertEquals(1, service.exchanges.get())
        assertEquals(1, service.destroyed.get())
        assertEquals(1, unbound.get())
    }

    private class RecordingService : Binder() {
        val exchanges = AtomicInteger()
        val updated = AtomicInteger()
        val destroyed = AtomicInteger()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == ColorOsServiceContract.DESTROY) {
                destroyed.incrementAndGet()
                return true
            }
            data.enforceInterface(ColorOsServiceContract.DESCRIPTOR)
            val request = ColorOsTransportProtocol.decode(data.createByteArray()!!)
            exchanges.incrementAndGet()
            val response = when (ColorOsTransportProtocol.validateRequest(request)) {
                "cas" -> {
                    updated.incrementAndGet()
                    "{\"uid\":2000,\"count\":1}"
                }
                else -> "{\"uid\":2000,\"row\":null}"
            }
            reply!!.writeNoException()
            reply.writeByteArray(ColorOsTransportProtocol.checkSize(response))
            return true
        }
    }
}
