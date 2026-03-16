package moe.ouom.neriplayer.data

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
 */

enum class SavedCookieAuthState {
    Missing,
    Checking,
    Expired,
    Stale,
    Valid
}

data class SavedCookieAuthHealth(
    val state: SavedCookieAuthState = SavedCookieAuthState.Missing,
    val savedAt: Long = 0L,
    val checkedAt: Long = 0L,
    val ageMs: Long = Long.MAX_VALUE,
    val loginCookieKeys: List<String> = emptyList()
) {
    val shouldPromptRelogin: Boolean
        get() = state == SavedCookieAuthState.Expired || state == SavedCookieAuthState.Stale
}
