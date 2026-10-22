package moe.ouom.neriplayer.ui.screen.tab.settings.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField

@Composable
internal fun SettingsConfigExportOptionsDialog(
    onConfirm: (includeLoginState: Boolean, password: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var includeLoginState by remember { mutableStateOf(false) }
    var encryptFile by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val passwordValid = password.length >= 8 && password.isNotBlank()
    val passwordsMatch = password == confirmation
    val dismiss = {
        password = ""
        confirmation = ""
        onDismiss()
    }

    MiuixSettingsDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(CoreCommonR.string.settings_config_export_options_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(CoreCommonR.string.settings_config_export_options_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                ConfigProtectionOption(
                    title = stringResource(CoreCommonR.string.settings_config_include_login_state),
                    description = stringResource(CoreCommonR.string.settings_config_include_login_state_description),
                    checked = includeLoginState,
                    onCheckedChange = { includeLoginState = it }
                )
                ConfigProtectionOption(
                    title = stringResource(CoreCommonR.string.settings_config_encrypt_file),
                    description = stringResource(CoreCommonR.string.settings_config_encrypt_file_description),
                    checked = encryptFile,
                    onCheckedChange = {
                        encryptFile = it
                        password = ""
                        confirmation = ""
                    }
                )
                if (includeLoginState && !encryptFile) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = stringResource(CoreCommonR.string.settings_config_plaintext_login_warning),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (encryptFile) {
                    ConfigPasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(CoreCommonR.string.settings_config_password)
                    )
                    Text(
                        text = stringResource(CoreCommonR.string.settings_config_password_requirements),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (password.isNotEmpty() && !passwordValid) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    ConfigPasswordField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = stringResource(CoreCommonR.string.settings_config_confirm_password)
                    )
                    if (confirmation.isNotEmpty() && !passwordsMatch) {
                        Text(
                            text = stringResource(CoreCommonR.string.settings_config_password_mismatch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            MiuixSettingsButton(
                enabled = !encryptFile || (passwordValid && passwordsMatch),
                onClick = {
                    val exportPassword = password.takeIf { encryptFile }
                    password = ""
                    confirmation = ""
                    onConfirm(includeLoginState, exportPassword)
                }
            ) {
                Text(stringResource(CoreCommonR.string.settings_backup_action_export))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = dismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun SettingsConfigImportPasswordDialog(
    isPasswordInvalid: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    val dismiss = {
        password = ""
        onDismiss()
    }

    MiuixSettingsDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(CoreCommonR.string.settings_config_import_password_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(CoreCommonR.string.settings_config_import_password_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                ConfigPasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(CoreCommonR.string.settings_config_password)
                )
                if (isPasswordInvalid) {
                    Text(
                        text = stringResource(CoreCommonR.string.settings_config_password_invalid),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            MiuixSettingsButton(
                enabled = password.isNotBlank(),
                onClick = {
                    val importPassword = password
                    password = ""
                    onConfirm(importPassword)
                }
            ) {
                Text(stringResource(CoreCommonR.string.settings_backup_action_import))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = dismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ConfigProtectionOption(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MiuixSettingsSwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ConfigPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    MiuixSettingsTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
}
