package moe.ouom.neriplayer.core.download

import java.io.File
import java.util.Locale
import moe.ouom.neriplayer.core.download.execution.DeferredDownloadScheduleQueue
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTiming
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTimingCollector
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * deterministic JVM scale harness for the bounded queue and transfer-cycle timing contract
 *
 * this is an S1 model: it records stable operation counts and virtual timings, but it is not
 * a substitute for Room, a real DocumentsProvider, a device profile, or a network throughput run
 */
class DownloadPipelineScaleBenchmarkTest {
    @Test
    fun `scale matrix keeps enrichment off the network lane and exports evidence`() {
        val rows = mutableListOf<DownloadPipelineBenchmarkResult>()
        listOf(10, 100, 500, 1_000).forEach { operationCount ->
            listOf(6, 8).forEach { networkLanes ->
                val audioOnly = runScenario(
                    operationCount = operationCount,
                    networkLanes = networkLanes,
                    enrichmentDelayMs = 0L,
                    audioOnly = true
                )
                val fullPipeline = runScenario(
                    operationCount = operationCount,
                    networkLanes = networkLanes,
                    enrichmentDelayMs = 0L,
                    audioOnly = false
                )
                val delayedEnrichment = runScenario(
                    operationCount = operationCount,
                    networkLanes = networkLanes,
                    enrichmentDelayMs = 30_000L,
                    audioOnly = false
                )

                assertScenarioComplete(audioOnly)
                assertScenarioComplete(fullPipeline)
                assertScenarioComplete(delayedEnrichment)
                assertEquals(audioOnly.networkStartTimesMs, fullPipeline.networkStartTimesMs)
                assertEquals(fullPipeline.networkStartTimesMs, delayedEnrichment.networkStartTimesMs)
                assertTrue(
                    "delayed enrichment must not hold a network lane",
                    delayedEnrichment.networkLanesHeldUntilCore
                )
                assertTrue(
                    "delayed enrichment should be visible in its own queue",
                    delayedEnrichment.maxEnrichmentQueueWaitMs > 0L
                )
                rows += audioOnly
                rows += fullPipeline
                rows += delayedEnrichment
            }
        }

        val output = File(
            System.getProperty("neriplayer.downloadBenchmark.output")
                ?: "build/reports/download-benchmark/deterministic-scale.json"
        )
        output.parentFile?.mkdirs()
        output.writeText(DownloadPipelineBenchmarkReport.toJson(rows))
        println("DOWNLOAD_BENCHMARK_JSON=${output.absolutePath}")
        println(
            "DOWNLOAD_BENCHMARK_ROWS=${rows.size}; " +
                "kind=deterministic-jvm-s1; physical-throughput=not-measured"
        )
    }

    private fun assertScenarioComplete(result: DownloadPipelineBenchmarkResult) {
        assertEquals(result.operationCount, result.completedCount)
        assertEquals(result.operationCount, result.timingCount)
        assertEquals(result.operationCount.toLong() * FIXED_AUDIO_BYTES, result.networkBytes)
        assertEquals(result.operationCount, result.providerCommitCount)
        assertEquals(0, result.queueResidentAfterRun)
        assertTrue(result.maxQueueResident <= result.operationCount)
        assertTrue(result.maxMaterializedRequests <= result.operationCount)
        assertTrue(result.permitHeldAtLeastTransfer)
    }

