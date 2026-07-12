package moe.ouom.neriplayer.util

internal object JsonUtil {
    fun toJson(map: Map<String, Any>): String {
        return moe.ouom.neriplayer.util.json.JsonUtil.toJson(map)
    }

    fun toJsonValue(v: Any?): String {
        return moe.ouom.neriplayer.util.json.JsonUtil.toJsonValue(v)
    }

    fun jsonQuote(s: String?): String {
        return moe.ouom.neriplayer.util.json.JsonUtil.jsonQuote(s)
    }
}
