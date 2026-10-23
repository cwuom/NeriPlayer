package moe.ouom.neriplayer.core.player.privileged.coloros

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal interface ColorOsTransportChannel : AutoCloseable {
    fun exchange(request: String): String
}

internal class ColorOsTransportBackend(
    override val uid: Int,
    private val channel: ColorOsTransportChannel,
    private val timeoutMillis: Long = 15_000
) : ColorOsConfigBackend {
    private val mutex = Mutex()
    private val closed = AtomicBoolean()

    override suspend fun read(): ColorOsRusRow? = call("read") { json ->
        if (!json.has("row")) ColorOsTransportProtocol.malformed()
        if (json.isNull("row")) null else ColorOsTransportProtocol.row(json.optJSONObject("row") ?: ColorOsTransportProtocol.malformed())
    }

    override suspend fun compareAndSet(expected: ColorOsRusRow, replacement: ColorOsRusRow): Int =
        call("cas", expected, replacement) { json ->
            val count = json.opt("count") as? Int ?: ColorOsTransportProtocol.malformed()
            if (count < 0) ColorOsTransportProtocol.malformed()
            count
        }

    override suspend fun requestReload() = call("reload") { json ->
        if (json.opt("accepted") != true) ColorOsTransportProtocol.malformed()
    }

    private suspend fun <T> call(
        operation: String,
        expected: ColorOsRusRow? = null,
        replacement: ColorOsRusRow? = null,
        decode: (JSONObject) -> T
    ): T {
        // validation happens before submission, so a size error never implies a write
        val request = ColorOsTransportProtocol.encodeRequest(operation, expected, replacement)
        return mutex.withLock {
            if (closed.get()) throw ColorOsConfigException(ColorOsConfigStatus.ERROR, "transport_closed")
            val submitted = AtomicBoolean()
            try {
                val response = withTimeout(timeoutMillis) {
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { close() }
                        Dispatchers.IO.dispatch(continuation.context, Runnable {
                            if (continuation.isActive) {
                                submitted.set(true)
                                continuation.resumeWith(runCatching { channel.exchange(request) })
                            }
                        })
                    }
                }
                ColorOsTransportProtocol.checkSize(response)
                val json = ColorOsTransportProtocol.parse(response)
                if (json.opt("uid") != uid) ColorOsTransportProtocol.malformed()
                if (json.has("error")) {
                    val status = (json.opt("error") as? String)?.let { value ->
                        ColorOsConfigStatus.entries.firstOrNull { it.name == value }
                    } ?: ColorOsTransportProtocol.malformed()
                    val detail = json.opt("detail") as? String ?: ColorOsTransportProtocol.malformed()
                    if (!detail.matches(Regex("[a-z0-9_]{1,96}"))) ColorOsTransportProtocol.malformed()
                    throw ColorOsConfigException(status, detail)
                }
                decode(json)
            } catch (error: Throwable) {
                close()
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (error is ColorOsConfigException && error.detailCode != "protocol_malformed" && error.detailCode != "protocol_size") throw error
                val status = when {
                    error is SecurityException -> ColorOsConfigStatus.PERMISSION_DENIED
                    operation == "cas" && submitted.get() -> ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN
                    operation == "reload" -> ColorOsConfigStatus.RELOAD_FAILED
                    else -> ColorOsConfigStatus.ERROR
                }
                val detail = if (error is TimeoutCancellationException) "transport_timeout" else "transport_reply_failed"
                throw ColorOsConfigException(status, detail)
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { channel.close() }
    }
}
