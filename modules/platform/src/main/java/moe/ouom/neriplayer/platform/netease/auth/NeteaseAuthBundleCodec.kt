package moe.ouom.neriplayer.platform.netease.auth

import moe.ouom.neriplayer.data.model.netease.auth.NeteaseAuthBundle
import org.json.JSONObject

fun NeteaseAuthBundle.toJson(): String {
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

fun NeteaseAuthBundle.Companion.fromJson(json: String): NeteaseAuthBundle {
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
