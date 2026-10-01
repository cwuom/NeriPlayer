package moe.ouom.neriplayer.listentogether.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

const val LISTEN_TOGETHER_MAX_HTTP_RESPONSE_BYTES = 2 * 1024 * 1024

fun readListenTogetherResponse(
    input: InputStream,
    contentLength: Long,
    charset: Charset = Charsets.UTF_8
): String = input.use {
    checkResponseSize(contentLength)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = it.read(buffer)
        if (read == -1) break
        total += read
        checkResponseSize(total)
        output.write(buffer, 0, read)
    }
    output.toByteArray().toString(charset)
}

private fun checkResponseSize(size: Long) {
    if (size > LISTEN_TOGETHER_MAX_HTTP_RESPONSE_BYTES) {
        throw IOException("ListenTogether response too large: $size bytes")
    }
}
