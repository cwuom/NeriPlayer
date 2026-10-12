package moe.ouom.neriplayer.api.sync.github

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

class GitHubArchiveCommitTest {
    @Test
    fun `one mutation binds binary additions and deletions to the expected branch head`() {
        val binary = ByteArray(256) { it.toByte() }
        val addition = archiveObject(binary)
        val deletion = archiveObject(byteArrayOf(9)).first
        val request = JSONObject(requireNotNull(request(files = listOf(addition, manifest()), deletions = setOf(deletion))))
        val query = request.getString("query").replace(Regex("\\s+"), "")
        assertTrue(query.contains("createCommitOnBranch(input:\$input)"))
        assertTrue(query.contains("commit{oidparents(first:2){nodes{oid}}}"))
        assertTrue(query.contains("ref{nameprefixrepository{nameWithOwner}target{oid}}"))
        assertTrue(query.contains("clientMutationId"))
        val input = request.getJSONObject("variables").getJSONObject("input")
        val branch = input.getJSONObject("branch")
        assertEquals("owner/repo", branch.getString("repositoryNameWithOwner"))
        assertEquals("sync/branch", branch.getString("branchName"))
        assertFalse(branch.has("id"))
        assertFalse(branch.has("refName"))
        assertEquals(BEFORE, input.getString("expectedHeadOid"))
        assertEquals(MUTATION_ID, input.getString("clientMutationId"))
        assertEquals("sync", input.getJSONObject("message").getString("headline"))
        assertFalse(input.has("force"))
        val changes = input.getJSONObject("fileChanges")
        assertEquals(2, changes.getJSONArray("additions").length())
        val file = changes.getJSONArray("additions").getJSONObject(0)
        assertEquals(addition.first, file.getString("path"))
        assertEquals(Base64.getEncoder().encodeToString(binary), file.getString("contents"))
        assertArrayEquals(binary, Base64.getDecoder().decode(file.getString("contents")))
        assertEquals(deletion, changes.getJSONArray("deletions").getJSONObject(0).getString("path"))
    }

    @Test
    fun `client item budget counts additions and deletions together`() {
        assertEquals(64, GitHubArchiveCommit.MAX_FILES)
        val files = (0 until 62).map { archiveObject(byteArrayOf(it.toByte())) } + manifest()
        val firstDeletion = archiveObject(byteArrayOf(100)).first
        val secondDeletion = archiveObject(byteArrayOf(101)).first
        assertNotNull(request(files = files, deletions = setOf(firstDeletion)))
        assertNull(request(files = files, deletions = setOf(firstDeletion, secondDeletion)))
        assertNull(request(files = (0 until 64).map { archiveObject(byteArrayOf(it.toByte())) } + manifest()))
    }

    @Test
    fun `client content budget falls back before building an oversized mutation`() {
        assertEquals(3 * 1024 * 1024, GitHubArchiveCommit.MAX_CONTENT_BYTES)
        val files = listOf(archiveObject(ByteArray(2 * 1024 * 1024) { 1 }),
            archiveObject(ByteArray(1024 * 1024) { 2 }), manifest())
        assertNull(request(files = files))
        assertNotNull(request(files = listOf(files.first(), manifest())))
    }

    @Test
    fun `request budget measures the complete UTF8 JSON including message bytes`() {
        assertEquals(4 * 1024 * 1024, GitHubArchiveCommit.MAX_REQUEST_BYTES)
        val baseline = requireNotNull(request(message = "a"))
        val remaining = GitHubArchiveCommit.MAX_REQUEST_BYTES - baseline.toByteArray(Charsets.UTF_8).size
        val message = "a" + "中".repeat(remaining / 3) + "x".repeat(remaining % 3)
        val exact = requireNotNull(request(message = message))
        assertEquals(GitHubArchiveCommit.MAX_REQUEST_BYTES, exact.toByteArray(Charsets.UTF_8).size)
        assertNull(request(message = message + "中"))
    }

    @Test
    fun `request JSON escapes branch and message values without changing them`() {
        val branch = "sync/branch\"value"
        val message = "sync\n\"quoted\"\\value"
        val body = GitHubArchiveCommit.requestBody("owner", "repo", GitHubSyncHead(branch, BEFORE), message,
            listOf(manifest()), emptySet(), MUTATION_ID)
        val input = JSONObject(requireNotNull(body)).getJSONObject("variables").getJSONObject("input")
        assertEquals(branch, input.getJSONObject("branch").getString("branchName"))
        assertEquals(message, input.getJSONObject("message").getString("headline"))
    }

