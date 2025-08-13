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
 * File: moe.ouom.neriplayer.data/BiliCookieRepository
 * Created: 2025/8/13
 */

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONObject

private val Context.biliCookieStore by preferencesDataStore("bili_auth_store")

object BiliCookieKeys {
    val COOKIE_JSON = stringPreferencesKey("bili_cookie_json")
}

class BiliCookieRepository(private val context: Context) {

    /** 以 Flow 形式观察 Cookie Map */
    val cookieFlow: Flow<Map<String, String>> =
        context.biliCookieStore.data.map { prefs ->
            val json = prefs[BiliCookieKeys.COOKIE_JSON] ?: "{}"
            jsonToMap(json)
        }

    /** 一次性读取 */
    suspend fun getCookiesOnce(): Map<String, String> {
        val prefs = context.biliCookieStore.data.first()
        val json = prefs[BiliCookieKeys.COOKIE_JSON] ?: "{}"
        return jsonToMap(json)
    }

    /** 原子覆盖 */
    suspend fun saveCookies(cookies: Map<String, String>) {
        clear()
        context.biliCookieStore.edit { it[BiliCookieKeys.COOKIE_JSON] = mapToJson(cookies) }
        NPLogger.d("NERI-BiliCookieRepo", "Saved Bili cookies: keys=${cookies.keys.joinToString()}")
    }

    /** 清空 */
    suspend fun clear() {
        context.biliCookieStore.edit { it[BiliCookieKeys.COOKIE_JSON] = "{}" }
        NPLogger.d("NERI-BiliCookieRepo", "Cleared Bili cookies")
    }

    private fun mapToJson(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun jsonToMap(json: String): Map<String, String> {
        val obj = JSONObject(json)
        val out = LinkedHashMap<String, String>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = obj.optString(k, "")
        }
        return out
    }
}