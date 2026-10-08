package moe.ouom.neriplayer.core.player.usb.sink

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusivePcmWriterTest {
    @Test
    fun `direct write keeps source offset and volume`() {
        val port = FakePcmWritePort()
        val published = mutableListOf<Float>()
        val writer = UsbExclusivePcmWriter(port, published::add)
        val source = ByteBuffer.allocateDirect(8).put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        source.position(2)

        val consumed = writer.writeNative(source, 4, 0.4f, snapshot())

        assertEquals(4, consumed)
        assertEquals(2, source.position())
        assertEquals(2, port.lastOffset)
        assertEquals(listOf<Byte>(3, 4, 5, 6), port.lastBytes)
        assertEquals(listOf(0.4f), published)
    }

    @Test
    fun `heap input is copied into owned direct scratch`() {
        val port = FakePcmWritePort()
        val writer = UsbExclusivePcmWriter(port) {}
        writer.prepareDirectScratch()
        writer.prepareDirectScratch()
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6))
        source.position(1)

        val consumed = writer.writeNative(source, 4, 1f, snapshot())

        assertEquals(4, consumed)
        assertEquals(1, source.position())
        assertTrue(port.lastBufferWasDirect)
        assertEquals(0, port.lastOffset)
        assertEquals(listOf<Byte>(2, 3, 4, 5), port.lastBytes)
        writer.release()
        assertEquals(0, writer.writeNative(source, 4, 1f, snapshot()))
    }

    @Test
    fun `float input converts to packed 24 bit and reports whole consumed frames`() {
        val port = FakePcmWritePort(outputDescription = "rate=48000 channels=1 bits=24 subslot=3")
        port.acceptedBytes = 3
        val writer = UsbExclusivePcmWriter(port) {}
        writer.prepareDirectScratch()
        writer.configureSoftwareFloatInput(usingNative = true, pcmEncoding = C.ENCODING_PCM_FLOAT, inputSampleRate = 48_000)
        val source = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(1f).putFloat(-1f)
        source.flip()

        val consumed = writer.writeNative(
            source, 8, 1f,
            snapshot(channelCount = 1, frameBytes = 4, pcmEncoding = C.ENCODING_PCM_FLOAT)
        )

        assertEquals(4, consumed)
        assertEquals(0, source.position())
        assertEquals(6, port.lastBytes.size)
        assertEquals(listOf<Byte>(-1, -1, 127, 0, 0, -128), port.lastBytes)
    }

    @Test
    fun `float input decoded from 24 bit PCM converts back bit exactly`() {
        val port = FakePcmWritePort(outputDescription = "rate=96000 channels=1 bits=24 subslot=3")
        port.acceptedBytes = 9
        val writer = UsbExclusivePcmWriter(port) {}
        writer.prepareDirectScratch()
        writer.configureSoftwareFloatInput(usingNative = true, pcmEncoding = C.ENCODING_PCM_FLOAT, inputSampleRate = 96_000)
        val samples = listOf(0x7FFFFE, -0x400001, 0x123456)
        val source = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { source.putFloat(it / 8_388_608f) }
        source.flip()

        writer.writeNative(source, 12, 1f, snapshot(channelCount = 1, frameBytes = 4, pcmEncoding = C.ENCODING_PCM_FLOAT))

        val expected = samples.flatMap { value ->
            listOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte())
        }
        assertEquals(expected, port.lastBytes)
    }

    @Test
    fun `resampled float input is written natively without software conversion`() {
        val port = FakePcmWritePort(outputDescription = "rate=48000 channels=2 bits=16 subslot=2")
        val writer = UsbExclusivePcmWriter(port) {}
        writer.prepareDirectScratch()
        writer.configureSoftwareFloatInput(usingNative = true, pcmEncoding = C.ENCODING_PCM_FLOAT, inputSampleRate = 192_000)
        val source = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.5f).putFloat(-0.5f).putFloat(0.25f).putFloat(-0.25f)
        source.flip()

        val consumed = writer.writeNative(
            source, 16, 1f,
            snapshot(frameBytes = 8, pcmEncoding = C.ENCODING_PCM_FLOAT)
        )

        assertEquals(16, consumed)
        assertTrue(port.lastBufferWasDirect)
        assertEquals(16, port.lastBytes.size)
        assertEquals(0, port.lastOffset)
    }

    @Test
    fun `float conversion handles 16 and 32 bit prepared output`() {
        listOf(
            "rate=48000 channels=1 bits=16 subslot=2" to 2,
            "rate=48000 channels=1 bits=32 subslot=4" to 4,
        ).forEach { (description, expectedBytes) ->
            val port = FakePcmWritePort(outputDescription = description)
            val writer = UsbExclusivePcmWriter(port) {}
            writer.prepareDirectScratch()
            writer.configureSoftwareFloatInput(true, C.ENCODING_PCM_FLOAT, 48_000)
            val source = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f)
            source.flip()

            assertEquals(4, writer.writeNative(
                source, 4, 1f,
                snapshot(channelCount = 1, frameBytes = 4, pcmEncoding = C.ENCODING_PCM_FLOAT)
            ))
            assertEquals(expectedBytes, port.lastBytes.size)
        }
    }

    @Test
    fun `float conversion rejects invalid frames and unavailable scratch`() {
        val port = FakePcmWritePort(outputDescription = "rate=48000 channels=1 bits=16 subslot=2")
        val writer = UsbExclusivePcmWriter(port) {}
        writer.configureSoftwareFloatInput(true, C.ENCODING_PCM_FLOAT, 48_000)
        val source = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f)
        source.flip()
        val floatState = snapshot(channelCount = 1, frameBytes = 4, pcmEncoding = C.ENCODING_PCM_FLOAT)

        assertEquals(0, writer.writeNative(source, 4, 1f, floatState))
        writer.prepareDirectScratch()
        val oversizedSource = ByteBuffer.allocate(600_000)
        assertEquals(0, writer.writeNative(oversizedSource, 600_000, 1f, floatState))
        assertEquals(0, writer.writeNative(source, 4, 1f, floatState.copy(frameBytes = 0)))
        assertEquals(0, writer.writeNative(source, 2, 1f, floatState))
        port.acceptedBytes = 0
        assertEquals(0, writer.writeNative(source, 4, 1f, floatState))
    }

    @Test
    fun `invalid native handle and empty input skip writes`() {
        val port = FakePcmWritePort()
        val writer = UsbExclusivePcmWriter(port) {}
        val source = ByteBuffer.allocateDirect(4)

        assertEquals(0, writer.writeNative(source, 4, 1f, snapshot(handle = 0L)))
        assertEquals(0, writer.writeNative(source, 0, 1f, snapshot()))
        assertEquals(emptyList<Byte>(), port.lastBytes)
    }

    @Test
    fun `write planner uses fresh live free space over stale report`() {
        val port = FakePcmWritePort()
        port.report = "source=player_pcm sampleRate=48000 channels=2 subslotBytes=2 " +
            "transferBytes=3072 pcmLevel=288000/288000 pcmFreeBytes=0 " +
            "running=true transportFailed=false lastError=none"
        port.liveFreeBytes = 288_000L
        val writer = UsbExclusivePcmWriter(port) {}

        val size = writer.writeSize(65_536, directBuffer = true, snapshot())

        assertEquals(12_288, size)
        assertEquals(1, port.runtimeReads)
        assertEquals(1, port.freeReads)
    }

    @Test
    fun `running transport fills the queue to its waterline in one buffer callback`() {
        val port = FakePcmWritePort()
        port.report = "source=player_pcm sampleRate=48000 channels=2 subslotBytes=2 " +
            "transferBytes=3072 pcmLevel=0/288000 pcmFreeBytes=288000 " +
            "running=true transportFailed=false lastError=none"
        port.liveFreeBytes = 288_000L
        port.consumeFreeBytesOnWrite = true
        val writer = UsbExclusivePcmWriter(port) {}
        val source = ByteBuffer.allocateDirect(192_000)
        val state = snapshot(runningQueueTargetMs = 750L)

        val first = writer.writeSize(source.remaining(), directBuffer = true, state)
        val written = writer.writeNativeUntilQueueTarget(source, first, 1f, state)

        assertEquals(144_000, written)
        assertEquals(0, source.position())
        assertEquals(12, port.writes)
        assertEquals(144_000 - 8_832, port.lastOffset)
    }

    @Test
    fun `queue top up waits for the transport and stops when native accepts nothing`() {
        val port = FakePcmWritePort()
        port.report = "source=player_pcm sampleRate=48000 channels=2 subslotBytes=2 " +
            "transferBytes=3072 pcmLevel=0/288000 pcmFreeBytes=288000 " +
            "running=true transportFailed=false lastError=none"
        port.liveFreeBytes = 288_000L
        port.consumeFreeBytesOnWrite = true
        val writer = UsbExclusivePcmWriter(port) {}
        val source = ByteBuffer.allocateDirect(192_000)

        assertEquals(12_288, writer.writeNativeUntilQueueTarget(source, 12_288, 1f, snapshot(transportStarted = false)))
        assertEquals(1, port.writes)

        port.acceptedBytes = 0
        assertEquals(0, writer.writeNativeUntilQueueTarget(source, 12_288, 1f, snapshot(runningQueueTargetMs = 750L)))
        assertEquals(2, port.writes)
    }

    @Test
    fun `full queue refreshes native runtime before rejecting a write`() {
        val port = FakePcmWritePort()
        port.report = "source=player_pcm sampleRate=48000 channels=2 subslotBytes=2 " +
            "transferBytes=3072 pcmLevel=288000/288000 pcmFreeBytes=0 " +
            "running=true transportFailed=false lastError=none"
        port.liveFreeBytes = 0L
        port.freeAfterRefresh = 288_000L
        port.nowMs = 1_000L
        val writer = UsbExclusivePcmWriter(port) {}

        val size = writer.writeSize(65_536, directBuffer = true, snapshot())

        assertEquals(12_288, size)
        assertEquals(1, port.refreshes)
        assertEquals(2, port.runtimeReads)
        assertEquals(2, port.freeReads)
    }

    @Test
    fun `heap write plan is bounded by owned scratch capacity`() {
        val port = FakePcmWritePort()
        port.report = "source=player_pcm sampleRate=48000 channels=2 subslotBytes=2 " +
            "transferBytes=3072 pcmLevel=0/288000 pcmFreeBytes=288000 " +
            "running=true transportFailed=false lastError=none"
        val writer = UsbExclusivePcmWriter(port) {}

        assertEquals(0, writer.writeSize(65_536, directBuffer = false, snapshot()))
        writer.prepareDirectScratch()
        assertEquals(12_288, writer.writeSize(65_536, directBuffer = false, snapshot()))
    }

    @Test
    fun `stalled write refresh respects healthy queue interval`() {
        val port = FakePcmWritePort()
        port.report = "source=player_pcm pcmLevel=288000/288000 pcmFreeBytes=0 " +
            "running=true transportFailed=false lastError=none"
        val writer = UsbExclusivePcmWriter(port) {}

        assertEquals(port.report, writer.refreshRuntimeAfterStalledWrite(7L, 100L))
        assertEquals(0, port.refreshes)
        writer.refreshRuntimeAfterStalledWrite(7L, 300L)
        assertEquals(1, port.refreshes)
        port.report = "source=diagnostic running=false transportFailed=true lastError=none"
        writer.refreshRuntimeAfterStalledWrite(7L, 301L)
        assertEquals(2, port.refreshes)
    }

    @Test
    fun `urgent audio thread setup is idempotent`() {
        val writer = UsbExclusivePcmWriter(FakePcmWritePort()) {}
        writer.ensureUrgentAudioThreadPriority()
        writer.ensureUrgentAudioThreadPriority()
    }

    @Test
    fun `backpressure parks only a background writer with no free queue space`() {
        val port = FakePcmWritePort()
        port.allowPark = true
        val writer = UsbExclusivePcmWriter(port) {}

        writer.observeBenignBackpressure(
            nowMs = 1_000L, pendingBytes = 8, attemptedBytes = 0,
            runtimeReport = backpressureReport(4) + " pcmBackpressureCurrentMs=120",
            state = snapshot()
        )

        assertEquals(listOf(4_000_000L), port.parkedNanos)
    }

    @Test
    fun `backpressure parking skips free space and invalid input frame timing`() {
        val port = FakePcmWritePort()
        port.allowPark = true
        val writer = UsbExclusivePcmWriter(port) {}
        val freeReport = "source=player_pcm pcmLevel=287999/288000 pcmFreeBytes=1 " +
            "running=true transportFailed=false"
        val fullReport = freeReport.replace("pcmFreeBytes=1", "pcmFreeBytes=0")

        writer.parkForBackpressure(freeReport, false, 48_000, 4)
        writer.parkForBackpressure(fullReport, false, 0, 4)
        writer.parkForBackpressure(fullReport, false, 48_000, 0)

        assertEquals(emptyList<Long>(), port.parkedNanos)
    }

    @Test
    fun `backpressure progress resets stall clock and release clears it`() {
        val writer = UsbExclusivePcmWriter(FakePcmWritePort()) {}
        val stalled = writer.observeBenignBackpressure(
            nowMs = 1_000L, pendingBytes = 8, attemptedBytes = 0,
            runtimeReport = backpressureReport(completedTransfers = 4), state = snapshot()
        )
        val progressed = writer.observeBenignBackpressure(
            nowMs = 4_500L, pendingBytes = 8, attemptedBytes = 0,
            runtimeReport = backpressureReport(completedTransfers = 5), state = snapshot()
        )
        val stopped = writer.observeBenignBackpressure(
            nowMs = 7_600L, pendingBytes = 8, attemptedBytes = 0,
            runtimeReport = backpressureReport(completedTransfers = 5), state = snapshot()
        )

        assertFalse(stalled.shouldRecover)
        assertTrue(progressed.madeProgress)
        assertFalse(progressed.shouldRecover)
        assertTrue(stopped.shouldRecover)
        assertEquals(3_100L, stopped.heldMs)
        writer.release()
        val afterRelease = writer.observeBenignBackpressure(
            nowMs = 8_000L, pendingBytes = 8, attemptedBytes = 0,
            runtimeReport = backpressureReport(completedTransfers = 5), state = snapshot()
        )
        assertEquals(0L, afterRelease.heldMs)
        assertFalse(afterRelease.shouldRecover)
    }

    @Test
    fun `backpressure without active native player never requests recovery`() {
        val writer = UsbExclusivePcmWriter(FakePcmWritePort()) {}
        writer.observeBenignBackpressure(
            nowMs = 1_000L, pendingBytes = 8, attemptedBytes = 0,
            runtimeReport = backpressureReport(4), state = snapshot(playing = false)
        )

        val observation = writer.observeBenignBackpressure(
            nowMs = 5_000L, pendingBytes = 8, attemptedBytes = 0,
            runtimeReport = backpressureReport(4), state = snapshot(playing = false)
        )

        assertFalse(observation.shouldRecover)
    }

    @Test
    fun `backpressure recovery requires a running healthy player transfer`() {
        val healthy = backpressureReport(4)
        val rejected = listOf(
            healthy.replace("source=player_pcm", "source=diagnostic"),
            healthy.replace("running=true", "running=false"),
            healthy.replace("transportFailed=false", "transportFailed=true"),
            healthy.replace("inFlight=8", "inFlight=0"),
            healthy.replace("completedTransfers=4", "completedTransfers=unknown"),
        )
        rejected.forEach { report ->
            val writer = UsbExclusivePcmWriter(FakePcmWritePort()) {}
            writer.observeBenignBackpressure(1_000L, 8, 0, report, snapshot())
            val observation = writer.observeBenignBackpressure(5_000L, 8, 0, report, snapshot())
            assertFalse(report, observation.shouldRecover)
        }
    }

    private fun snapshot(
        handle: Long = 7L,
        channelCount: Int = 2,
        frameBytes: Int = 4,
        pcmEncoding: Int = C.ENCODING_PCM_16BIT,
        playing: Boolean = true,
        transportStarted: Boolean = true,
        runningQueueTargetMs: Long? = null,
    ) = UsbExclusiveNativeWriteSnapshot(
        handle = handle,
        sampleRate = 48_000,
        frameBytes = frameBytes,
        channelCount = channelCount,
        pcmEncoding = pcmEncoding,
        transportStarted = transportStarted,
        playing = playing,
        usingNative = true,
        hasQueuedPcm = true,
        prerollMs = 300L,
        runningQueueTargetMs = runningQueueTargetMs,
    )

    private fun backpressureReport(completedTransfers: Int): String =
        "source=player_pcm pcmLevel=288000/288000 pcmFreeBytes=0 " +
            "completedTransfers=$completedTransfers inFlight=8 running=true transportFailed=false"

    private class FakePcmWritePort(
        private val outputDescription: String = "rate=48000 channels=2 bits=16 subslot=2",
    ) : UsbExclusivePcmWritePort {
        var acceptedBytes: Int? = null
        var report = ""
        var liveFreeBytes: Long? = null
        var freeAfterRefresh: Long? = null
        var nowMs = 0L
        var refreshes = 0
        var allowPark = false
        val parkedNanos = mutableListOf<Long>()
        var runtimeReads = 0
        var freeReads = 0
        var lastOffset = -1
        var lastBufferWasDirect = false
        var lastBytes = emptyList<Byte>()
        var consumeFreeBytesOnWrite = false
        var writes = 0

        override fun write(handle: Long, buffer: ByteBuffer, offset: Int, size: Int, volume: Float): Int {
            writes += 1
            lastOffset = offset
            lastBufferWasDirect = buffer.isDirect
            val copy = buffer.duplicate()
            copy.position(offset)
            copy.limit(offset + size)
            lastBytes = ByteArray(size).also { copy.get(it) }.toList()
            val accepted = acceptedBytes ?: size
            if (consumeFreeBytesOnWrite) liveFreeBytes = liveFreeBytes?.minus(accepted)
            return accepted
        }

        override fun runtimeReport(handle: Long): String {
            runtimeReads += 1
            return report
        }

        override fun freeBytes(handle: Long): Long? {
            freeReads += 1
            return liveFreeBytes
        }

        override fun refreshRuntime(handle: Long) {
            refreshes += 1
            liveFreeBytes = freeAfterRefresh ?: liveFreeBytes
        }

        override fun outputFormat(): String = outputDescription

        override fun elapsedRealtimeMs(): Long = nowMs

        override fun mayParkCurrentThread(): Boolean = allowPark

        override fun parkNanos(nanos: Long) {
            parkedNanos += nanos
        }
    }
}
