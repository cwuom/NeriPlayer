package moe.ouom.neriplayer.api.sync.webdav

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.Charset
import moe.ouom.neriplayer.api.sync.http.SyncResponseBodyReader
import okhttp3.Response
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.xml.sax.InputSource

internal object WebDavXmlResponse {
    private val byteOrderMarks = mapOf(
        "0000feff".decodeHex() to "UTF-32BE",
        "fffe0000".decodeHex() to "UTF-32LE",
        "efbbbf".decodeHex() to "UTF-8",
        "feff".decodeHex() to "UTF-16BE",
        "fffe".decodeHex() to "UTF-16LE"
    )
    private val xmlSignatures = mapOf(
        "0000003c".decodeHex() to "UTF-32BE",
        "3c000000".decodeHex() to "UTF-32LE",
        "003c003f".decodeHex() to "UTF-16BE",
        "3c003f00".decodeHex() to "UTF-16LE"
    )
    private val declarationEncoding = Regex("^<\\?xml\\s+[^?]*?\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1")

    fun read(response: Response, maxBytes: Int = SyncResponseBodyReader.MAX_SYNC_FILE_BYTES): InputSource {
        val bytes = SyncResponseBodyReader.read(response.body, maxBytes)
        val (charset, offset) = inputEncoding(bytes, response.body.contentType()?.parameter("charset"))
        val xml = String(bytes, offset, bytes.size - offset, charset)
        // 与解析器使用相同编码，避免 UTF16 等响应绕过 DTD 检查
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
            throw IOException("Invalid WebDAV directory response: DTD is not allowed")
        }
        return InputSource(ByteArrayInputStream(bytes, offset, bytes.size - offset)).apply {
            this.encoding = charset.name()
        }
    }

    private fun inputEncoding(bytes: ByteArray, httpEncoding: String?): Pair<Charset, Int> {
        val prefix = bytes.toByteString(0, minOf(4, bytes.size))
        val bom = byteOrderMarks.entries.firstOrNull { prefix.startsWith(it.key) }
        // MIME XML 的编码优先级是 BOM、HTTP charset、XML 声明
        val encoding = bom?.value ?: httpEncoding
            ?: xmlSignatures.entries.firstOrNull { prefix.startsWith(it.key) }?.value
            ?: declaredEncoding(bytes) ?: "UTF-8"
        return charset(encoding) to (bom?.key?.size ?: 0)
    }

    private fun declaredEncoding(bytes: ByteArray): String? =
        declarationEncoding.find(bytes.toString(Charsets.ISO_8859_1))?.groupValues?.get(2)

    private fun charset(encoding: String): Charset = try {
        Charset.forName(encoding)
    } catch (error: IllegalArgumentException) {
        throw IOException("Unsupported WebDAV XML encoding: $encoding", error)
    }
}
