package moe.ouom.neriplayer.core.player

import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一解析网易云播放响应，避免把 JSONObject.NULL 误当成字符串 "null"。
 */
internal object NeteasePlaybackResponseParser {

    internal sealed class PlaybackResult {
        data class Success(
            val url: String,
            val type: String?,
            val notice: Notice? = null
        ) : PlaybackResult()

        object RequiresLogin : PlaybackResult()

        data class Failure(val reason: FailureReason = FailureReason.UNKNOWN) : PlaybackResult()
    }

    internal enum class Notice {
        PREVIEW_CLIP
    }

    internal enum class FailureReason {
        NO_PERMISSION,
        NO_PLAY_URL,
        UNKNOWN
    }

    internal data class DownloadInfo(
        val url: String,
        val type: String?
    )

    fun parsePlayback(rawResponse: String, originalDurationMs: Long): PlaybackResult {
        val root = runCatching { JSONObject(rawResponse) }.getOrElse {
            return PlaybackResult.Failure(FailureReason.UNKNOWN)
        }

        return when (root.optInt("code", -1)) {
            301 -> PlaybackResult.RequiresLogin
            200 -> {
                val data = extractDataObject(root)
                    ?: return PlaybackResult.Failure(FailureReason.NO_PLAY_URL)
                val url = data.optCleanString("url")
                    ?: return PlaybackResult.Failure(classifyFailure(data))
                val notice = if (isPreviewClip(data, originalDurationMs)) {
                    Notice.PREVIEW_CLIP
                } else {
                    null
                }
                PlaybackResult.Success(
                    url = url,
                    type = data.optCleanString("type"),
                    notice = notice
                )
            }

            else -> PlaybackResult.Failure(FailureReason.UNKNOWN)
        }
    }

    fun parseDownloadInfo(rawResponse: String): DownloadInfo? {
        val root = runCatching { JSONObject(rawResponse) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 200) return null

        val data = extractDataObject(root) ?: return null
        val url = data.optCleanString("url") ?: return null
        return DownloadInfo(
            url = url,
            type = data.optCleanString("type")
        )
    }

    private fun classifyFailure(data: JSONObject): FailureReason {
        val dataCode = data.optInt("code", -1)
        val cannotListenReason = data.optJSONObject("freeTrialPrivilege")
            ?.optIntOrNull("cannotListenReason")
        val fee = data.optInt("fee", 0)

        return when {
            dataCode == 404 || cannotListenReason == 1 || fee > 0 -> FailureReason.NO_PERMISSION
            else -> FailureReason.NO_PLAY_URL
        }
    }

    private fun isPreviewClip(data: JSONObject, originalDurationMs: Long): Boolean {
        val previewInfoPresent = data.opt("freeTrialInfo")
            ?.let { it != JSONObject.NULL }
            ?: false
        if (!previewInfoPresent) return false

        // 用户要求：只要接口明确给出 freeTrialInfo，就直接按试听资源提示。
        // originalDurationMs 保留给调用方的结束时长兜底逻辑使用。
        return originalDurationMs >= 0L
    }

    private fun extractDataObject(root: JSONObject): JSONObject? {
        return when (val data = root.opt("data")) {
            is JSONObject -> data
            is JSONArray -> data.optJSONObject(0)
            else -> null
        }
    }

    private fun JSONObject.optCleanString(key: String): String? {
        val value = when (val raw = opt(key)) {
            null, JSONObject.NULL -> null
            is String -> raw.trim()
            else -> raw.toString().trim()
        }
        return value?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        return when (val value = opt(key)) {
            null, JSONObject.NULL -> null
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
