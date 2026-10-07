@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

internal fun mockHttpUri(url: String): Uri {
    val parsed = URI(url)
    return mock(Uri::class.java).also { uri ->
        `when`(uri.scheme).thenReturn(parsed.scheme)
        `when`(uri.host).thenReturn(parsed.host)
        `when`(uri.path).thenReturn(parsed.path)
        `when`(uri.toString()).thenReturn(url)
    }
}

internal fun httpGet(
    uri: Uri,
    position: Long = 0L,
    length: Long = C.LENGTH_UNSET.toLong(),
    headers: Map<String, String> = emptyMap()
): DataSpec = DataSpec.Builder()
    .setUri(uri)
    .setPosition(position)
    .setLength(length)
    .setHttpRequestHeaders(headers)
    .build()

internal fun DataSource.readToEnd(chunkSize: Int = 64): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(chunkSize)
    while (true) {
        val read = read(buffer, 0, buffer.size)
        if (read == C.RESULT_END_OF_INPUT) return output.toByteArray()
        output.write(buffer, 0, read)
    }
}

internal fun rangeFailure(code: Int, spec: DataSpec) = HttpDataSource.InvalidResponseCodeException(
    code,
    null,
    null,
    emptyMap(),
    spec,
    ByteArray(0)
)

internal class ScriptedHttpResponse(
    val code: Int = 200,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val openLength: Long = body.size.toLong(),
    val failure: IOException? = null
)

/** Serves one scripted response per opened connection and records every request it saw. */
internal class ScriptedHttpServer(
    private val respond: (DataSpec) -> ScriptedHttpResponse
) : HttpDataSource.Factory {
    val requests = mutableListOf<DataSpec>()
    val connections = mutableListOf<Connection>()
    var closeFailure: IOException? = null

    override fun createDataSource(): Connection = Connection().also(connections::add)

    override fun setDefaultRequestProperties(
        defaultRequestProperties: Map<String, String>
    ): HttpDataSource.Factory = this

    inner class Connection : HttpDataSource {
        private var response: ScriptedHttpResponse? = null
        private var openedUri: Uri? = null
        private var readOffset = 0
        val clearedProperties = mutableListOf<String>()
        var closeCount = 0
            private set

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            requests += dataSpec
            val reply = respond(dataSpec)
            reply.failure?.let { throw it }
            response = reply
            openedUri = dataSpec.uri
            readOffset = 0
            return reply.openLength
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val body = response?.body ?: return C.RESULT_END_OF_INPUT
            if (readOffset >= body.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, body.size - readOffset)
            body.copyInto(buffer, offset, readOffset, readOffset + count)
            readOffset += count
            return count
        }

        override fun getUri(): Uri? = openedUri

        override fun getResponseHeaders(): Map<String, List<String>> = response?.headers.orEmpty()

        override fun getResponseCode(): Int = response?.code ?: -1

        override fun close() {
            closeCount += 1
            closeFailure?.let { throw it }
        }

        override fun setRequestProperty(name: String, value: String) = Unit

        override fun clearRequestProperty(name: String) {
            clearedProperties += name
        }

        override fun clearAllRequestProperties() = Unit
    }
}
