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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.auth/SettingsAuthComponents
 * Updated: 2026/3/23
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import moe.ouom.neriplayer.util.HapticButton

@Composable
internal fun NeteaseLoginContent(vm: NeteaseAuthViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycleCompat()

    Column {
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.phone,
            onValueChange = vm::onPhoneChange,
            label = { Text(stringResource(R.string.settings_phone_number_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state.captcha,
            onValueChange = vm::onCaptchaChange,
            label = { Text(stringResource(R.string.login_sms_code)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        HapticButton(
            enabled = !state.sending && state.countdownSec <= 0,
            onClick = vm::askConfirmSendCaptcha
        ) {
            if (state.sending) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.login_sending))
            } else {
                Text(
                    if (state.countdownSec > 0) {
                        stringResource(R.string.settings_resend_code_countdown, state.countdownSec)
                    } else {
                        stringResource(R.string.login_send_code)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        HapticButton(
            enabled = state.captcha.isNotEmpty() && !state.loggingIn,
            onClick = { vm.loginByCaptcha(countryCode = "86") }
        ) {
            if (state.loggingIn) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.login_logging_in))
            } else {
                Text(stringResource(R.string.login_title))
            }
        }
    }
}
