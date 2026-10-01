package moe.ouom.neriplayer.platform.bilibili.auth

import moe.ouom.neriplayer.data.model.bilibili.auth.BiliAuthBundle
import org.json.JSONObject

fun BiliAuthBundle.toJson(): String {
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

fun BiliAuthBundle.Companion.fromJson(json: String): BiliAuthBundle {
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
        BiliAuthBundle(
            cookies = cookies,
            savedAt = savedAt
        ).normalized(savedAt = savedAt)
    }.getOrDefault(BiliAuthBundle())
}
