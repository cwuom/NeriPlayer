package moe.ouom.neriplayer.api.sync.github

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

internal object GitHubArchiveCommit {
    // 这些是客户端快速路径预算，较大的归档仍使用分阶段发布
    const val MAX_FILES = 64
    const val MAX_CONTENT_BYTES = 3 * 1024 * 1024
    const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
    private val objectId = Regex("[0-9a-f]{40}")
    private const val MUTATION = "mutation NeriPlayerSyncCommit(\$input: CreateCommitOnBranchInput!) { createCommitOnBranch(input: \$input) { clientMutationId commit { oid parents(first: 2) { nodes { oid } } } ref { name prefix repository { nameWithOwner } target { oid } } } }"

    fun requestBody(
        owner: String,
        repo: String,
        head: GitHubSyncHead,
        message: String,
        files: List<Pair<String, ByteArray>>,
        deletions: Set<String>,
        mutationId: String
    ): String? {
        if (!validateChanges(files, deletions)) return null
        val additions = JSONArray()
        for ((path, content) in files) {
            additions.put(JSONObject().put("path", path).put("contents", Base64.getEncoder().encodeToString(content)))
        }
        val removals = JSONArray()
        for (path in deletions) removals.put(JSONObject().put("path", path))
        val input = JSONObject().apply {
            put("branch", JSONObject().put("repositoryNameWithOwner", "$owner/$repo").put("branchName", head.branch))
            put("expectedHeadOid", head.sha)
            put("message", JSONObject().put("headline", message))
            put("fileChanges", JSONObject().put("additions", additions).put("deletions", removals))
            put("clientMutationId", mutationId)
        }
        val body = JSONObject().put("query", MUTATION).put("variables", JSONObject().put("input", input)).toString()
        return body.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES }
    }

    private fun validateChanges(files: List<Pair<String, ByteArray>>, deletions: Set<String>): Boolean {
        if (files.size.toLong() + deletions.size > MAX_FILES) return false
        val paths = HashSet<String>()
        var contentBytes = 0L
        for ((path, content) in files) {
            GitHubArchiveUploadValidation.validate(path, content)
            require(paths.add(path)) { "Duplicate sync archive addition" }
            contentBytes += content.size
        }
        for (path in deletions) {
            require(GitHubArchiveTreeReader.isOwnedObjectPath(path)) { "Invalid sync archive deletion" }
            require(paths.add(path)) { "Overlapping sync archive changes" }
        }
        return contentBytes <= MAX_CONTENT_BYTES
    }

    fun validateResponse(body: String, mutationId: String, owner: String, repo: String, head: GitHubSyncHead): String {
        val response = GitHubArchiveRefUpdate.readResponse(body)
        val data = requiredObject(response.get("data"), "data")
        val result = requiredObject(data.get("createCommitOnBranch"), "createCommitOnBranch")
        if (requiredString(result.get("clientMutationId"), "mutation acknowledgement") != mutationId) {
            throw IOException("GitHub archive commit acknowledgement does not match")
        }
        val sha = readCommit(result, head)
        validateRef(result, owner, repo, head, sha)
        return sha
    }

    private fun readCommit(result: JsonObject, head: GitHubSyncHead): String {
        val commit = requiredObject(result.get("commit"), "commit")
        val sha = requiredObjectId(commit.get("oid"))
        if (sha == head.sha) throw IOException("GitHub archive commit did not advance the expected head")
        val parents = requiredObject(commit.get("parents"), "commit parents").get("nodes")
        if (parents == null || !parents.isJsonArray || parents.asJsonArray.size() != 1) {
            throw IOException("GitHub archive commit must have exactly one parent")
        }
        val parent = requiredObject(parents.asJsonArray[0], "commit parent")
        if (requiredString(parent.get("oid"), "parent object ID") != head.sha) {
            throw IOException("GitHub archive commit parent does not match the expected head")
        }
        return sha
    }

    private fun validateRef(result: JsonObject, owner: String, repo: String, head: GitHubSyncHead, sha: String) {
        val ref = requiredObject(result.get("ref"), "ref")
        if (requiredString(ref.get("name"), "branch name") != head.branch ||
            requiredString(ref.get("prefix"), "branch prefix") != "refs/heads/") {
            throw IOException("GitHub archive commit acknowledged another branch")
        }
        val repository = requiredObject(ref.get("repository"), "repository")
        if (!requiredString(repository.get("nameWithOwner"), "repository name").equals("$owner/$repo", ignoreCase = true)) {
            throw IOException("GitHub archive commit acknowledged another repository")
        }
        val target = requiredObject(ref.get("target"), "ref target")
        if (requiredObjectId(target.get("oid")) != sha) {
            throw IOException("GitHub archive commit and branch target do not match")
        }
    }

    private fun requiredObjectId(value: JsonElement?): String {
        val sha = requiredString(value, "object ID")
        if (!objectId.matches(sha)) throw IOException("GitHub archive commit has an invalid object ID")
        return sha
    }

    private fun requiredObject(value: JsonElement?, field: String): JsonObject {
        if (value == null || !value.isJsonObject) throw IOException("GitHub archive commit response has no valid $field")
        return value.asJsonObject
    }

    private fun requiredString(value: JsonElement?, field: String): String {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isBlank()) {
            throw IOException("GitHub archive commit response has no valid $field")
        }
        return value.asString
    }
}
