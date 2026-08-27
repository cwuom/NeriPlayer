package moe.ouom.neriplayer.core.player.engine.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.TrafficStatsRepository
import moe.ouom.neriplayer.data.traffic.TrafficUsageSource

@UnstableApi
internal class TrafficCountingHttpDataSource(
    private val delegate: HttpDataSource,
    private val trafficStatsRepository: TrafficStatsRepository,
    private val usageSource: TrafficUsageSource = TrafficUsageSource.PLAYBACK
) : HttpDataSource {
    private val trafficLock = Any()

    @Volatile
    private var active = false
    private var accumulator = newAccumulator()
    private var neteaseFlacDiagnostic: NeteaseFlacStreamDiagnostic? = null

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val nextAccumulator = newAccumulator()
        val length = delegate.open(dataSpec)
        val diagnostic = buildNeteaseFlacDiagnostic(dataSpec, length)
        synchronized(trafficLock) {
            accumulator.flush()
            accumulator = nextAccumulator
            active = true
            neteaseFlacDiagnostic = diagnostic
        }
        diagnostic?.let(::logNeteaseFlacOpen)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val readAccumulator = synchronized(trafficLock) {
            accumulator.takeIf { active }
        }
        val read = delegate.read(buffer, offset, length)
        if (readAccumulator != null && read > 0) {
            val signatureToLog = synchronized(trafficLock) {
                readAccumulator.add(read.toLong())
                neteaseFlacDiagnostic?.let { diagnostic ->
                    diagnostic.bytesRead += read
                    if (!diagnostic.firstPayloadSignatureLogged) {
                        diagnostic.firstPayloadSignatureLogged = true
                        diagnostic.firstPayloadSignature = flacPayloadSignature(buffer, offset, read)
                        diagnostic
                    } else {
                        null
                    }
                }
            }
            signatureToLog?.let(::logNeteaseFlacPayloadSignature)
        } else if (read == C.RESULT_END_OF_INPUT) {
            val diagnostic = synchronized(trafficLock) {
                neteaseFlacDiagnostic?.takeUnless { it.endOfInputLogged }?.also {
                    it.endOfInputLogged = true
                }
            }
            diagnostic?.let(::logNeteaseFlacEndOfInput)
        }
        return read
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        var diagnostic: NeteaseFlacStreamDiagnostic? = null
        try {
            delegate.close()
        } finally {
            synchronized(trafficLock) {
                active = false
                accumulator.flush()
                diagnostic = neteaseFlacDiagnostic
                neteaseFlacDiagnostic = null
            }
            diagnostic?.takeUnless { it.endOfInputLogged }?.let(::logNeteaseFlacClose)
        }
    }

    override fun setRequestProperty(name: String, value: String) {
        delegate.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        delegate.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        delegate.clearAllRequestProperties()
    }

    override fun getResponseCode(): Int = delegate.responseCode

    private fun newAccumulator(): TrafficByteAccumulator {
        val networkType: TrafficNetworkType = trafficStatsRepository.currentNetworkType()
        return TrafficByteAccumulator {
            trafficStatsRepository.recordNetworkBytes(
                networkType = networkType,
                bytes = it,
                source = usageSource
            )
        }
    }

    private fun buildNeteaseFlacDiagnostic(
        dataSpec: DataSpec,
        openedLength: Long
    ): NeteaseFlacStreamDiagnostic? {
        val uri = delegate.uri ?: dataSpec.uri
        val host = uri.host?.lowercase() ?: return null
        val responseHeaders = delegate.responseHeaders
        val contentType = responseHeaders.headerValue("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        val isNeteaseStream = host == "music.126.net" || host.endsWith(".music.126.net")
        val isFlac = uri.path?.lowercase()?.endsWith(".flac") == true ||
            contentType == "audio/flac" ||
            contentType == "audio/x-flac"
        if (!isNeteaseStream || !isFlac) return null
        return NeteaseFlacStreamDiagnostic(
            host = host,
            requestPosition = dataSpec.position,
            requestLength = dataSpec.length,
            openedLength = openedLength,
            responseCode = delegate.responseCode,
            contentType = contentType,
            contentLength = responseHeaders.headerValue("Content-Length"),
            contentRange = responseHeaders.headerValue("Content-Range")
        )
    }

    private fun logNeteaseFlacOpen(diagnostic: NeteaseFlacStreamDiagnostic) {
        NPLogger.d(
            "NERI-PlaybackHttp",
            "Netease FLAC stream open: host=${diagnostic.host}, position=${diagnostic.requestPosition}, " +
                "requestedLength=${diagnostic.requestLength}, openedLength=${diagnostic.openedLength}, " +
                "responseCode=${diagnostic.responseCode}, contentType=${diagnostic.contentType}, " +
                "contentLength=${diagnostic.contentLength}, contentRange=${diagnostic.contentRange}"
        )
    }

    private fun logNeteaseFlacPayloadSignature(diagnostic: NeteaseFlacStreamDiagnostic) {
        NPLogger.d(
            "NERI-PlaybackHttp",
            "Netease FLAC payload signature: host=${diagnostic.host}, " +
                "position=${diagnostic.requestPosition}, signature=${diagnostic.firstPayloadSignature}"
        )
    }

    private fun logNeteaseFlacEndOfInput(diagnostic: NeteaseFlacStreamDiagnostic) {
        NPLogger.w(
            "NERI-PlaybackHttp",
            "Netease FLAC stream EOF: host=${diagnostic.host}, position=${diagnostic.requestPosition}, " +
                "openedLength=${diagnostic.openedLength}, bytesRead=${diagnostic.bytesRead}, " +
                "responseCode=${diagnostic.responseCode}, contentLength=${diagnostic.contentLength}, " +
                "contentRange=${diagnostic.contentRange}"
        )
    }

    private fun logNeteaseFlacClose(diagnostic: NeteaseFlacStreamDiagnostic) {
        NPLogger.d(
            "NERI-PlaybackHttp",
            "Netease FLAC stream closed before EOF: host=${diagnostic.host}, " +
                "position=${diagnostic.requestPosition}, bytesRead=${diagnostic.bytesRead}, " +
                "openedLength=${diagnostic.openedLength}"
        )
    }
}

private data class NeteaseFlacStreamDiagnostic(
    val host: String,
    val requestPosition: Long,
    val requestLength: Long,
    val openedLength: Long,
    val responseCode: Int,
    val contentType: String?,
    val contentLength: String?,
    val contentRange: String?,
    var bytesRead: Long = 0L,
    var firstPayloadSignature: String? = null,
    var firstPayloadSignatureLogged: Boolean = false,
    var endOfInputLogged: Boolean = false
)

private fun flacPayloadSignature(buffer: ByteArray, offset: Int, length: Int): String {
    if (length < 4) return "short_read"
    return if (
        buffer[offset] == 'f'.code.toByte() &&
        buffer[offset + 1] == 'L'.code.toByte() &&
        buffer[offset + 2] == 'a'.code.toByte() &&
        buffer[offset + 3] == 'C'.code.toByte()
    ) {
        "fLaC"
    } else {
        "other"
    }
}

private fun Map<String, List<String>>.headerValue(name: String): String? {
    return entries.firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
        ?.value
        ?.joinToString(",")
        ?.takeIf { it.isNotBlank() }
}
