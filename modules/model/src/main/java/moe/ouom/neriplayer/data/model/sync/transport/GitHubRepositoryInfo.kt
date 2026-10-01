package moe.ouom.neriplayer.data.model.sync.transport

import com.google.gson.annotations.SerializedName

/** GitHub API响应 - 仓库信息 */
data class GitHubRepositoryInfo(
    val id: Long,
    val name: String,
    @SerializedName("full_name") val fullName: String,
    val private: Boolean,
    @SerializedName("default_branch") val defaultBranch: String
)
