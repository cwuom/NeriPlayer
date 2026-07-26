package moe.ouom.neriplayer.data.settings

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
 * File: moe.ouom.neriplayer.data.settings/SettingsStore
 * Updated: 2026/3/23
 */

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import moe.ouom.neriplayer.core.logging.NPLogger

// 设置文件损坏（断电/杀进程导致 pb 截断）时回退为空偏好（各项走默认值），
// 避免 dataStore.data 持续抛 CorruptionException 使主界面陷入崩溃-重启循环
internal val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        NPLogger.e("SettingsStore", "settings DataStore 损坏，已回退为空偏好", it)
        emptyPreferences()
    }
)
