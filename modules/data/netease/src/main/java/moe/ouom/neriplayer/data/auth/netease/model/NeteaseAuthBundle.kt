package moe.ouom.neriplayer.data.auth.netease.model

import org.json.JSONObject

internal val NETEASE_LOGIN_COOKIE_KEYS = listOf(
    "MUSIC_U"
)

data class NeteaseAuthBundle(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
) {
    fun hasLoginCookies(): Boolean {
        return NETEASE_LOGIN_COOKIE_KEYS.any { key -> !cookies[key].isNullOrBlank() }
    }

    fun normalized(savedAt: Long = this.savedAt): NeteaseAuthBundle {
        return copy(
            cookies = LinkedHashMap(cookies.filterKeys { it.isNotBlank() }),
            savedAt = savedAt
        )
    }

    fun toJson(): String {
        return JSONObject().apply {
            put(
                "cookies",
                JSONObject().apply {
                    cookies.forEach { (key, value) -> put(key, value) }
                }
            )
            put("savedAt", savedAt)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): NeteaseAuthBundle {
            return runCatching {
                val root = JSONObject(json)
                val cookiesJson = root.optJSONObject("cookies") ?: JSONObject()
                val cookies = linkedMapOf<String, String>()
                val keys = cookiesJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    cookies[key] = cookiesJson.optString(key, "")
                }
                val savedAt = root.optLong("savedAt", 0L)
                NeteaseAuthBundle(
                    cookies = cookies,
                    savedAt = savedAt
                ).normalized(savedAt = savedAt)
            }.getOrDefault(NeteaseAuthBundle())
        }
    }
}
