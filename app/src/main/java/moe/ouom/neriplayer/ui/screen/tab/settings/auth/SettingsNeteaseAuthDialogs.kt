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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.auth/SettingsNeteaseAuthDialogs
 * Updated: 2026/3/23
 */

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.activity.auth.NeteaseQrLoginActivity
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import org.json.JSONObject

@Composable
internal fun SettingsNeteaseAuthDialogs(
    showSheet: Boolean,
    initialTab: Int,
    onDismissSheet: () -> Unit,
    inlineMsg: String?,
    onInlineMsgChange: (String?) -> Unit,
    showConfirmDialog: Boolean,
    confirmPhoneMasked: String?,
    onDismissConfirmDialog: () -> Unit,
    vm: NeteaseAuthViewModel,
    showSavedCookieDialog: Boolean = false,
    onDismissSavedCookieDialog: () -> Unit = {},
    onOpenSheetAtTab: (Int) -> Unit = {},
    onLogout: (() -> Unit)? = null,
    onBrowserLogin: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current

    if (showSavedCookieDialog) {
        SavedCookieActionDialog(
            title = stringResource(CoreCommonR.string.settings_netease_saved_cookie_title),
            message = stringResource(CoreCommonR.string.settings_netease_saved_cookie_message),
            onDismiss = onDismissSavedCookieDialog,
            onContinueLogin = {
                onDismissSavedCookieDialog()
                onOpenSheetAtTab(0)
            },
            onLogout = {
                onDismissSavedCookieDialog()
                onLogout?.invoke()
            }
        )
    }

    if (showConfirmDialog) {
        MiuixSettingsDialog(
            onDismissRequest = onDismissConfirmDialog,
            title = { Text(stringResource(CoreCommonR.string.login_confirm_send_code)) },
            text = { Text(stringResource(CoreCommonR.string.login_send_code_to, confirmPhoneMasked ?: "")) },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        onDismissConfirmDialog()
                        vm.sendCaptcha(ctcode = "86")
                    }
                ) {
                    Text(stringResource(CoreCommonR.string.action_send))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(
                    onClick = {
                        onDismissConfirmDialog()
                        onInlineMsgChange(composeResources.getString(CoreCommonR.string.sync_send_cancelled))
                    }
                ) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }

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
                    val json = result.data?.getStringExtra(NeteaseQrLoginActivity.RESULT_COOKIE) ?: "{}"
                    vm.importCookiesFromMap(parseCookieMap(json))
                } else {
                    onInlineMsgChange(composeResources.getString(CoreCommonR.string.settings_cookie_cancelled))
                }
            }
            val defaultBrowserLogin: () -> Unit = {
                onInlineMsgChange(null)
                AppContainer.pauseYouTubeBackgroundWebWorkForForegroundLogin()
                webLoginLauncher.launch(Intent(context, NeteaseQrLoginActivity::class.java))
            }
            defaultBrowserLogin
        }

        SettingsCookieLoginSheet(
            title = stringResource(CoreCommonR.string.login_netease),
            initialTab = initialTab,
            inlineMsg = inlineMsg,
            onInlineMsgChange = onInlineMsgChange,
            onDismiss = onDismissSheet,
            browserTabLabel = stringResource(CoreCommonR.string.login_qr),
            browserButtonLabel = stringResource(CoreCommonR.string.login_start_netease_qr),
            browserHintContent = {
                Text(
                    stringResource(CoreCommonR.string.settings_netease_login_browser_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            cookieLabel = stringResource(CoreCommonR.string.login_paste_cookie_hint),
            onBrowserLogin = launchBrowserLogin,
            onSaveCookie = { rawCookie ->
                if (rawCookie.isBlank()) {
                    onInlineMsgChange(composeResources.getString(CoreCommonR.string.settings_cookie_input_hint))
                } else {
                    vm.importCookiesFromRaw(rawCookie)
                }
            }
        )
    }

}

private fun parseCookieMap(json: String): Map<String, String> {
    return JSONObject(json).let { obj ->
        val keys = obj.keys()
        val result = linkedMapOf<String, String>()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = obj.optString(key, "")
        }
        result
    }
}
