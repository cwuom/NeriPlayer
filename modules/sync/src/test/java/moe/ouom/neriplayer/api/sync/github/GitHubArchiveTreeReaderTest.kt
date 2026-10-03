package moe.ouom.neriplayer.api.sync.github

import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubArchiveTreeReaderTest {
    private val owned = "neriplayer-sync-v4-${"a".repeat(64)}.zst"

    @Test
    fun `only regular root archive blobs are candidates after the whole listing is validated`() {
        val paths = listOf(owned, "neriplayer-sync-v3-${"b".repeat(64)}.zst")
        val tree = JSONArray()
        for (path in paths) tree.put(entry(path))
        for (path in listOf("folder/$owned", "neriplayer-sync-v4-${"A".repeat(64)}.zst", "backup-raw.bin", "notes.txt")) {
            tree.put(entry(path))
        }
        tree.put(entry("neriplayer-sync-v4-${"c".repeat(64)}.zst", "120000"))
        tree.put(entry("neriplayer-sync-v4-${"d".repeat(64)}.zst", "040000", "tree"))
        assertEquals(paths.toSet(), read(listing(tree)))
    }

    @Test
    fun `incomplete ambiguous or malformed tree listings never return deletion candidates`() {
        val valid = listing(JSONArray().put(entry(owned)))
        val invalid = listOf(
            valid.dropLast(1), valid + "{}", valid.replace("\"truncated\":false", "\"truncated\":true"),
            """{"sha":"base","tree":[]}""", """{"sha":"other","tree":[],"truncated":false}""",
            """{"sha":"base","truncated":false}""", """{"sha":"base","tree":null,"truncated":false}""",
            """{"sha":"base","tree":[],"truncated":"false"}""",
            """{"sha":"base","sha":"base","tree":[],"truncated":false}""",
            listing(JSONArray().put(entry(owned).removeField("mode"))),
            listing(JSONArray().put(entry(owned).put("sha", 12))),
            listing(JSONArray().put(entry(owned).put("sha", "not-an-object-id"))),
            listing(JSONArray().put(entry(owned).put("sha", "e".repeat(39)))),
            listing(JSONArray().put(entry(owned)).put(entry(owned)))
        )
        for (body in invalid) assertTrue(body.take(150), runCatching { read(body) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `required entry fields reject missing duplicate blank and nonstring values`() {
        for (field in listOf("path", "mode", "type", "sha")) {
            val missing = listing(JSONArray().put(entry(owned).removeField(field)))
            assertTrue(field, runCatching { read(missing) }.exceptionOrNull() is IOException)
            for (value in listOf("", "  ", 7, true, JSONObject.NULL)) {
                val invalid = listing(JSONArray().put(entry(owned).put(field, value)))
                assertTrue("$field=$value", runCatching { read(invalid) }.exceptionOrNull() is IOException)
            }
            val duplicate = entry(owned).toString().dropLast(1) + ",\"$field\":\"duplicate\"}"
            val invalid = """{"sha":"base","tree":[$duplicate],"truncated":false}"""
            assertTrue(field, runCatching { read(invalid) }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun `root fields reject duplicates and wrong types while unrelated API fields remain valid`() {
        val valid = listing(JSONArray().put(entry(owned).put("size", 123).put("url", "https://sync.test/blob")))
        val extended = JSONObject(valid).put("url", "https://sync.test/tree").put("extra", JSONArray().put(JSONObject().put("key", 1)))
        assertEquals(setOf(owned), read(extended.toString()))
        for (field in listOf("sha", "tree", "truncated")) {
            val original = JSONObject(valid)
            val duplicate = valid.dropLast(1) + "," + JSONObject().put(field, original.get(field)).toString().drop(1)
            assertTrue(field, runCatching { read(duplicate) }.exceptionOrNull() is IOException)
            assertTrue(field, runCatching { read(original.removeField(field).toString()) }.exceptionOrNull() is IOException)
        }
        for ((field, value) in listOf("sha" to " ", "sha" to 7, "tree" to JSONObject(), "truncated" to 0)) {
            val invalid = JSONObject(valid).put(field, value).toString()
            assertTrue(field, runCatching { read(invalid) }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun `entry budget applies to the complete listing including unrelated files`() {
        val entries = (0..100_000).joinToString(",") { index ->
            """{"path":"p$index","mode":"100644","type":"blob","sha":"x"}"""
        }
        val listing = """{"sha":"base","tree":[$entries],"truncated":false}"""
        val failure = runCatching { read(listing) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("GitHub archive tree contains too many entries", failure?.message)
    }

    @Test
    fun `response budget and cancellation close the owned call before returning candidates`() {
        val tooLarge = """{"padding":"${"x".repeat(12 * 1024 * 1024)}","sha":"base","tree":[],"truncated":false}"""
        val client = OkHttpClient()
        try {
            val call = client.newCall(Request.Builder().url("https://sync.test/tree").build())
            assertTrue(runCatching { GitHubArchiveTreeReader.readOwnedObjectPaths(tooLarge.toResponseBody(), call, "base") }.exceptionOrNull() is IOException)
            assertTrue(call.isCanceled())
            val chunked = client.newCall(Request.Builder().url("https://sync.test/tree").build())
            val unknownLengthBody = object : ResponseBody() {
                private val source = Buffer().writeUtf8(tooLarge)
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun source() = source
            }
            assertTrue(runCatching { GitHubArchiveTreeReader.readOwnedObjectPaths(unknownLengthBody, chunked, "base") }.exceptionOrNull() is IOException)
            assertTrue(chunked.isCanceled())
            val cancelled = CancellationException("cancel tree read")
            val second = client.newCall(Request.Builder().url("https://sync.test/tree").build())
            assertSame(cancelled, runCatching {
                GitHubArchiveTreeReader.readOwnedObjectPaths(listing(JSONArray()).toResponseBody(), second, "base") { throw cancelled }
            }.exceptionOrNull())
            assertTrue(second.isCanceled())
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun read(body: String): Set<String> {
        val client = OkHttpClient()
        return try {
            val call = client.newCall(Request.Builder().url("https://sync.test/tree").build())
            GitHubArchiveTreeReader.readOwnedObjectPaths(body.toResponseBody(), call, "base")
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun entry(path: String, mode: String = "100644", type: String = "blob") = JSONObject()
        .put("path", path).put("mode", mode).put("type", type).put("sha", "e".repeat(40))

    private fun listing(tree: JSONArray): String = JSONObject().put("sha", "base").put("tree", tree).put("truncated", false).toString()

    private fun JSONObject.removeField(name: String): JSONObject = apply { remove(name) }
}
