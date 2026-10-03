package moe.ouom.neriplayer.api.sync.github

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GitHubArchiveRefUpdateTest {
    @Test
    fun `maps standard and enterprise endpoints without inventing an unsafe fallback`() {
        assertEquals("https://api.github.com/graphql", GitHubArchiveRefUpdate.endpoint("https://api.github.com").toString())
        assertEquals("https://enterprise.test/api/graphql", GitHubArchiveRefUpdate.endpoint("https://enterprise.test/api/v3/").toString())
        assertEquals("https://enterprise.test/prefix/api/graphql", GitHubArchiveRefUpdate.endpoint("https://enterprise.test/prefix/api/v3").toString())
        assertTrue(runCatching { GitHubArchiveRefUpdate.endpoint("https://enterprise.test/unsupported") }.exceptionOrNull() is IOException)
    }

    @Test
    fun `mutation always includes expected object ID and disallows forced updates`() {
        val request = JSONObject(GitHubArchiveRefUpdate.requestBody("repository-node", GitHubSyncHead("sync/branch", "before"), "after"))
        assertTrue(request.getString("query").contains("updateRefs(input: \$input)"))
        val input = request.getJSONObject("variables").getJSONObject("input")
        assertEquals("repository-node", input.getString("repositoryId"))
        assertEquals("after", input.getString("clientMutationId"))
        val updates = input.getJSONArray("refUpdates")
        assertEquals(1, updates.length())
        val update = updates.getJSONObject(0)
        assertEquals("refs/heads/sync/branch", update.getString("name"))
        assertEquals("before", update.getString("beforeOid"))
        assertEquals("after", update.getString("afterOid"))
        assertFalse(update.getBoolean("force"))
    }

    @Test
    fun `repository node ID requires an actual nonempty string and strict JSON`() {
        assertEquals("repository-node", GitHubArchiveRefUpdate.repositoryId("""{"node_id":"repository-node"}"""))
        for (body in listOf("{}", "null", "[]", """{"node_id":null}""", """{"node_id":1}""",
            """{"node_id":{}}""", """{"node_id":" "}""", """{node_id:"repository-node"}""")) {
            assertTrue(body, runCatching { GitHubArchiveRefUpdate.repositoryId(body) }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun `only a matching mutation acknowledgement without errors can succeed`() {
        for (errors in listOf("", "\"errors\":[],")) {
            GitHubArchiveRefUpdate.validateResponse("{$errors\"data\":{\"updateRefs\":{\"clientMutationId\":\"commit\"}}}", "commit")
        }
        for (body in listOf("{}", """{"data":null}""", """{"data":{}}""", """{"data":{"updateRefs":null}}""",
            """{"data":{"updateRefs":{}}}""", """{"data":{"updateRefs":{"clientMutationId":7}}}""",
            """{"data":{"updateRefs":{"clientMutationId":"wrong"}}}""", """{"errors":null}""",
            """{"errors":{}}""", """{"errors":[null]}""", """{"errors":[{}]}""",
            """{"errors":[{"message":"error","type":7}]}""",
            """{"errors":[{"message":"error","extensions":null}]}""")) {
            assertTrue(body, runCatching { GitHubArchiveRefUpdate.validateResponse(body, "commit") }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun `partial data cannot hide typed limits or stale reference failures`() {
        val errors = listOf(
            """{"type":"RATE_LIMITED","message":"quota reached"}""" to (true to false),
            """{"extensions":{"code":"RATE_LIMITED"},"message":"quota reached"}""" to (true to false),
            """{"message":"secondary rate limit"}""" to (true to false),
            """{"message":"abuse detection"}""" to (true to false),
            """{"type":"STALE_DATA","message":"beforeOid does not match"}""" to (false to true),
            """{"extensions":{"code":"STALE_DATA"},"message":"beforeOid does not match"}""" to (false to true),
            """{"type":null,"extensions":{"code":null},"message":"Permission denied"}""" to (false to false)
        )
        for ((error, flags) in errors) {
            val body = "{\"errors\":[$error],\"data\":{\"updateRefs\":{\"clientMutationId\":\"commit\"}}}"
            val failure = runCatching { GitHubArchiveRefUpdate.validateResponse(body, "commit") }.exceptionOrNull() as GitHubGraphQlException
            assertEquals(flags.first, failure.rateLimited)
            assertEquals(flags.second, failure.staleReference)
        }
    }
}
