package moe.ouom.neriplayer.core.player.usb.session

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.core.player.usb.sink.ResolvedUsbOutputFormat
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveIoGate
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class UsbExclusiveSessionResourcesTest {
    private val preferred = format(96_000)
    private val fallback = format(48_000)

    @Test
    fun `native candidate retry keeps the selected physical connection`() {
        val native = RecordingNativePort().apply {
            openHandles.addAll(listOf(0L, 7L))
            openError = "sample_rate_negotiation_failed"
        }
        val gate = UsbExclusiveIoGate().apply { open() }
        val resources = resources(gate, native)
        val opened = openedDevice()

        val result = resources.openPlayerPcm(
            context = mock(Context::class.java),
            openedDevice = opened,
            outputCandidates = listOf(preferred, fallback),
            inputSampleRate = 48_000,
            inputChannelCount = 2,
            inputEncoding = 2,
            appInForeground = true,
            allowMixedPlayback = true
        )

        assertEquals(7L, result.handle)
        assertEquals(fallback, result.outputFormat)
        assertEquals(listOf("open:96000", "open:48000"), native.events.filter { it.startsWith("open:") })
        assertTrue(native.events.contains("prepare:7"))
        assertTrue(gate.isOpen())
    }

    @Test
    fun `tone open keeps the connection until the controller commits it`() {
        val native = RecordingNativePort().apply { openHandles.add(17L) }
        val gate = UsbExclusiveIoGate().apply { open() }
        val resources = resources(gate, native)
        val opened = openedDevice()

        val result = resources.openTone(mock(Context::class.java), opened)

        assertEquals(17L, result.handle)
        assertTrue(gate.isOpen())
        resources.commitConnection(opened.connection)
        assertEquals(opened.connection, resources.takeActiveConnection())
    }

    @Test
    fun `tone start failure stops the handle before closing its connection`() {
        val native = RecordingNativePort().apply {
            openHandles.add(19L)
            startToneResult = false
            report = "tone_start_failed"
        }
        val closed = CountDownLatch(1)
        val gate = UsbExclusiveIoGate().apply { open() }
        val resources = resources(gate, native, onClose = { _, _ -> closed.countDown() })
        val opened = openedDevice()

        val result = resources.openTone(mock(Context::class.java), opened)

        assertEquals("tone_start_failed", result.error)
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertTrue(native.events.indexOf("stop:19") < native.events.indexOf("close:19"))
        verify(opened.connection).close()
    }

    @Test
    fun `pending native close owns its connection through completion`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val native = RecordingNativePort().apply {
            closeEntered = entered
            closeRelease = release
        }
        val resources = resources(UsbExclusiveIoGate(), native, onClose = { _, _ -> completed.countDown() })
        val connection = mock(UsbDeviceConnection::class.java)

        assertFalse(resources.hasNativeCloseInFlight)
        resources.scheduleClose(UsbExclusiveSessionResources.CloseRequest(7L, connection, "player_pcm", "stop"))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(resources.hasNativeCloseInFlight)
        release.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertFalse(resources.hasNativeCloseInFlight)
        verify(connection).close()
    }

    @Test
    fun `non retryable open failure closes only the opening connection`() {
        val native = RecordingNativePort().apply {
            openHandles.add(0L)
            openError = "claim_interface_failed"
        }
        val gate = UsbExclusiveIoGate().apply { open() }
        val soundGuard = RecordingSoundGuard()
        val resources = resources(gate, native, soundGuard)
        val opened = openedDevice()

        val result = resources.openPlayerPcm(
            context = mock(Context::class.java),
            openedDevice = opened,
            outputCandidates = listOf(preferred, fallback),
            inputSampleRate = 48_000,
            inputChannelCount = 2,
            inputEncoding = 2,
            appInForeground = true,
            allowMixedPlayback = false
        )

        assertEquals(0L, result.handle)
        assertEquals("claim_interface_failed", result.error)
        assertTrue(result.shouldFuseOpen)
        assertEquals(listOf("open:96000"), native.events.filter { it.startsWith("open:") })
        verify(opened.connection).close()
        assertFalse(gate.isOpen())
        assertEquals(listOf("player_pcm_open_start", "player_pcm_open_failed"), soundGuard.events)
    }

    @Test
    fun `prepare failure stops before native close and drains the same connection`() {
        val native = RecordingNativePort().apply {
            openHandles.add(11L)
            prepareResult = false
            report = "prepare_failed"
        }
        val gate = UsbExclusiveIoGate().apply { open() }
        val closed = CountDownLatch(1)
        val resources = resources(gate, native, onClose = { _, _ -> closed.countDown() })
        val opened = openedDevice()

        val result = resources.openPlayerPcm(
            context = mock(Context::class.java),
            openedDevice = opened,
            outputCandidates = listOf(preferred),
            inputSampleRate = 48_000,
            inputChannelCount = 2,
            inputEncoding = 2,
            appInForeground = true,
            allowMixedPlayback = true
        )

        assertEquals("prepare_failed", result.error)
        assertTrue(result.shouldFuseOpen)
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertTrue(native.events.indexOf("stop:11") < native.events.indexOf("close:11"))
        verify(opened.connection).close()
        assertFalse(gate.isOpen())
    }

    @Test
    fun `opened device identity follows the owned connection`() {
        val device = mock(UsbDevice::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        `when`(device.deviceId).thenReturn(41)
        `when`(device.deviceName).thenReturn("usb-41")
        `when`(device.productName).thenReturn("DAC")
        `when`(device.vendorId).thenReturn(7)
        `when`(device.productId).thenReturn(8)
        val gate = UsbExclusiveIoGate()
        val context = mock(Context::class.java)
        var selectedKey = "usb:7:8:dac"
        val resources = UsbExclusiveSessionResources(
            ioGate = gate,
            onCloseComplete = { _, _ -> },
            selectedDeviceKeyPort = UsbExclusiveSelectedDeviceKeyPort { selectedKey },
            native = RecordingNativePort(),
            soundGuard = RecordingSoundGuard(),
            openDeviceConnection = { _, _ -> device to connection }
        )

        assertTrue(resources.matchesPreferredDevice(context, device))
        selectedKey = "usb:9:9:other"
        assertFalse(resources.matchesPreferredDevice(context, device))
        selectedKey = ""
        assertEquals("auto", resources.preferredDeviceKey(context))
        assertEquals(connection, resources.openDevice(context, "selected")?.connection)
        assertTrue(gate.isOpen())
        assertEquals("usb:7:8:dac", resources.selectedDeviceKey)
        assertEquals("deviceId=41 deviceName=usb-41", resources.detachedDeviceDescription(device))
        assertEquals("deviceId=null deviceName=null", resources.detachedDeviceDescription(null))
        assertTrue(resources.matchesActiveDevice(device))
        assertTrue(resources.matchesActiveDevice(null))
        val renamed = mock(UsbDevice::class.java)
        `when`(renamed.deviceId).thenReturn(42)
        `when`(renamed.deviceName).thenReturn("usb-41")
        assertTrue(resources.matchesActiveDevice(renamed))
        `when`(renamed.deviceName).thenReturn("other")
        assertFalse(resources.matchesActiveDevice(renamed))
        resources.commitConnection(connection)
        assertTrue(resources.hasActiveConnection)
        assertTrue(resources.hasCloseableSession(0L))
        assertEquals(connection, resources.takeActiveConnection())
        assertFalse(resources.hasCloseableSession(0L))
        assertTrue(resources.hasCloseableSession(7L))
        resources.endDeviceSession()
        assertFalse(resources.matchesActiveDevice(device))
        assertFalse(resources.matchesActiveDevice(null))
        assertFalse(gate.isOpen())
        resources.closeIdleConnection(connection)
        verify(connection).close()
        resources.closeIdleConnection(null)
    }

    @Test
    fun `audio streaming interface is required for attach admission`() {
        val device = mock(UsbDevice::class.java)
        val vendor = mock(UsbInterface::class.java)
        val audio = mock(UsbInterface::class.java)
        `when`(device.interfaceCount).thenReturn(2)
        `when`(device.getInterface(0)).thenReturn(vendor)
        `when`(device.getInterface(1)).thenReturn(audio)
        `when`(vendor.interfaceClass).thenReturn(0xFF)
        `when`(audio.interfaceClass).thenReturn(1)
        `when`(audio.interfaceSubclass).thenReturn(2)
        val resources = resources(UsbExclusiveIoGate(), RecordingNativePort())

        assertTrue(resources.isAudioStreamingDevice(device))
        `when`(audio.interfaceSubclass).thenReturn(3)
        assertFalse(resources.isAudioStreamingDevice(device))
        `when`(device.interfaceCount).thenReturn(0)
        assertFalse(resources.isAudioStreamingDevice(device))
    }

    @Test
    fun `committed handle requires the same live resource and opened player state`() {
        val gate = UsbExclusiveIoGate()
        val native = RecordingNativePort()
        val resources = resources(gate, native)
        val state = UsbExclusiveNativeState(handle = 7L, source = "player_pcm", opened = true)

        assertEquals(0L, resources.committedPlayerHandle(7L, state))
        gate.open()
        assertEquals(7L, resources.committedPlayerHandle(7L, state))
        assertEquals(0L, resources.committedPlayerHandle(0L, state))
        assertEquals(0L, resources.committedPlayerHandle(8L, state))
        assertEquals(0L, resources.committedPlayerHandle(7L, state.copy(opened = false)))
        assertTrue(resources.isCurrentOpenHandle(7L, state.copy(opened = false)))
        resources.markDeviceDetached(0L)
        resources.markDeviceDetached(7L)
        assertEquals(listOf("detached:7"), native.events)
    }

    @Test
    fun `in place reconfiguration retries only compatible native failure`() {
        val native = RecordingNativePort().apply {
            reconfigureResults.addAll(listOf(false, true))
            reports.addAll(listOf("reconfigure_no_compatible_output", "reconfigured", "reconfigured"))
        }
        val resources = resources(UsbExclusiveIoGate(), native)
        val observedReports = mutableListOf<String>()

        val result = resources.tryReconfigurePlayerPcm(
            handle = 7L,
            currentOutputDescription = "rate=44100 channels=2 bits=16 subslot=2",
            outputCandidates = listOf(preferred, fallback),
            inputSampleRate = 48_000,
            inputChannelCount = 2,
            inputEncoding = 2,
            appInForeground = true,
            focusSuppressed = false,
            shouldRetry = { it == "reconfigure_no_compatible_output" },
            onRuntimeReport = observedReports::add
        )

        assertEquals(fallback, result.outputFormat)
        assertEquals("reconfigured", result.report)
        assertTrue(result.complete)
        assertEquals(fallback, result.requireOutputFormat())
        assertEquals("reconfigured", result.requireReport())
        assertFalse(UsbExclusiveSessionResources.ReconfigureResult(preferred, null).complete)
        assertFalse(UsbExclusiveSessionResources.ReconfigureResult(null, "failed").complete)
        assertEquals(listOf("reconfigure_no_compatible_output", "reconfigured", "reconfigured"), observedReports)
    }

    @Test
    fun `in place reconfiguration preserves terminal failure and prepare report`() {
        val failedNative = RecordingNativePort().apply {
            reconfigureResults.add(false)
            reports.add("reconfigure_requires_idle_handle")
        }
        val stopped = resources(UsbExclusiveIoGate(), failedNative).tryReconfigurePlayerPcm(
            handle = 7L,
            currentOutputDescription = "old",
            outputCandidates = listOf(preferred, fallback),
            inputSampleRate = 48_000,
            inputChannelCount = 2,
            inputEncoding = 2,
            appInForeground = true,
            focusSuppressed = false,
            shouldRetry = { false },
            onRuntimeReport = { }
        )
        assertEquals(null, stopped.outputFormat)
        assertEquals("reconfigure_requires_idle_handle", stopped.report)

        val failedPrepare = RecordingNativePort().apply {
            reconfigureResults.add(true)
            prepareResult = false
            reports.addAll(listOf("reconfigured", "prepare_failed"))
        }
        val prepared = resources(UsbExclusiveIoGate(), failedPrepare).tryReconfigurePlayerPcm(
            handle = 7L,
            currentOutputDescription = "old",
            outputCandidates = listOf(preferred),
            inputSampleRate = 48_000,
            inputChannelCount = 2,
            inputEncoding = 2,
            appInForeground = true,
            focusSuppressed = false,
            shouldRetry = { true },
            onRuntimeReport = { }
        )
        assertEquals(null, prepared.outputFormat)
        assertEquals("prepare_failed", prepared.report)
    }

    @Test
    fun `existing handle preparation requires an open gate and every native stage`() {
        val closedGate = UsbExclusiveIoGate()
        val closedNative = RecordingNativePort()
        assertEquals(null, prepareExisting(resources(closedGate, closedNative)))
        assertFalse(closedNative.events.contains("prepare:7"))

        val failedBuffer = RecordingNativePort().apply { bufferResult = false }
        assertEquals(null, prepareExisting(resources(UsbExclusiveIoGate().apply { open() }, failedBuffer)))
        assertFalse(failedBuffer.events.contains("prepare:7"))

        val failedTransfer = RecordingNativePort().apply { transferResult = false }
        assertEquals(null, prepareExisting(resources(UsbExclusiveIoGate().apply { open() }, failedTransfer)))
        assertFalse(failedTransfer.events.contains("prepare:7"))

        val failedPrepare = RecordingNativePort().apply { prepareResult = false }
        assertEquals(null, prepareExisting(resources(UsbExclusiveIoGate().apply { open() }, failedPrepare)))
        assertTrue(failedPrepare.events.contains("prepare:7"))

        val prepared = RecordingNativePort().apply { report = "reused" }
        assertEquals("reused", prepareExisting(resources(UsbExclusiveIoGate().apply { open() }, prepared)))
    }

    @Test
    fun `new handle preparation stops at the failed native stage`() {
        val failedBuffer = RecordingNativePort().apply { bufferResult = false }
        assertFalse(prepareNew(resources(UsbExclusiveIoGate(), failedBuffer)))
        assertFalse(failedBuffer.events.contains("prepare:7"))

        val failedTransfer = RecordingNativePort().apply { transferResult = false }
        assertFalse(prepareNew(resources(UsbExclusiveIoGate(), failedTransfer)))
        assertFalse(failedTransfer.events.contains("prepare:7"))

        val failedPrepare = RecordingNativePort().apply { prepareResult = false }
        assertFalse(prepareNew(resources(UsbExclusiveIoGate(), failedPrepare)))
        assertTrue(failedPrepare.events.contains("prepare:7"))
        assertTrue(prepareNew(resources(UsbExclusiveIoGate(), RecordingNativePort())))
        val notReady = UsbExclusiveSessionResources.RearmResult(
            reconfigured = true,
            bufferConfigured = false,
            transferWindowConfigured = false,
            prepared = false,
            report = "buffer_failed",
            completedFrames = 0L,
            queuedFrames = 0L
        )
        assertFalse(notReady.ready)
        assertTrue(notReady.copy(bufferConfigured = true, prepared = true).ready)
    }

    private fun prepareNew(resources: UsbExclusiveSessionResources): Boolean {
        return resources.preparePlayerPcm(
            handle = 7L,
            outputFormat = preferred,
            inputSampleRate = 48_000,
            inputChannelCount = 2,
            inputEncoding = 2,
            appInForeground = true
        )
    }

    private fun prepareExisting(resources: UsbExclusiveSessionResources): String? {
        return resources.prepareExistingPlayerPcm(
            handle = 7L,
            outputDescription = preferred.description,
            bufferDurationMs = preferred.bufferDurationMs,
            inputSampleRate = 48_000,
            inputChannelCount = 2,
            inputEncoding = 2,
            appInForeground = true
        )
    }

    private fun resources(
        gate: UsbExclusiveIoGate,
        native: RecordingNativePort,
        soundGuard: RecordingSoundGuard = RecordingSoundGuard(),
        onClose: (UsbExclusiveSessionResources.CloseRequest, Int) -> Unit = { _, _ -> }
    ): UsbExclusiveSessionResources {
        return UsbExclusiveSessionResources(
            gate,
            onClose,
            UsbExclusiveSelectedDeviceKeyPort { "auto" },
            native,
            soundGuard
        )
    }

    private fun openedDevice(): UsbExclusiveSessionResources.OpenedDevice {
        val device = mock(UsbDevice::class.java)
        `when`(device.productName).thenReturn("DAC")
        return UsbExclusiveSessionResources.OpenedDevice(
            device = device,
            connection = mock(UsbDeviceConnection::class.java)
        )
    }

    private fun format(sampleRate: Int): ResolvedUsbOutputFormat {
        return ResolvedUsbOutputFormat(
            sampleRate = sampleRate,
            channelCount = 2,
            bitDepth = 16,
            subslotBytes = 2,
            bufferDurationMs = 100,
            description = "rate=$sampleRate channels=2 bits=16 subslot=2"
        )
    }

    private class RecordingSoundGuard : UsbExclusiveSoundGuardPort {
        val events = mutableListOf<String>()
        override fun activate(context: Context, reason: String) {
            events += reason
        }
        override fun releaseWhenNativeIdle(context: Context, reason: String) {
            events += reason
        }
    }

    private class RecordingNativePort : UsbExclusiveNativeSessionPort {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val openHandles = ArrayDeque<Long>()
        val reconfigureResults = ArrayDeque<Boolean>()
        val reports = ArrayDeque<String>()
        var openError = "nativeOpen failed"
        var prepareResult = true
        var bufferResult = true
        var transferResult = true
        var startToneResult = true
        var report = "running=false"
        var closeEntered: CountDownLatch? = null
        var closeRelease: CountDownLatch? = null

        override fun open(
            connection: UsbDeviceConnection,
            sampleRate: Int,
            channelCount: Int,
            bitsPerSample: Int,
            subslotBytes: Int
        ): Long {
            events += "open:$sampleRate"
            return openHandles.removeFirst()
        }
        override fun lastOpenError(): String = openError
        override fun startGeneratedTone(handle: Long): Boolean = startToneResult
        override fun runtimeReport(handle: Long): String = reports.pollFirst() ?: report
        override fun configurePlayerBufferDuration(handle: Long, durationMs: Int): Boolean =
            bufferResult
        override fun configurePlayerTransferWindow(handle: Long, durationMs: Int): Boolean =
            transferResult
        override fun preparePlayerPcm(
            handle: Long,
            inputSampleRate: Int,
            inputChannelCount: Int,
            inputEncoding: Int
        ): Boolean {
            events += "prepare:$handle"
            return prepareResult
        }
        override fun reconfigurePlayerPcmOutput(
            handle: Long,
            sampleRate: Int,
            channelCount: Int,
            bitsPerSample: Int,
            subslotBytes: Int
        ): Boolean = reconfigureResults.removeFirst()
        override fun setPlayerFocusMuted(handle: Long, muted: Boolean): Boolean = true
        override fun completedAudioFrames(handle: Long): Long = 0L
        override fun queuedPlayerFrames(handle: Long): Long = 0L
        override fun markDeviceDetached(handle: Long) {
            events += "detached:$handle"
        }
        override fun stop(handle: Long) {
            events += "stop:$handle"
        }
        override fun close(handle: Long) {
            closeEntered?.countDown()
            closeRelease?.await(2, TimeUnit.SECONDS)
            events += "close:$handle"
        }
    }
}
