@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package moe.ouom.neriplayer.ui.screen.tab.settings.auth

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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.auth/SettingsCookieAuthDialogs
 * Updated: 2026/3/23
 */

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.BiliWebLoginActivity
import moe.ouom.neriplayer.activity.YouTubeWebLoginActivity
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.auth.bili.BILI_AUTH_STALE_AFTER_MS
import moe.ouom.neriplayer.ui.component.bottomSheetDragBlocker
import moe.ouom.neriplayer.ui.screen.tab.settings.component.InlineMessage
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.util.HapticButton
import moe.ouom.neriplayer.util.HapticTextButton
import moe.ouom.neriplayer.util.convertTimestampToDate

@Composable
internal fun SettingsBiliAuthDialogs(
    showSheet: Boolean,
    initialTab: Int,
    onDismissSheet: () -> Unit,
    inlineMsg: String?,
    onInlineMsgChange: (String?) -> Unit,
    vm: BiliAuthViewModel,
    showCookieDialog: Boolean,
    cookieText: String,
    onDismissCookieDialog: () -> Unit,
    showReauthDialog: Boolean,
    reauthHealth: SavedCookieAuthHealth?,
    onDismissReauthDialog: () -> Unit,
    onOpenSheetAtTab: (Int) -> Unit,
    onBrowserLogin: (() -> Unit)? = null
) {
    val context = LocalContext.current

    if (showSheet) {
        val launchBrowserLogin: () -> Unit = onBrowserLogin?.let { injectedBrowserLogin ->
            {
                onInlineMsgChange(null)
                injectedBrowserLogin()
            }
        } ?: run {
            val webLoginLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    val json = result.data?.getStringExtra(BiliWebLoginActivity.RESULT_COOKIE) ?: "{}"
                    vm.importCookiesFromMap(vm.parseJsonToMap(json))
                } else {
                    onInlineMsgChange(context.getString(R.string.settings_cookie_cancelled))
                }
            }
            val defaultBrowserLogin: () -> Unit = {
                onInlineMsgChange(null)
                webLoginLauncher.launch(Intent(context, BiliWebLoginActivity::class.java))
            }
            defaultBrowserLogin
        }

        TwoTabCookieLoginSheet(
            title = stringResource(R.string.platform_bilibili),
            initialTab = initialTab,
            inlineMsg = inlineMsg,
            onInlineMsgChange = onInlineMsgChange,
            onDismiss = onDismissSheet,
            browserHintContent = {
                Text(
                    stringResource(R.string.settings_bili_login_browser_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            },
            cookieLabel = stringResource(R.string.login_paste_bili_cookie_hint),
            onBrowserLogin = launchBrowserLogin,
            onSaveCookie = { rawCookie ->
                if (rawCookie.isBlank()) {
                    onInlineMsgChange(context.getString(R.string.auth_cookie_empty))
                } else {
                    vm.importCookiesFromRaw(rawCookie)
                }
            }
        )
    }

    if (showCookieDialog) {
        CookieTextDialog(
            title = stringResource(R.string.settings_bili_login_success),
            cookieText = cookieText,
            onDismiss = onDismissCookieDialog
        )
    }

    reauthHealth?.takeIf { showReauthDialog }?.let { health ->
        val title = when (health.state) {
            SavedCookieAuthState.Missing -> stringResource(R.string.settings_bili_reauth_required_title)
            SavedCookieAuthState.Expired -> stringResource(R.string.settings_bili_reauth_expired_title)
            SavedCookieAuthState.Stale -> stringResource(R.string.settings_bili_reauth_stale_title)
            SavedCookieAuthState.Valid,
            SavedCookieAuthState.Checking -> stringResource(R.string.platform_bilibili)
        }
        val message = when (health.state) {
            SavedCookieAuthState.Missing -> stringResource(R.string.settings_bili_reauth_required_message)
            SavedCookieAuthState.Expired -> stringResource(R.string.settings_bili_reauth_expired_message)
            SavedCookieAuthState.Stale -> {
                val savedAtLabel = health.savedAt
                    .takeIf { it > 0L }
                    ?.let(::convertTimestampToDate)
                    ?: stringResource(R.string.settings_bili_reauth_unknown_time)
                pluralStringResource(
                    R.plurals.settings_bili_reauth_stale_message,
                    (BILI_AUTH_STALE_AFTER_MS / (24L * 60L * 60L * 1000L)).toInt(),
                    (BILI_AUTH_STALE_AFTER_MS / (24L * 60L * 60L * 1000L)).toInt(),
                    savedAtLabel
                )
            }
            SavedCookieAuthState.Valid,
            SavedCookieAuthState.Checking -> ""
        }

        AlertDialog(
            onDismissRequest = onDismissReauthDialog,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        onDismissReauthDialog()
                        onInlineMsgChange(null)
                        onOpenSheetAtTab(0)
                    }
                ) {
                    Text(stringResource(R.string.settings_bili_reauth_action_login))
                }
            },
            dismissButton = {
                Row {
                    HapticTextButton(
                        onClick = {
                            onDismissReauthDialog()
                            onInlineMsgChange(null)
                            onOpenSheetAtTab(1)
                        }
                    ) {
                        Text(stringResource(R.string.settings_bili_reauth_action_import))
                    }
                    HapticTextButton(onClick = onDismissReauthDialog) {
                        Text(stringResource(R.string.action_later))
                    }
                }
            }
        )
    }
}