    private fun runScenario(
        operationCount: Int,
        networkLanes: Int,
        enrichmentDelayMs: Long,
        audioOnly: Boolean
    ): DownloadPipelineBenchmarkResult {
        val queue = DeferredDownloadScheduleQueue(maxRequests = operationCount.coerceAtLeast(1))
        val requests = (0 until operationCount).map { index ->
            DownloadExecutionRequest(
                operationId = "benchmark-$operationCount-$networkLanes-$audioOnly-$index",
                song = SongItem(
                    id = index.toLong() + 1L,
                    name = "Benchmark $index",
                    artist = "Benchmark Artist",
                    album = "Benchmark Album",
                    albumId = 1L,
                    durationMs = 180_000L,
                    coverUrl = null,
                    sourceStableKey = "benchmark:$index"
                ),
                artifactLeaseId = "benchmark-lease-$index"
            )
        }
        requests.forEach(queue::enqueue)
        val maxQueueResident = queue.size()
        var virtualNowNs = 0L
        val collector = DownloadOperationTimingCollector(
            nowNs = { virtualNowNs },
            maxOperations = operationCount.coerceAtLeast(1)
        )
        val network = FakeNetwork()
        val provider = FakeProvider()
        val networkAvailability = LanePool(networkLanes)
        val coreAvailability = LanePool(CORE_COMMIT_LANES)
        val enrichmentAvailability = LanePool(ENRICHMENT_LANES)
        val networkStartTimesMs = ArrayList<Long>(operationCount)
        val timings = ArrayList<DownloadOperationTiming>(operationCount)
        var maxMaterializedRequests = 0

        fun markAt(
            token: moe.ouom.neriplayer.core.download.observability.DownloadOperationTraceToken?,
            phase: DownloadOperationTracePhase,
            atMs: Long
        ) {
            virtualNowNs = atMs.coerceAtLeast(0L) * NANOS_PER_MILLISECOND
            collector.mark(token, phase)
        }

        requests.forEachIndexed { index, expectedRequest ->
            val request = queue.poll() ?: error("benchmark queue lost operation $index")
            assertEquals(expectedRequest.operationId, request.operationId)
            queue.remove(request)
            maxMaterializedRequests = maxOf(maxMaterializedRequests, queue.size() + 1)
            val token = requireNotNull(
                collector.begin(request.operationId, attemptId = 1L)
            )
            val selectedAtMs = (index / PUMP_PAGE_SIZE).toLong()
            val backendStartedAtMs = selectedAtMs + BACKEND_START_MS
            val resolveStartedAtMs = backendStartedAtMs
            val resolveFinishedAtMs = resolveStartedAtMs + SOURCE_RESOLVE_MS
            val prepareFinishedAtMs = resolveFinishedAtMs + PREPARE_MS
            val permitRequestedAtMs = prepareFinishedAtMs
            val networkStartAtMs = networkAvailability.reserveStartAt(
                requestedAtMs = permitRequestedAtMs,
                durationMs = TRANSFER_MS
            )
            val networkFinishAtMs = networkStartAtMs + TRANSFER_MS
            val coreStartAtMs = coreAvailability.reserveStartAt(
                requestedAtMs = networkFinishAtMs,
                durationMs = CORE_COMMIT_MS
            )
            val coreFinishAtMs = coreStartAtMs + CORE_COMMIT_MS
            networkAvailability.extendLastReservation(coreFinishAtMs)
            val enrichmentStartAtMs = if (audioOnly) {
                coreFinishAtMs
            } else {
                enrichmentAvailability.reserveStartAt(
                    requestedAtMs = coreFinishAtMs,
                    durationMs = ENRICHMENT_WORK_MS + enrichmentDelayMs
                )
            }
            val enrichmentFinishAtMs =
                enrichmentStartAtMs + ENRICHMENT_WORK_MS + enrichmentDelayMs

            markAt(token, DownloadOperationTracePhase.ENQUEUED, 0L)
            markAt(token, DownloadOperationTracePhase.QUEUE_SELECTED, selectedAtMs)
            markAt(token, DownloadOperationTracePhase.HOST_ADMISSION_REQUESTED, selectedAtMs)
            markAt(token, DownloadOperationTracePhase.HOST_ADMISSION_GRANTED, selectedAtMs)
            markAt(token, DownloadOperationTracePhase.BACKEND_SCHEDULED, selectedAtMs)
            markAt(token, DownloadOperationTracePhase.BACKEND_STARTED, backendStartedAtMs)
            markAt(token, DownloadOperationTracePhase.SOURCE_RESOLVE_STARTED, resolveStartedAtMs)
            markAt(token, DownloadOperationTracePhase.SOURCE_RESOLVE_FINISHED, resolveFinishedAtMs)
            markAt(token, DownloadOperationTracePhase.PREPARE_STARTED, resolveFinishedAtMs)
            markAt(token, DownloadOperationTracePhase.PREPARE_FINISHED, prepareFinishedAtMs)
            markAt(token, DownloadOperationTracePhase.NETWORK_PERMIT_REQUESTED, permitRequestedAtMs)
            markAt(token, DownloadOperationTracePhase.NETWORK_PERMIT_GRANTED, networkStartAtMs)
            markAt(token, DownloadOperationTracePhase.NETWORK_STARTED, networkStartAtMs)
            markAt(token, DownloadOperationTracePhase.NETWORK_FINISHED, networkFinishAtMs)
            markAt(token, DownloadOperationTracePhase.CORE_COMMIT_REQUESTED, networkFinishAtMs)
            markAt(token, DownloadOperationTracePhase.CORE_COMMIT_GRANTED, coreStartAtMs)
            markAt(token, DownloadOperationTracePhase.CORE_COMMIT_STARTED, coreStartAtMs)
            markAt(token, DownloadOperationTracePhase.CORE_COMMIT_FINISHED, coreFinishAtMs)
            markAt(token, DownloadOperationTracePhase.CORE_COMMITTED, coreFinishAtMs)
            markAt(token, DownloadOperationTracePhase.NETWORK_PERMIT_RELEASED, coreFinishAtMs)
            if (audioOnly) {
                markAt(token, DownloadOperationTracePhase.TERMINAL, coreFinishAtMs)
            } else {
                markAt(token, DownloadOperationTracePhase.ENRICHMENT_ENQUEUED, coreFinishAtMs)
                markAt(token, DownloadOperationTracePhase.ENRICHMENT_STARTED, enrichmentStartAtMs)
                markAt(token, DownloadOperationTracePhase.ENRICHMENT_METADATA_STARTED, enrichmentStartAtMs)
                markAt(
                    token,
                    DownloadOperationTracePhase.ENRICHMENT_METADATA_FINISHED,
                    enrichmentStartAtMs + ENRICHMENT_WORK_MS
                )
                markAt(token, DownloadOperationTracePhase.ENRICHMENT_FINISHED, enrichmentFinishAtMs)
                markAt(token, DownloadOperationTracePhase.TERMINAL, enrichmentFinishAtMs)
            }
            network.consumeFixedPayload()
            provider.commitCore()
            if (!audioOnly) provider.writeEnrichment()
            networkStartTimesMs += networkStartAtMs
            timings += requireNotNull(collector.snapshot(token))
        }

        assertTrue(queue.isEmpty())
        return DownloadPipelineBenchmarkResult(
            operationCount = operationCount,
            networkLanes = networkLanes,
            audioOnly = audioOnly,
            enrichmentDelayMs = enrichmentDelayMs,
            completedCount = timings.count { timing ->
                timing.markNs(DownloadOperationTracePhase.TERMINAL) != null
            },
            timingCount = timings.size,
            networkBytes = network.bytesRead,
            providerCommitCount = provider.coreCommitCount,
            maxQueueResident = maxQueueResident,
            queueResidentAfterRun = queue.size(),
            maxMaterializedRequests = maxMaterializedRequests,
            networkStartTimesMs = networkStartTimesMs,
            queueWaitP95Ms = percentileMs(timings.mapNotNull(DownloadOperationTiming::queueWaitNs)),
            permitWaitP95Ms = percentileMs(timings.mapNotNull(DownloadOperationTiming::networkPermitWaitNs)),
            transferP95Ms = percentileMs(timings.mapNotNull(DownloadOperationTiming::transferNs)),
            coreCommitP95Ms = percentileMs(timings.mapNotNull(DownloadOperationTiming::coreCommitIoNs)),
            enrichmentQueueWaitP95Ms = percentileMs(
                timings.mapNotNull(DownloadOperationTiming::enrichmentQueueWaitNs)
            ),
            maxEnrichmentQueueWaitMs = timings
                .mapNotNull(DownloadOperationTiming::enrichmentQueueWaitNs)
                .maxOrNull()
                ?.div(NANOS_PER_MILLISECOND)
                ?: 0L,
            permitHeldAtLeastTransfer = timings.all { timing ->
                (timing.networkPermitHeldNs ?: 0L) >= (timing.transferNs ?: 0L)
            },
            networkLanesHeldUntilCore = timings.all { timing ->
                val transferEnd = timing.markNs(DownloadOperationTracePhase.NETWORK_FINISHED)
                val release = timing.markNs(DownloadOperationTracePhase.NETWORK_PERMIT_RELEASED)
                transferEnd != null && release != null && release >= transferEnd
            },
            virtualMakespanMs = timings
                .mapNotNull { timing ->
                    timing.markNs(DownloadOperationTracePhase.TERMINAL)
                }
                .maxOrNull()
                ?.div(NANOS_PER_MILLISECOND)
                ?: 0L
        )
    }

