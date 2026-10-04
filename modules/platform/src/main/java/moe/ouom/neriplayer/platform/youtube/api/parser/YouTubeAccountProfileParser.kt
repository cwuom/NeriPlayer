package moe.ouom.neriplayer.platform.youtube.api.parser

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAccountProfile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

internal fun parseYouTubeAccountProfile(root: JSONObject): YouTubeAccountProfile? {
    val headers = findAccountRenderers(root, setOf("activeAccountHeaderRenderer"))
    headers.firstNotNullOfOrNull(::profileFromAccountRenderer)?.let { return it }

    val accounts = findAccountRenderers(root, setOf("accountItemRenderer", "accountItem"))
    val selected = accounts.filter { it.optBoolean("isSelected") || it.optBoolean("selected") }
    if (selected.isNotEmpty()) return selected.firstNotNullOfOrNull(::profileFromAccountRenderer)
    // 多账号菜单未标明当前账号时不能把其它账号资料显示成当前身份
    return accounts.singleOrNull()?.let(::profileFromAccountRenderer)
}

private fun findAccountRenderers(value: Any?, keys: Set<String>): List<JSONObject> = buildList {
    when (value) {
        is JSONObject -> {
            val fields = value.keys()
            while (fields.hasNext()) {
                val key = fields.next()
                val child = value.opt(key)
                if (key in keys && child is JSONObject) add(child)
                addAll(findAccountRenderers(child, keys))
            }
        }
        is JSONArray -> {
            for (index in 0 until value.length()) addAll(findAccountRenderers(value.opt(index), keys))
        }
    }
}

private fun profileFromAccountRenderer(renderer: JSONObject): YouTubeAccountProfile? {
    val nickname = listOf("accountName", "displayName", "channelName", "title")
        .firstNotNullOfOrNull { key -> accountText(renderer.opt(key)) }
        ?: return null
    val avatarUrl = listOf("accountPhoto", "avatar", "thumbnail")
        .firstNotNullOfOrNull { key -> accountThumbnailUrl(renderer.opt(key)) }
    return YouTubeAccountProfile(nickname, avatarUrl)
}

private fun accountText(value: Any?): String? {
    val text = when (value) {
        is String -> value
        is JSONObject -> {
            value.optString("simpleText").ifBlank { value.optString("text") }.ifBlank {
                val runs = value.optJSONArray("runs") ?: return null
                buildString {
                    for (index in 0 until runs.length()) {
                        append(runs.optJSONObject(index)?.optString("text").orEmpty())
                    }
                }
            }
        }
        else -> return null
    }
    return text.trim().takeUnless { it.isEmpty() || it == "null" }
}

private fun accountThumbnailUrl(value: Any?): String? {
    return when (value) {
        is String -> normalizeAccountAvatarUrl(value)
        is JSONObject -> {
            val thumbnails = value.optJSONArray("thumbnails")
            if (thumbnails != null) {
                for (index in thumbnails.length() - 1 downTo 0) {
                    normalizeAccountAvatarUrl(thumbnails.optJSONObject(index)?.optString("url").orEmpty())
                        ?.let { return it }
                }
            }
            accountThumbnailUrl(value.opt("thumbnail"))
        }
        else -> null
    }
}

private fun normalizeAccountAvatarUrl(raw: String): String? {
    val url = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
    val parsed = url.toHttpUrlOrNull() ?: return null
    return parsed.newBuilder().scheme("https").build().toString()
}
