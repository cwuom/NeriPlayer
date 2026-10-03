package moe.ouom.neriplayer.api.sync.github

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.Call
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.io.InputStreamReader

internal object GitHubTreeResponseReader {
    private const val MAX_PREFIX_BYTES = 64L * 1024L

    fun readSha(body: ResponseBody, call: Call): String {
        val prefix = PrefixSource(body.source()).buffer()
        return JsonReader(InputStreamReader(prefix.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.setStrictness(Strictness.STRICT)
            try {
                readTopLevelSha(reader)
            } catch (error: IllegalStateException) {
                throw IOException("Invalid GitHub sync tree response", error)
            } finally {
                // 树列表会随备份累积，取到树 SHA 后先断开，避免关闭正文继续排空列表
                call.cancel()
            }
        }
    }

    private fun readTopLevelSha(reader: JsonReader): String {
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "sha") return readShaString(reader)
            reader.skipValue()
        }
        throw IOException("GitHub sync tree response has no top-level SHA")
    }

    private fun readShaString(reader: JsonReader): String {
        if (reader.peek() != JsonToken.STRING) throw IOException("GitHub sync tree SHA must be a string")
        val sha = reader.nextString()
        if (sha.isBlank()) throw IOException("GitHub sync tree response has an empty SHA")
        return sha
    }

    private class PrefixSource(delegate: Source) : ForwardingSource(delegate) {
        private var consumed = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount == 0L) return 0L
            if (consumed == MAX_PREFIX_BYTES) throw IOException("GitHub sync tree SHA exceeds response prefix budget")
            val count = super.read(sink, minOf(byteCount, MAX_PREFIX_BYTES - consumed))
            if (count > 0L) consumed += count
            return count
        }
    }
}
