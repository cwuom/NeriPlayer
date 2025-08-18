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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject

private val Context.cookieDataStore by preferencesDataStore("auth_store")

object CookieKeys {
    val NETEASE_COOKIE_JSON = stringPreferencesKey("netease_cookie_json")
}

class NeteaseCookieRepository(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _cookieFlow = MutableStateFlow(runBlocking { getCookiesOnce() })

    val cookieFlow: StateFlow<Map<String, String>> = _cookieFlow.asStateFlow()

    init {
        scope.launch {
            context.cookieDataStore.data.map { prefs ->
                val json = prefs[CookieKeys.NETEASE_COOKIE_JSON] ?: "{}"
                jsonToMap(json)
            }.collect { newCookies ->
                _cookieFlow.value = newCookies
            }
        }
    }

    /** 一次性读取 */
    suspend fun getCookiesOnce(): Map<String, String> {
        val prefs = context.cookieDataStore.data.first()
        val json = prefs[CookieKeys.NETEASE_COOKIE_JSON] ?: "{}"
        return jsonToMap(json)
    }

    /** 保存 Cookie */
    suspend fun saveCookies(cookies: Map<String, String>) {
        // 4. 保存和清除方法不变，DataStore 的变化会通过 init 中的 collect 自动更新 StateFlow
        context.cookieDataStore.edit { prefs ->
            prefs[CookieKeys.NETEASE_COOKIE_JSON] = mapToJson(cookies)
        }
        NPLogger.d("NERI-CookieRepo", "Saved cookies to DataStore: keys=${cookies.keys.joinToString()}")
    }

    /** 清空 Cookie */
    suspend fun clear() {
        context.cookieDataStore.edit { prefs ->
            prefs[CookieKeys.NETEASE_COOKIE_JSON] = "{}"
        }
        NPLogger.d("NERI-CookieRepo", "Cleared all saved cookies.")
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