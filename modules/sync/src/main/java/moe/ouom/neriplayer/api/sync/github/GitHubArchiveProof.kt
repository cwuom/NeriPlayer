package moe.ouom.neriplayer.api.sync.github

import java.util.Collections
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class GitHubArchiveHead internal constructor(
    apiBase: String,
    owner: String,
    repo: String,
    val head: GitHubSyncHead,
    internal val treeSha: String
) {
    private val target = GitHubArchiveTarget(apiBase, owner, repo)

    internal fun matches(apiBase: String, owner: String, repo: String): Boolean =
        target == GitHubArchiveTarget(apiBase, owner, repo)
}

class GitHubArchiveProof internal constructor(
    private val snapshot: GitHubArchiveHead,
    objectIds: Map<String, String>
) {
    val objectIds: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(objectIds))
    internal val treeSha: String get() = snapshot.treeSha

    internal fun matches(apiBase: String, owner: String, repo: String, head: GitHubSyncHead): Boolean =
        snapshot.matches(apiBase, owner, repo) && snapshot.head == head
}

private data class GitHubArchiveTarget(val apiBase: HttpUrl, val owner: String, val repo: String) {
    constructor(apiBase: String, owner: String, repo: String) :
        this(apiBase.trimEnd('/').toHttpUrl(), owner, repo)
}
