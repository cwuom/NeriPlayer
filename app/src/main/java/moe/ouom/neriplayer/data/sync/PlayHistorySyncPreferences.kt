package moe.ouom.neriplayer.data.sync

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
 * File: moe.ouom.neriplayer.data.sync/PlayHistorySyncPreferences
 * Created: 2026/7/15
 */

import android.content.Context
import androidx.core.content.edit
import moe.ouom.neriplayer.data.config.SyncPreferencesConfigSnapshot
import java.util.Locale

class PlayHistorySyncPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class UpdateMode(val intervalMinutes: Int?) {
        IMMEDIATE(null),
        EVERY_10_MINUTES(10),
        EVERY_15_MINUTES(15),
        EVERY_30_MINUTES(30);

        val intervalMillis: Long
            get() = intervalMinutes?.toLong()?.times(MINUTE_MILLIS) ?: 0L

        companion object {
            val selectableModes: List<UpdateMode> = listOf(
                IMMEDIATE,
                EVERY_10_MINUTES,
                EVERY_15_MINUTES,
                EVERY_30_MINUTES
            )

            fun fromStoredName(name: String?): UpdateMode? {
                return when (name?.trim()?.uppercase(Locale.ROOT)) {
                    IMMEDIATE.name -> IMMEDIATE
                    EVERY_10_MINUTES.name, LEGACY_BATCHED_MODE, "BATCHED_10" -> EVERY_10_MINUTES
                    EVERY_15_MINUTES.name, "BATCHED_15" -> EVERY_15_MINUTES
                    EVERY_30_MINUTES.name, "BATCHED_30" -> EVERY_30_MINUTES
                    else -> null
                }
            }
        }
    }

    fun getUpdateMode(legacyModeName: String? = null): UpdateMode {
        if (!prefs.contains(KEY_UPDATE_MODE)) {
            val legacyMode = UpdateMode.fromStoredName(legacyModeName)
            if (legacyMode != null) {
                setUpdateMode(legacyMode)
                return legacyMode
            }
        }
        return UpdateMode.fromStoredName(prefs.getString(KEY_UPDATE_MODE, null))
            ?: UpdateMode.IMMEDIATE
    }

    fun setUpdateMode(mode: UpdateMode) {
        prefs.edit { putString(KEY_UPDATE_MODE, mode.name) }
    }

    fun snapshot(legacyModeName: String? = null): SyncPreferencesConfigSnapshot {
        return SyncPreferencesConfigSnapshot(
            playHistoryUpdateMode = getUpdateMode(legacyModeName).name
        )
    }

    fun restore(
        snapshot: SyncPreferencesConfigSnapshot,
        legacyModeName: String? = null
    ) {
        val mode = UpdateMode.fromStoredName(snapshot.playHistoryUpdateMode)
            ?: UpdateMode.fromStoredName(legacyModeName)
            ?: UpdateMode.IMMEDIATE
        setUpdateMode(mode)
    }

    companion object {
        private const val PREFS_NAME = "play_history_sync_preferences"
        private const val KEY_UPDATE_MODE = "play_history_update_mode"
        private const val LEGACY_BATCHED_MODE = "BATCHED"
        private const val MINUTE_MILLIS = 60_000L
    }
}