@Composable
internal fun SettingsYouTubeAuthDialogs(
    showSheet: Boolean,
    initialTab: Int,
    onDismissSheet: () -> Unit,
    inlineMsg: String?,
    onInlineMsgChange: (String?) -> Unit,
    vm: YouTubeAuthViewModel,
    showCookieDialog: Boolean,
    cookieText: String,
    onDismissCookieDialog: () -> Unit,
    onBrowserLogin: (() -> Unit)? = null
) {
    val context = LocalContext.current

    if (showSheet) {
        val launchBrowserLogin: () -> Unit = onBrowserLogin?.let { injectedBrowserLogin ->
            {
                onInlineMsgChange(null)
                injectedBrowserLogin()
            }
        } ?: run {
            val webLoginLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    val json = result.data?.getStringExtra(YouTubeWebLoginActivity.RESULT_AUTH_JSON) ?: "{}"
                    vm.importAuthFromJson(json)
                } else {
                    onInlineMsgChange(context.getString(R.string.settings_cookie_cancelled))
                }
            }
            val defaultBrowserLogin: () -> Unit = {
                onInlineMsgChange(null)
                webLoginLauncher.launch(Intent(context, YouTubeWebLoginActivity::class.java))
            }
            defaultBrowserLogin
        }

        TwoTabCookieLoginSheet(
            title = stringResource(R.string.common_youtube),
            initialTab = initialTab,
            inlineMsg = inlineMsg,
            onInlineMsgChange = onInlineMsgChange,
            onDismiss = onDismissSheet,
            browserHintContent = {
                Text(
                    stringResource(R.string.settings_youtube_login_browser_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_youtube_login_browser_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            },
            cookieLabel = stringResource(R.string.login_paste_youtube_cookie_hint),
            onBrowserLogin = launchBrowserLogin,
            onSaveCookie = { rawCookie ->
                if (rawCookie.isBlank()) {
                    onInlineMsgChange(context.getString(R.string.auth_cookie_empty))
                } else {
                    vm.importCookiesFromRaw(rawCookie)
                }
            }
        )
    }

    if (showCookieDialog) {
        CookieTextDialog(
            title = stringResource(R.string.settings_youtube_login_success),
            cookieText = cookieText,
            onDismiss = onDismissCookieDialog
        )
    }
}

@Composable
private fun TwoTabCookieLoginSheet(
    title: String,
    initialTab: Int,
    inlineMsg: String?,
    onInlineMsgChange: (String?) -> Unit,
    onDismiss: () -> Unit,
    browserHintContent: @Composable ColumnScope.() -> Unit,
    cookieLabel: String,
    onBrowserLogin: () -> Unit,
    onSaveCookie: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var rawCookie by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier
                .bottomSheetDragBlocker()
                .padding(start = 16.dp, end = 16.dp, bottom = 48.dp, top = 12.dp)
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))

                AnimatedVisibility(visible = inlineMsg != null, enter = fadeIn(), exit = fadeOut()) {
                    InlineMessage(
                        text = inlineMsg ?: "",
                        onClose = { onInlineMsgChange(null) }
                    )
                }

                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.login_browser)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.login_paste_cookie)) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                when (selectedTab) {
                    0 -> {
                        browserHintContent()
                        HapticButton(onClick = onBrowserLogin) {
                            Text(stringResource(R.string.login_start_browser))
                        }
                    }

                    else -> {
                        androidx.compose.material3.OutlinedTextField(
                            value = rawCookie,
                            onValueChange = { rawCookie = it },
                            label = { Text(cookieLabel) },
                            minLines = 6,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        HapticButton(onClick = { onSaveCookie(rawCookie) }) {
                            Text(stringResource(R.string.login_save_cookie))
                        }
                    }
                }
            }
        }
    }
}
