package moe.ouom.neriplayer.ui.component

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.LanguageManager
import moe.ouom.neriplayer.util.getDisplayName

/**
 * 语言选择对话框
 * Language selection dialog
 */
@Composable
fun LanguageSettingItem(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf(LanguageManager.getCurrentLanguage(context)) }
    var showRestartDialog by remember { mutableStateOf(false) }

    ListItem(
        modifier = modifier.clickable { showDialog = true },
        headlineContent = { Text(stringResource(R.string.language_setting_title)) },
        supportingContent = { Text(currentLanguage.getDisplayName(context)) },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null
            )
        }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.language_select_title)) },
            text = {
                Column {
                    LanguageManager.Language.entries.forEach { language ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    LanguageManager.setLanguage(context, language)
                                    currentLanguage = language
                                    showDialog = false
                                    showRestartDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLanguage == language,
                                onClick = {
                                    LanguageManager.setLanguage(context, language)
                                    currentLanguage = language
                                    showDialog = false
                                    showRestartDialog = true
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = language.getDisplayName(context),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text(stringResource(R.string.language_restart_title)) },
            text = { Text(stringResource(R.string.language_restart_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        (context as? Activity)?.let { activity ->
                            LanguageManager.restartActivity(activity)
                        }
                    }
                ) {
                    Text(stringResource(R.string.language_restart_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text(stringResource(R.string.language_restart_later))
                }
            }
        )
    }
}
