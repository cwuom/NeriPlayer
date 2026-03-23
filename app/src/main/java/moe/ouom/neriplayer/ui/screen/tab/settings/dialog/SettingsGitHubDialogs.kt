package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.dialog/SettingsGitHubDialogs
 * Updated: 2026/3/23
 */

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.viewmodel.GitHubSyncViewModel
import moe.ouom.neriplayer.util.HapticButton
import moe.ouom.neriplayer.util.HapticTextButton

@Composable
internal fun SettingsGitHubDialogs(
    showGitHubConfigDialog: Boolean,
    onShowGitHubConfigDialogChange: (Boolean) -> Unit,
    showClearGitHubConfigDialog: Boolean,
    onShowClearGitHubConfigDialogChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val githubVm: GitHubSyncViewModel = viewModel()

    if (showGitHubConfigDialog) {
        val githubState by githubVm.uiState.collectAsState()
        var githubToken by remember { mutableStateOf("") }
        var githubRepoName by remember { mutableStateOf("neriplayer-backup") }
        var useExistingRepo by remember { mutableStateOf(false) }
        var existingRepoName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { onShowGitHubConfigDialogChange(false) },
            title = { Text(stringResource(R.string.sync_config)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResource(R.string.sync_step1_token),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = githubToken,
                        onValueChange = { githubToken = it },
                        label = { Text(stringResource(R.string.settings_github_token_label)) },
                        placeholder = { Text(stringResource(R.string.settings_github_token_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_github_token_permission),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                "https://github.com/settings/tokens/new?scopes=repo&description=NeriPlayer%20Backup".toUri()
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Text(stringResource(R.string.sync_create_token))
                    }

                    if (githubState.tokenValid) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.sync_step2_repo),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = !useExistingRepo,
                                onClick = { useExistingRepo = false }
                            )
                            Text(stringResource(R.string.sync_create_new_repo))
                        }

                        if (!useExistingRepo) {
                            OutlinedTextField(
                                value = githubRepoName,
                                onValueChange = { githubRepoName = it },
                                label = { Text(stringResource(R.string.sync_repo_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = useExistingRepo,
                                onClick = { useExistingRepo = true }
                            )
                            Text(stringResource(R.string.sync_use_existing_repo))
                        }

                        if (useExistingRepo) {
                            OutlinedTextField(
                                value = existingRepoName,
                                onValueChange = { existingRepoName = it },
                                label = { Text(stringResource(R.string.sync_repo_full_name)) },
                                placeholder = { Text(stringResource(R.string.settings_sync_repo_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!githubState.tokenValid) {
                    HapticButton(
                        onClick = { githubVm.validateToken(context, githubToken) },
                        enabled = githubToken.isNotBlank() && !githubState.isValidating
                    ) {
                        if (githubState.isValidating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.sync_verify_token))
                    }
                } else {
                    HapticButton(
                        onClick = {
                            if (useExistingRepo) {
                                githubVm.useExistingRepository(context, existingRepoName)
                            } else {
                                githubVm.createRepository(context, githubRepoName)
                            }
                            onShowGitHubConfigDialogChange(false)
                        },
                        enabled = !githubState.isCreatingRepo && !githubState.isCheckingRepo
                    ) {
                        if (githubState.isCreatingRepo || githubState.isCheckingRepo) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.action_done))
                    }
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { onShowGitHubConfigDialogChange(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showClearGitHubConfigDialog) {
        AlertDialog(
            onDismissRequest = { onShowClearGitHubConfigDialogChange(false) },
            title = { Text(stringResource(R.string.sync_clear_config)) },
            text = { Text(stringResource(R.string.sync_clear_config_desc)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        githubVm.clearConfiguration(context)
                        onShowClearGitHubConfigDialogChange(false)
                    }
                ) {
                    Text(
                        stringResource(R.string.action_confirm_clear),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { onShowClearGitHubConfigDialogChange(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
