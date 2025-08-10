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
 *
 * File: moe.ouom.neriplayer.data/NeteaseCookieRepository
 * Created: 2025/8/9
 */

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.cookieDataStore by preferencesDataStore("auth_store")

object CookieKeys {
    val NETEASE_COOKIE_JSON = stringPreferencesKey("netease_cookie_json")
}

class NeteaseCookieRepository(private val context: Context) {

    /** Flow 形式读取 Cookie */
    val cookieFlow: Flow<Map<String, String>> =
        context.cookieDataStore.data.map { prefs ->
            val json = prefs[CookieKeys.NETEASE_COOKIE_JSON] ?: "{}"
            jsonToMap(json)
        }

    /** 一次性读取 */
    suspend fun getCookiesOnce(): Map<String, String> {
        val prefs = context.cookieDataStore.data.map { it }.first()
        val json = prefs[CookieKeys.NETEASE_COOKIE_JSON] ?: "{}"
        return jsonToMap(json)
    }

    /** 保存 Cookie */
    suspend fun saveCookies(cookies: Map<String, String>) {
        context.cookieDataStore.edit { prefs ->
            prefs[CookieKeys.NETEASE_COOKIE_JSON] = mapToJson(cookies)
        }
    }

    /** 清空 Cookie */
    suspend fun clear() {
        context.cookieDataStore.edit { prefs ->
            prefs[CookieKeys.NETEASE_COOKIE_JSON] = "{}"
        }
    }

    private fun mapToJson(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun jsonToMap(json: String): Map<String, String> {
        val obj = JSONObject(json)
        val result = mutableMapOf<String, String>()
        for (key in obj.keys()) {
            result[key] = obj.optString(key, "")
        }
        return result
    }
}
