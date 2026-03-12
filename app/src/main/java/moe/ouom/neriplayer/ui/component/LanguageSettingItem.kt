package moe.ouom.neriplayer.ui.component

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.LanguageManager
import moe.ouom.neriplayer.util.getDisplayName

private val LanguageSettingItemShape = RoundedCornerShape(18.dp)
private val LanguageOptionShape = RoundedCornerShape(16.dp)

/**
 * 语言选择对话框
 * Language selection dialog
 */
@Composable
fun LanguageSettingItem(
    modifier: Modifier = Modifier,
    onBeforeRestart: () -> Unit = {}
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf(LanguageManager.getCurrentLanguage(context)) }

    ListItem(
        modifier = modifier
            .clip(LanguageSettingItemShape)
            .clickable { showDialog = true },
        headlineContent = { Text(stringResource(R.string.language_setting_title)) },
        supportingContent = { Text(currentLanguage.getDisplayName(context)) },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null
            )
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
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
                                .clip(LanguageOptionShape)
                                .clickable {
                                    showDialog = false
                                    if (currentLanguage == language) return@clickable
                                    LanguageManager.setLanguage(context, language)
                                    currentLanguage = language
                                    (context as? Activity)?.let { activity ->
                                        onBeforeRestart()
                                        LanguageManager.restartActivity(activity)
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLanguage == language,
                                onClick = {
                                    showDialog = false
                                    if (currentLanguage == language) return@RadioButton
                                    LanguageManager.setLanguage(context, language)
                                    currentLanguage = language
                                    (context as? Activity)?.let { activity ->
                                        onBeforeRestart()
                                        LanguageManager.restartActivity(activity)
                                    }
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
}
