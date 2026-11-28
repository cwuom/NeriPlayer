package moe.ouom.neriplayer.data.model.playback

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
 * File: moe.ouom.neriplayer.core.player.model/PlayerEvent
 * Updated: 2026/3/23
 */

/** 用于封装播放器需要通知 UI 的事件 */
sealed class PlayerEvent {
    data class ShowLoginPrompt(val message: String) : PlayerEvent()
    data class ShowError(val message: String) : PlayerEvent()

    /** 不打断播放的提示，例如只拿到试听片段 */
    data class ShowNotice(val message: String) : PlayerEvent()

    /**
     * SAF 目录授权失效，需要用户重新授权才能恢复本地文件访问
     * @param reference 失效的文档引用，下载目录场景下可为空，由 UI 读取当前下载目录
     */
    data class StoragePermissionLost(
        val scope: StoragePermissionScope,
        val fallback: StoragePermissionFallback,
        val reference: String? = null
    ) : PlayerEvent()
}

enum class StoragePermissionScope { DOWNLOAD_DIRECTORY, LOCAL_MEDIA }

enum class StoragePermissionFallback {
    /** 已改用在线播放 */
    STREAMING,
    /** 无可用回退，已跳过当前歌曲 */
    SKIPPED,
    /** 非播放触发的检查，例如启动时的授权巡检 */
    NONE
}
