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
 * File: moe.ouom.neriplayer.ui.screen.debug/LogListScreen
 * Created: 2025/8/17
 */

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.R as AppR

@Composable
fun LogListScreen(
    onBack: () -> Unit,
    onLogFileClick: (String) -> Unit
) {
    val context = LocalContext.current
    TextLogFileListScreen(
        titleRes = AppR.string.log_app,
        clearConfirmRes = AppR.string.log_delete_confirm,
        clearedCountRes = AppR.plurals.log_cleared_count,
        emptyTitleRes = AppR.string.log_no_file,
        emptyHintRes = AppR.string.log_enable_hint,
        resolveDirectory = { NPLogger.getLogDirectory(context) },
        onBack = onBack,
        onLogFileClick = onLogFileClick,
        fileIcon = Icons.Rounded.Description,
        fileHue = Color(0xFF0A84FF)
    )
}