    private fun percentileMs(valuesNs: List<Long>): Long {
        if (valuesNs.isEmpty()) return 0L
        val sorted = valuesNs.sorted()
        val index = ((sorted.size * 95L + 99L) / 100L - 1L)
            .coerceIn(0L, sorted.lastIndex.toLong())
            .toInt()
        return sorted[index] / NANOS_PER_MILLISECOND
    }

    private class LanePool(count: Int) {
        private val availability = LongArray(count)
        private var lastReservationLane: Int = -1

        init {
            require(count > 0)
        }

        fun reserveStartAt(requestedAtMs: Long, durationMs: Long): Long {
            var lane = 0
            for (index in 1 until availability.size) {
                if (availability[index] < availability[lane]) lane = index
            }
            val start = maxOf(requestedAtMs, availability[lane])
            availability[lane] = start + durationMs
            lastReservationLane = lane
            return start
        }

        /** network permit release extends the lane selected for the most recent transfer */
        fun extendLastReservation(releaseAtMs: Long) {
            if (lastReservationLane < 0) return
            availability[lastReservationLane] = maxOf(
                availability[lastReservationLane],
                releaseAtMs
            )
            lastReservationLane = -1
        }
    }

    private class FakeNetwork {
        var bytesRead: Long = 0L

        fun consumeFixedPayload() {
            var remaining = FIXED_AUDIO_BYTES
            while (remaining > 0L) {
                val chunk = minOf(remaining, STREAM_BUFFER_BYTES)
                bytesRead += chunk
                remaining -= chunk
            }
        }
    }

