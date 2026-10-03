package moe.ouom.neriplayer.api.sync.github

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class GitHubGraphQlException(
    val rateLimited: Boolean,
    val staleReference: Boolean,
    message: String
) : IOException(message)

internal object GitHubArchiveRefUpdate {
    const val MAX_RESPONSE_BYTES = 64 * 1024
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private const val MUTATION = "mutation NeriPlayerSyncPublish(\$input: UpdateRefsInput!) { updateRefs(input: \$input) { clientMutationId } }"

    fun endpoint(apiBase: String): HttpUrl {
        val base = apiBase.trimEnd('/').toHttpUrl()
        val segments = base.pathSegments.filter(String::isNotEmpty)
        val path = when {
            segments.isEmpty() -> "/graphql"
            segments.takeLast(2) == listOf("api", "v3") -> "/${segments.dropLast(1).joinToString("/")}/graphql"
            else -> throw IOException("GitHub API endpoint does not expose a supported atomic GraphQL sync endpoint")
        }
        return base.newBuilder().encodedPath(path).query(null).fragment(null).build()
    }

    fun repositoryId(body: String): String = requiredString(parseObject(body).get("node_id"), "repository node ID")

    fun requestBody(repositoryId: String, head: GitHubSyncHead, commitSha: String): String {
        val update = JSONObject().apply {
            put("name", "refs/heads/${head.branch}")
            put("beforeOid", head.sha)
            put("afterOid", commitSha)
            put("force", false)
        }
        val input = JSONObject().apply {
            put("repositoryId", repositoryId)
            put("refUpdates", JSONArray().put(update))
            put("clientMutationId", commitSha)
        }
        return JSONObject().put("query", MUTATION).put("variables", JSONObject().put("input", input)).toString()
    }

    fun validateResponse(body: String, commitSha: String) {
        val response = parseObject(body)
        response.get("errors")?.let(::rejectErrors)
        val data = requiredObject(response.get("data"), "data")
        val updated = requiredObject(data.get("updateRefs"), "updateRefs")
        val acknowledged = requiredString(updated.get("clientMutationId"), "mutation acknowledgement")
        if (acknowledged != commitSha) throw IOException("GitHub atomic sync mutation acknowledgement does not match")
    }

    private fun rejectErrors(value: JsonElement) {
        if (!value.isJsonArray) throw IOException("Invalid GitHub GraphQL errors response")
        val errors = value.asJsonArray.map(::readError)
        if (errors.isEmpty()) return
        throw GitHubGraphQlException(errors.any(Error::isRateLimit), errors.any(Error::isStaleReference),
            "GitHub atomic sync publication failed: ${errors.joinToString("; ") { it.message }.take(240)}")
    }

    private fun readError(value: JsonElement): Error {
        val error = requiredObject(value, "error")
        val extension = error.get("extensions")
        val code = if (extension == null) null else optionalString(requiredObject(extension, "error extensions").get("code"))
        return Error(optionalString(error.get("type")), code, requiredString(error.get("message"), "error message"))
    }

    private data class Error(val type: String?, val code: String?, val message: String) {
        fun isRateLimit(): Boolean = type == "RATE_LIMITED" || code == "RATE_LIMITED" ||
            message.contains("rate limit", ignoreCase = true) || message.contains("abuse detection", ignoreCase = true)
        fun isStaleReference(): Boolean = type == "STALE_DATA" || code == "STALE_DATA"
    }

    private fun parseObject(body: String): JsonObject = runCatching { gson.fromJson(body, JsonObject::class.java) }
        .getOrNull() ?: throw IOException("Invalid GitHub atomic sync response")

    private fun requiredObject(value: JsonElement?, field: String): JsonObject {
        if (value == null || !value.isJsonObject) throw IOException("GitHub atomic sync response has no valid $field")
        return value.asJsonObject
    }

    private fun requiredString(value: JsonElement?, field: String): String {
        val text = optionalString(value)
        if (text.isNullOrBlank()) throw IOException("GitHub atomic sync response has no valid $field")
        return text
    }

    private fun optionalString(value: JsonElement?): String? {
        if (value == null || value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw IOException("GitHub atomic sync response contains a non-string field")
        return value.asString
    }
}
