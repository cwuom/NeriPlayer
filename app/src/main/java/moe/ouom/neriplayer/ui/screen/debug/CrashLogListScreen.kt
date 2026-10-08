package moe.ouom.neriplayer.ui.screen.debug

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
 * File: moe.ouom.neriplayer.ui.screen.debug/CrashLogListScreen
 * Created: 2025/1/11
 */

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.io.File
import moe.ouom.neriplayer.common.R as CoreCommonR

@Composable
fun CrashLogListScreen(
    onBack: () -> Unit,
    onLogFileClick: (String) -> Unit
) {
    val context = LocalContext.current
    TextLogFileListScreen(
        titleRes = CoreCommonR.string.crash_log_title,
        clearConfirmRes = CoreCommonR.string.crash_log_clear_confirm,
        clearedCountRes = CoreCommonR.plurals.crash_log_cleared,
        emptyTitleRes = CoreCommonR.string.crash_log_no_file,
        emptyHintRes = CoreCommonR.string.crash_log_hint,
        resolveDirectory = { crashLogDirectory(context) },
        onBack = onBack,
        onLogFileClick = onLogFileClick,
        fileIcon = { Icon(Icons.Outlined.Error, null, tint = MaterialTheme.colorScheme.error) }
    )
}

internal fun crashLogDirectory(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "crashes")
