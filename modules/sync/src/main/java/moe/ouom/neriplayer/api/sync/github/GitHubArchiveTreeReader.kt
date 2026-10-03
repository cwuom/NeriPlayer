package moe.ouom.neriplayer.api.sync.github

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStreamReader
import okhttp3.Call
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer

internal object GitHubArchiveTreeReader {
    private const val MAX_RESPONSE_BYTES = 12L * 1024L * 1024L
    private const val MAX_ENTRIES = 100_000
    private val objectPath = Regex("neriplayer-sync-v[34]-[0-9a-f]{64}\\.zst")
    private val objectId = Regex("[0-9a-f]{40}")
    private val listingFields = setOf("sha", "tree", "truncated")
    private val entryFields = setOf("path", "mode", "type", "sha")

    fun isOwnedObjectPath(path: String): Boolean = objectPath.matches(path)

    fun readOwnedObjectPaths(
        body: ResponseBody,
        call: Call,
        expectedTreeSha: String,
        checkActive: () -> Unit = {}
    ): Set<String> {
        try {
            checkActive()
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("GitHub archive tree response is too large")
            val source = LimitedSource(body.source(), checkActive).buffer()
            return JsonReader(InputStreamReader(source.inputStream(), Charsets.UTF_8)).use { reader ->
                try {
                    reader.setStrictness(Strictness.STRICT)
                    readListing(reader, expectedTreeSha, checkActive)
                } catch (error: Exception) {
                    // 关闭正文前先断开，失败的列表不能继续排空
                    call.cancel()
                    throw error
                }
            }
        } catch (cancelled: CancellationException) {
            call.cancel()
            throw cancelled
        } catch (error: Exception) {
            call.cancel()
            if (error is IllegalStateException) throw IOException("Invalid GitHub archive tree response", error)
            throw error
        }
    }

    private fun readListing(reader: JsonReader, expectedSha: String, checkActive: () -> Unit): Set<String> {
        val fields = HashSet<String>()
        val listing = Listing()
        reader.beginObject()
        while (reader.hasNext()) {
            checkActive()
            val name = reader.nextName()
            if (name !in listingFields) reader.skipValue() else {
                if (!fields.add(name)) throw IOException("Duplicate GitHub archive tree field")
                listing.readField(reader, name, checkActive)
            }
        }
        reader.endObject()
        val paths = listing.complete(expectedSha)
        if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing GitHub archive tree content")
        checkActive()
        return paths
    }

    private class Listing {
        private var sha: String? = null
        private var paths: Set<String>? = null
        private var truncated: Boolean? = null

        fun readField(reader: JsonReader, name: String, checkActive: () -> Unit) {
            when (name) {
                "sha" -> sha = readString(reader)
                "tree" -> paths = readEntries(reader, checkActive)
                else -> truncated = readTruncated(reader)
            }
        }

        fun complete(expectedSha: String): Set<String> {
            if (sha != expectedSha || truncated != false) throw IOException("GitHub archive tree listing is incomplete")
            return paths ?: throw IOException("GitHub archive tree listing has no entries")
        }
    }

    private fun readTruncated(reader: JsonReader): Boolean {
        if (reader.peek() != JsonToken.BOOLEAN) throw IOException("Invalid GitHub archive tree truncation flag")
        return reader.nextBoolean()
    }

    private fun readEntries(reader: JsonReader, checkActive: () -> Unit): Set<String> {
        val seen = HashSet<String>()
        val owned = LinkedHashSet<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            checkActive()
            if (seen.size == MAX_ENTRIES) throw IOException("GitHub archive tree contains too many entries")
            val entry = readEntry(reader)
            val path = entry.getValue("path")
            if (!seen.add(path)) throw IOException("Duplicate GitHub archive tree path")
            if (entry.getValue("type") == "blob" && entry.getValue("mode") == "100644" && isOwnedObjectPath(path)) {
                if (!objectId.matches(entry.getValue("sha"))) throw IOException("Invalid GitHub archive object ID")
                owned += path
            }
        }
        reader.endArray()
        return owned
    }

    private fun readEntry(reader: JsonReader): Map<String, String> {
        val fields = HashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name !in entryFields) reader.skipValue() else {
                if (fields.put(name, readString(reader)) != null) throw IOException("Duplicate GitHub archive tree entry field")
            }
        }
        reader.endObject()
        if (fields.size != 4) throw IOException("GitHub archive tree entry is incomplete")
        return fields
    }

    private fun readString(reader: JsonReader): String {
        if (reader.peek() != JsonToken.STRING) throw IOException("GitHub archive tree field must be a string")
        val value = reader.nextString()
        if (value.isBlank()) throw IOException("GitHub archive tree field is empty")
        return value
    }

    private class LimitedSource(delegate: Source, private val checkActive: () -> Unit) : ForwardingSource(delegate) {
        private var consumed = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            checkActive()
            val count = super.read(sink, minOf(byteCount, MAX_RESPONSE_BYTES - consumed + 1L))
            if (count > 0L) consumed += count
            if (consumed > MAX_RESPONSE_BYTES) throw IOException("GitHub archive tree response is too large")
            return count
        }
    }
}
