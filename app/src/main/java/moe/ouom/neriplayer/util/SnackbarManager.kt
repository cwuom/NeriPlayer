package moe.ouom.neriplayer.util

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
 * File: moe.ouom.neriplayer.util/SnackbarManager
 * Created: 2025/1/11
 */

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 集中管理 Snackbar 的工具类
 * 提供统一的 Snackbar 显示接口，支持自定义属性
 */
object SnackbarManager {

    /**
     * 显示简单的 Snackbar
     */
    fun showSnackbar(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short
    ) {
        scope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = duration
            )
        }
    }

    /**
     * 显示带操作按钮的 Snackbar
     */
    fun showSnackbarWithAction(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        message: String,
        actionLabel: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        onActionPerformed: () -> Unit = {}
    ) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = duration
            )
            if (result == SnackbarResult.ActionPerformed) {
                onActionPerformed()
            }
        }
    }

    /**
     * 显示长时间的 Snackbar
     */
    fun showLongSnackbar(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        message: String
    ) {
        showSnackbar(scope, snackbarHostState, message, SnackbarDuration.Long)
    }

    /**
     * 显示无限时长的 Snackbar（需要用户手动关闭）
     */
    fun showIndefiniteSnackbar(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        message: String,
        actionLabel: String,
        onActionPerformed: () -> Unit = {}
    ) {
        showSnackbarWithAction(
            scope = scope,
            snackbarHostState = snackbarHostState,
            message = message,
            actionLabel = actionLabel,
            duration = SnackbarDuration.Indefinite,
            onActionPerformed = onActionPerformed
        )
    }
}
