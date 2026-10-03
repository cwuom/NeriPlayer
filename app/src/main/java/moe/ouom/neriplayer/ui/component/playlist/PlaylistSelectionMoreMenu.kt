package moe.ouom.neriplayer.ui.component.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.LowPriority
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.haptic.HapticIconButton

@Composable
internal fun PlaylistSelectionMoreMenu(
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    canExport: Boolean,
    onExport: () -> Unit,
    canSync: Boolean = false,
    onSync: (() -> Unit)? = null,
    canInsert: Boolean = false,
    onInsert: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        HapticIconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(CoreCommonR.string.cd_more_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(stringResource(if (allSelected) {
                        CoreCommonR.string.action_deselect_all
                    } else {
                        CoreCommonR.string.action_select_all
                    }))
                },
                leadingIcon = {
                    Icon(
                        if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = null
                    )
                },
                onClick = {
                    expanded = false
                    onToggleSelectAll()
                }
            )
            if (onInsert != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(CoreCommonR.string.playlist_insert_action)) },
                    leadingIcon = { Icon(Icons.Outlined.LowPriority, contentDescription = null) },
                    enabled = canInsert,
                    onClick = {
                        expanded = false
                        onInsert()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.cd_export_playlist)) },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null)
                },
                enabled = canExport,
                onClick = {
                    expanded = false
                    onExport()
                }
            )
            if (onSync != null) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(CoreCommonR.string.local_playlist_sync_netease_playlist))
                    },
                    leadingIcon = { Icon(Icons.Outlined.Sync, contentDescription = null) },
                    enabled = canSync,
                    onClick = {
                        expanded = false
                        onSync()
                    }
                )
            }
        }
    }
}
