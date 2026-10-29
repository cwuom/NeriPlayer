package moe.ouom.neriplayer.lanltw.core

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.lanltw.protocol.LanCodec
import java.util.concurrent.Executors

/** Owns one JS runtime and its FIFO sample window. All native calls stay on one thread. */
class LanNtpEstimator {
    data class Estimate(val offset: Double, val rtt: Double, val synced: Boolean)
    private val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "lan-beatsync").apply { isDaemon = true } }.asCoroutineDispatcher()
    private var runtime: BeatSyncRuntime? = null
    private val samples = ArrayDeque<JsonObject>()
    private fun engine(): BeatSyncRuntime {
        runtime?.let { return it }
        val source = readSource()
        return BeatSyncRuntime(source).also { runtime = it }
    }
    private fun readSource(): String = checkNotNull(LanNtpEstimator::class.java.classLoader.getResourceAsStream("beatsync/core.js")).bufferedReader().use { it.readText() }
    private fun invoke(request: JsonObject): JsonElement = Json.parseToJsonElement(engine().invoke(JsonPrimitive(request.toString()).toString()))
    suspend fun reset() = withContext(dispatcher) {
        samples.clear(); invoke(buildJsonObject { put("op", "reset") }); Unit
    }
    suspend fun response(message: LanMessage, receivedAt: Double): Estimate? = withContext(dispatcher) {
        val sample = invoke(buildJsonObject { put("op", "response"); put("now", receivedAt); put("response", Json.parseToJsonElement(LanCodec.encode(message))) })
        if (sample !is JsonObject) return@withContext null
        val delay = sample.getValue("roundTripDelay").jsonPrimitive.double
        if (!delay.isFinite() || delay < 0) return@withContext null
        if (samples.size == 16) samples.removeFirst()
        samples.addLast(sample)
        val result = invoke(buildJsonObject { put("op", "estimate"); put("measurements", JsonArray(samples.toList())) }).jsonObject
        Estimate(result.getValue("averageOffset").jsonPrimitive.double, result.getValue("averageRoundTrip").jsonPrimitive.double, samples.size == 16)
    }
    suspend fun close() {
        withContext(dispatcher) { runtime?.close(); runtime = null; samples.clear() }
        dispatcher.close()
    }
}
