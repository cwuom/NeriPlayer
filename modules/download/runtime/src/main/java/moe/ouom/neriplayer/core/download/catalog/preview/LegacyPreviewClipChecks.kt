package moe.ouom.neriplayer.core.download.catalog.preview

import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.json.JSONArray
import org.json.JSONObject

internal const val LEGACY_PREVIEW_CLIP_MAX_DURATION_MS = 90_000L
internal const val LEGACY_PREVIEW_CLIP_PROBES_PER_PASS = 128

internal data class LegacyPreviewClipCheck(
    val sizeBytes: Long,
    val audioDurationMs: Long?,
    val catalogDurationMs: Long
) {
    val previewClip: Boolean
        get() = isLegacyPreviewClipDuration(catalogDurationMs, audioDurationMs)
}

internal data class LegacyPreviewClipCandidate(
    val reference: String,
    val sizeBytes: Long,
    val catalogDurationMs: Long
)

internal data class LegacyPreviewClipPlan(
    val retained: Map<String, LegacyPreviewClipCheck>,
    val pending: List<LegacyPreviewClipCandidate>
)

internal fun isLegacyPreviewClipDuration(catalogDurationMs: Long, audioDurationMs: Long?): Boolean {
    val actual = audioDurationMs?.takeIf { it > 0L } ?: return false
    return catalogDurationMs > 0L &&
        actual <= LEGACY_PREVIEW_CLIP_MAX_DURATION_MS &&
        actual * 2 <= catalogDurationMs
}

internal fun legacyPreviewClipCandidate(
    reference: String,
    sizeBytes: Long,
    metadata: DownloadedAudioMetadata
): LegacyPreviewClipCandidate? =
    LegacyPreviewClipCandidate(reference, sizeBytes, metadata.durationMs)
        .takeIf { isFinalizedLegacyNeteaseDownload(metadata) }

// 只有网易云会把试听片段当完整音源返回；B 站分 P 等来源的音频本就可能短于目录时长
private fun isFinalizedLegacyNeteaseDownload(metadata: DownloadedAudioMetadata): Boolean =
    metadata.createdAtSource.equals(LEGACY_V15_CREATED_AT_SOURCE, ignoreCase = true) &&
        metadata.downloadFinalized == true &&
        metadata.durationMs > 0L &&
        downloadSourceChannel(metadata).equals("netease", ignoreCase = true)

private fun downloadSourceChannel(metadata: DownloadedAudioMetadata): String? =
    metadata.channelId?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
        ?: metadata.identityAlbum

internal fun planLegacyPreviewClipChecks(
    stored: Map<String, LegacyPreviewClipCheck>,
    candidates: List<LegacyPreviewClipCandidate>
): LegacyPreviewClipPlan {
    val retained = LinkedHashMap<String, LegacyPreviewClipCheck>()
    val pending = ArrayList<LegacyPreviewClipCandidate>()
    candidates.forEach { candidate ->
        val check = stored[candidate.reference]
        if (
            check?.audioDurationMs != null &&
                check.sizeBytes == candidate.sizeBytes &&
                check.catalogDurationMs == candidate.catalogDurationMs
        ) {
            retained[candidate.reference] = check
        } else {
            pending += candidate
        }
    }
    return LegacyPreviewClipPlan(retained, pending)
}

internal fun legacyPreviewClipSizes(checks: Map<String, LegacyPreviewClipCheck>): Map<String, Long> =
    checks.filterValues(LegacyPreviewClipCheck::previewClip).mapValues { (_, check) -> check.sizeBytes }

internal data class LegacyPreviewClipPassResult(
    val checks: Map<String, LegacyPreviewClipCheck>,
    val changed: Boolean,
    val probedCount: Int,
    val remainingCount: Int
)

/** candidates 为 null 表示目录列举不完整，此时保留已有记录，不做任何删减 */
internal suspend fun runLegacyPreviewClipPass(
    stored: Map<String, LegacyPreviewClipCheck>,
    candidates: List<LegacyPreviewClipCandidate>?,
    probeDurationMs: suspend (reference: String) -> Long?,
    probesPerPass: Int = LEGACY_PREVIEW_CLIP_PROBES_PER_PASS
): LegacyPreviewClipPassResult {
    if (candidates == null) {
        return LegacyPreviewClipPassResult(stored, changed = false, probedCount = 0, remainingCount = 0)
    }
    val plan = planLegacyPreviewClipChecks(stored, candidates)
    val probed = plan.pending.take(probesPerPass).associate { candidate ->
        candidate.reference to LegacyPreviewClipCheck(
            sizeBytes = candidate.sizeBytes,
            audioDurationMs = probeDurationMs(candidate.reference),
            catalogDurationMs = candidate.catalogDurationMs
        )
    }
    val checks = plan.retained + probed
    return LegacyPreviewClipPassResult(
        checks = checks,
        changed = checks != stored,
        probedCount = probed.size,
        remainingCount = plan.pending.size - probed.size
    )
}

internal object LegacyPreviewClipCheckCodec {
    const val METADATA_KEY = "legacy_download_preview_clip_checks"

    fun encode(checks: Map<String, LegacyPreviewClipCheck>): String = JSONObject().apply {
        checks.forEach { (reference, check) ->
            put(
                reference,
                JSONArray()
                    .put(check.sizeBytes)
                    .put(check.audioDurationMs ?: JSONObject.NULL)
                    .put(check.catalogDurationMs)
            )
        }
    }.toString()

    fun decode(raw: String?): Map<String, LegacyPreviewClipCheck> {
        val root = parseRoot(raw) ?: return emptyMap()
        return root.keys().asSequence()
            .mapNotNull { reference -> decodeCheck(root.optJSONArray(reference))?.let { reference to it } }
            .toMap(LinkedHashMap())
    }

    private fun parseRoot(raw: String?): JSONObject? =
        raw?.takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun decodeCheck(values: JSONArray?): LegacyPreviewClipCheck? {
        if (values == null || values.length() < 3) return null
        return LegacyPreviewClipCheck(
            sizeBytes = values.optLong(0, -1L),
            audioDurationMs = values.optLong(1, 0L).takeIf { !values.isNull(1) && it > 0L },
            catalogDurationMs = values.optLong(2, 0L)
        )
    }
}

private const val LEGACY_V15_CREATED_AT_SOURCE = "LEGACY_V15"