    private class FakeProvider {
        var coreCommitCount: Int = 0
        fun commitCore() {
            coreCommitCount++
        }

        fun writeEnrichment() = Unit
    }

    private data class DownloadPipelineBenchmarkResult(
        val operationCount: Int,
        val networkLanes: Int,
        val audioOnly: Boolean,
        val enrichmentDelayMs: Long,
        val completedCount: Int,
        val timingCount: Int,
        val networkBytes: Long,
        val providerCommitCount: Int,
        val maxQueueResident: Int,
        val queueResidentAfterRun: Int,
        val maxMaterializedRequests: Int,
        val networkStartTimesMs: List<Long>,
        val queueWaitP95Ms: Long,
        val permitWaitP95Ms: Long,
        val transferP95Ms: Long,
        val coreCommitP95Ms: Long,
        val enrichmentQueueWaitP95Ms: Long,
        val maxEnrichmentQueueWaitMs: Long,
        val permitHeldAtLeastTransfer: Boolean,
        val networkLanesHeldUntilCore: Boolean,
        val virtualMakespanMs: Long
    ) {
        val songsPerMinute: Double
            get() = if (virtualMakespanMs <= 0L) {
                0.0
            } else {
                operationCount * 60_000.0 / virtualMakespanMs
            }
    }

    private object DownloadPipelineBenchmarkReport {
        fun toJson(rows: List<DownloadPipelineBenchmarkResult>): String {
            val builder = StringBuilder()
            builder.append("{\"kind\":\"deterministic-jvm-s1\",\"rows\":[")
            rows.forEachIndexed { index, row ->
                if (index > 0) builder.append(',')
                builder.append('{')
                    .append("\"n\":").append(row.operationCount)
                    .append(",\"networkLanes\":").append(row.networkLanes)
                    .append(",\"audioOnly\":").append(row.audioOnly)
                    .append(",\"enrichmentDelayMs\":").append(row.enrichmentDelayMs)
                    .append(",\"completed\":").append(row.completedCount)
                    .append(",\"timings\":").append(row.timingCount)
                    .append(",\"networkBytes\":").append(row.networkBytes)
                    .append(",\"maxQueueResident\":").append(row.maxQueueResident)
                    .append(",\"maxMaterialized\":").append(row.maxMaterializedRequests)
                    .append(",\"queueWaitP95Ms\":").append(row.queueWaitP95Ms)
                    .append(",\"permitWaitP95Ms\":").append(row.permitWaitP95Ms)
                    .append(",\"transferP95Ms\":").append(row.transferP95Ms)
                    .append(",\"coreCommitP95Ms\":").append(row.coreCommitP95Ms)
                    .append(",\"enrichmentQueueWaitP95Ms\":")
                    .append(row.enrichmentQueueWaitP95Ms)
                    .append(",\"maxEnrichmentQueueWaitMs\":")
                    .append(row.maxEnrichmentQueueWaitMs)
                    .append(",\"virtualMakespanMs\":").append(row.virtualMakespanMs)
                    .append(",\"syntheticSongsPerMinute\":")
                    .append(String.format(Locale.US, "%.3f", row.songsPerMinute))
                    .append('}')
            }
            return builder.append("]}").toString()
        }
    }

    private companion object {
        private const val PUMP_PAGE_SIZE = 64
        private const val CORE_COMMIT_LANES = 2
        private const val ENRICHMENT_LANES = 2
        private const val BACKEND_START_MS = 1L
        private const val SOURCE_RESOLVE_MS = 1L
        private const val PREPARE_MS = 1L
        private const val TRANSFER_MS = 90L
        private const val CORE_COMMIT_MS = 10L
        private const val ENRICHMENT_WORK_MS = 1L
        private const val FIXED_AUDIO_BYTES = 3L * 1024L * 1024L
        private const val STREAM_BUFFER_BYTES = 64L * 1024L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