    @Test
    fun `only canonical validated archive additions and owned deletions are accepted`() {
        val item = archiveObject(byteArrayOf(1))
        for (files in listOf(listOf("../${item.first}" to item.second), listOf("unrelated.txt" to item.second),
            listOf(item.first to byteArrayOf(2)), listOf(MANIFEST to ByteArray(0)),
            listOf(archiveObject(ByteArray(2 * 1024 * 1024 + 1))))) {
            assertTrue(runCatching { request(files = files) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (path in listOf(MANIFEST, "unrelated.txt", "../${item.first}", "directory/${item.first}")) {
            assertTrue(path, runCatching { request(deletions = setOf(path)) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `duplicate and overlapping paths cannot produce a mutation`() {
        val item = archiveObject(byteArrayOf(1))
        assertTrue(runCatching { request(files = listOf(item, item, manifest())) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { request(files = listOf(item, manifest()), deletions = setOf(item.first)) }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `matching commit parent and branch acknowledgement returns the new object ID`() {
        assertEquals(AFTER, validate(response()))
        assertEquals(AFTER, validate(response().put("errors", JSONArray())))
        assertEquals(AFTER, validate(response().apply { ref().getJSONObject("repository").put("nameWithOwner", "OWNER/Repo") }))
    }

    @Test
    fun `acknowledgement must be a matching string`() {
        for (value in listOf<Any>(JSONObject.NULL, 1, "", "wrong", JSONObject())) {
            rejects(response().apply { payload().put("clientMutationId", value) })
        }
        rejects(response().apply { payload().remove("clientMutationId") })
    }

    @Test
    fun `new commit and target object IDs must be complete lowercase SHA1 values`() {
        for (value in listOf<Any>(JSONObject.NULL, 1, "", "a".repeat(39), "g".repeat(40), "A".repeat(40), JSONObject())) {
            rejects(response().apply { commit().put("oid", value) })
            rejects(response().apply { ref().getJSONObject("target").put("oid", value) })
        }
        rejects(response().apply { ref().getJSONObject("target").put("oid", BEFORE) })
    }

    @Test
    fun `a commit cannot acknowledge the unchanged head as its own parent`() {
        rejects(response().apply {
            commit().put("oid", BEFORE)
            ref().getJSONObject("target").put("oid", BEFORE)
        })
    }

    @Test
    fun `exactly one parent must acknowledge the expected immutable head`() {
        for (nodes in listOf(JSONArray(), JSONArray().put(JSONObject().put("oid", AFTER)),
            JSONArray().put(JSONObject().put("oid", BEFORE)).put(JSONObject().put("oid", AFTER)),
            JSONArray().put(JSONObject().put("oid", 1)), JSONArray().put(JSONObject.NULL),
            JSONArray().put(JSONObject()))) {
            rejects(response().apply { commit().getJSONObject("parents").put("nodes", nodes) })
        }
        for (value in listOf<Any>(JSONObject.NULL, JSONObject(), 1)) {
            rejects(response().apply { commit().getJSONObject("parents").put("nodes", value) })
        }
        rejects(response().apply { commit().remove("parents") })
    }

    @Test
    fun `acknowledged ref must match branch prefix and repository`() {
        for ((field, value) in listOf("name" to "other", "name" to "SYNC/branch", "prefix" to "refs/tags/", "prefix" to "")) {
            rejects(response().apply { ref().put(field, value) })
        }
        for (value in listOf<Any>("owner/other", "other/repo", "owner/repo/extra", JSONObject.NULL, 1)) {
            rejects(response().apply { ref().getJSONObject("repository").put("nameWithOwner", value) })
        }
        for (field in listOf("name", "prefix", "repository", "target")) {
            rejects(response().apply { ref().remove(field) })
            rejects(response().apply { ref().put(field, JSONObject.NULL) })
            rejects(response().apply { ref().put(field, 1) })
        }
    }

    @Test
    fun `missing malformed and trailing response data cannot acknowledge publication`() {
        for (body in listOf("null", "[]", "{}", "{data:{}}", response().toString() + "{}",
            """{"data":null}""", """{"data":{"createCommitOnBranch":null}}""")) {
            assertTrue(body, runCatching { GitHubArchiveCommit.validateResponse(body, MUTATION_ID, "owner", "repo", HEAD) }
                .exceptionOrNull() is IOException)
        }
        for (field in listOf("commit", "ref")) {
            rejects(response().apply { payload().remove(field) })
            rejects(response().apply { payload().put(field, JSONObject.NULL) })
            rejects(response().apply { payload().put(field, JSONArray()) })
        }
    }

    @Test
    fun `errors take precedence over complete or malformed success data and preserve classification`() {
        val errors = listOf(
            """{"type":"RATE_LIMITED","message":"quota reached"}""" to (true to false),
            """{"extensions":{"code":"RATE_LIMITED"},"message":"quota reached"}""" to (true to false),
            """{"message":"secondary rate limit"}""" to (true to false),
            """{"message":"abuse detection"}""" to (true to false),
            """{"type":"STALE_DATA","message":"expected head does not match"}""" to (false to true),
            """{"extensions":{"code":"STALE_DATA"},"message":"expected head does not match"}""" to (false to true),
            """{"type":"FORBIDDEN","message":"permission denied"}""" to (false to false)
        )
        for ((error, flags) in errors) {
            for (body in listOf(response(), JSONObject().put("data", JSONObject.NULL))) {
                body.put("errors", JSONArray().put(JSONObject(error)))
                val failure = runCatching { validate(body) }.exceptionOrNull()
                assertTrue(failure is GitHubGraphQlException)
                failure as GitHubGraphQlException
                assertEquals(flags.first, failure.rateLimited)
                assertEquals(flags.second, failure.staleReference)
            }
        }
    }

    @Test
    fun `malformed error envelopes cannot be mistaken for success`() {
        for (value in listOf<Any>(JSONObject.NULL, JSONObject(), 1, JSONArray().put(JSONObject.NULL),
            JSONArray().put(JSONObject()), JSONArray().put(JSONObject().put("message", "error").put("type", 1)),
            JSONArray().put(JSONObject().put("message", "error").put("extensions", JSONObject.NULL)))) {
            rejects(response().put("errors", value))
        }
    }

    private fun request(
        files: List<Pair<String, ByteArray>> = listOf(manifest()),
        deletions: Set<String> = emptySet(),
        message: String = "sync"
    ): String? = GitHubArchiveCommit.requestBody("owner", "repo", HEAD, message, files, deletions, MUTATION_ID)

    private fun validate(body: JSONObject): String =
        GitHubArchiveCommit.validateResponse(body.toString(), MUTATION_ID, "owner", "repo", HEAD)

    private fun rejects(body: JSONObject) {
        assertTrue(body.toString(), runCatching { validate(body) }.exceptionOrNull() is IOException)
    }

    private fun response(): JSONObject = JSONObject().put("data", JSONObject().put("createCommitOnBranch", JSONObject()
        .put("clientMutationId", MUTATION_ID)
        .put("commit", JSONObject().put("oid", AFTER).put("parents", JSONObject()
            .put("nodes", JSONArray().put(JSONObject().put("oid", BEFORE)))))
        .put("ref", JSONObject().put("name", HEAD.branch).put("prefix", "refs/heads/")
            .put("repository", JSONObject().put("nameWithOwner", "owner/repo"))
            .put("target", JSONObject().put("oid", AFTER)))))

    private fun JSONObject.payload(): JSONObject = getJSONObject("data").getJSONObject("createCommitOnBranch")
    private fun JSONObject.commit(): JSONObject = payload().getJSONObject("commit")
    private fun JSONObject.ref(): JSONObject = payload().getJSONObject("ref")
    private fun manifest(): Pair<String, ByteArray> = MANIFEST to byteArrayOf(1, 2, 3)
    private fun archiveObject(content: ByteArray): Pair<String, ByteArray> {
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        return "neriplayer-sync-v4-$hash.zst" to content
    }

    private companion object {
        const val BEFORE = "1111111111111111111111111111111111111111"
        const val AFTER = "2222222222222222222222222222222222222222"
        const val MUTATION_ID = "archive-mutation"
        const val MANIFEST = "neriplayer-sync-v3.manifest"
        val HEAD = GitHubSyncHead("sync/branch", BEFORE)
    }
}
