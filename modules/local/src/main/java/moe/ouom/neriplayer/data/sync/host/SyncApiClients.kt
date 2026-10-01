package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.network.DataHttpClients

fun createGitHubSyncClient(context: Context, token: String): GitHubApiClient = GitHubApiClient(
    token = token,
    client = DataHttpClients.shared,
    tokenExpiredMessage = context.getString(CoreCommonR.string.github_token_expired_message)
)

fun createWebDavSyncClient(context: Context, username: String, password: String): WebDavApiClient = WebDavApiClient(
    username = username,
    password = password,
    client = DataHttpClients.shared,
    authFailureMessage = context.getString(CoreCommonR.string.webdav_auth_failed)
)
