package moe.ouom.neriplayer.ui.effect.glass

import kotlin.math.max
import kotlin.math.min

internal data class AdvancedGlassLocalBlurPlan(
    val radiusPx: Float,
    val downscaleFactor: Int,
    val inputPaddingPx: Float,
    val groups: List<AdvancedGlassLocalBlurGroup>,
    val rendererCacheKey: Int = DefaultLocalBlurRendererCacheKey
) {
    val estimatedBlurredInputArea: Double
        get() = groups.sumOf { group ->
            group.bounds.expandedArea(inputPaddingPx)
        } / (downscaleFactor.toDouble() * downscaleFactor.toDouble())
}

internal data class AdvancedGlassLocalBlurGroup(
    val bounds: AdvancedGlassLocalBlurBounds,
    val regions: List<AdvancedGlassRenderRegion>
)

internal data class AdvancedGlassLocalBlurBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    fun union(other: AdvancedGlassLocalBlurBounds): AdvancedGlassLocalBlurBounds =
        AdvancedGlassLocalBlurBounds(
            left = min(left, other.left),
            top = min(top, other.top),
            right = max(right, other.right),
            bottom = max(bottom, other.bottom)
        )

    fun expand(paddingPx: Float): AdvancedGlassLocalBlurBounds = AdvancedGlassLocalBlurBounds(
        left = left - paddingPx,
        top = top - paddingPx,
        right = right + paddingPx,
        bottom = bottom + paddingPx
    )

    fun intersect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): AdvancedGlassLocalBlurBounds? {
        val intersected = AdvancedGlassLocalBlurBounds(
            left = max(this.left, left),
            top = max(this.top, top),
            right = min(this.right, right),
            bottom = min(this.bottom, bottom)
        )
        return intersected.takeIf { it.width > 0f && it.height > 0f }
    }

    fun expandedArea(paddingPx: Float): Double {
        val expandedWidth = (width + paddingPx * 2f).coerceAtLeast(0f)
        val expandedHeight = (height + paddingPx * 2f).coerceAtLeast(0f)
        return expandedWidth.toDouble() * expandedHeight.toDouble()
    }
}

internal fun resolveAdvancedGlassLocalBlurPlan(
    regions: List<AdvancedGlassRenderRegion>,
    radiusPx: Float,
    maximumMergedInputAreaRatio: Float,
    downscaleFactor: Int = 1,
    rendererCacheKey: Int = DefaultLocalBlurRendererCacheKey
): AdvancedGlassLocalBlurPlan? {
    if (!radiusPx.isFinite() || radiusPx <= 0f ||
        !maximumMergedInputAreaRatio.isFinite() ||
        maximumMergedInputAreaRatio < 1f ||
        downscaleFactor !in SupportedLocalBlurDownscaleFactors
    ) {
        return null
    }
    val inputPaddingPx = radiusPx * LocalBlurInputPaddingRadiusMultiplier
    val pending = regions
        .filter(AdvancedGlassRenderRegion::hasValidBounds)
        .sortedWith(compareBy<AdvancedGlassRenderRegion> { it.top }.thenBy { it.left })
        .toMutableList()
    if (pending.isEmpty()) return null

    val groups = mutableListOf<AdvancedGlassLocalBlurGroup>()
    while (pending.isNotEmpty()) {
        val groupedRegions = mutableListOf(pending.removeAt(0))
        var groupBounds = groupedRegions.single().localBlurBounds()
        while (true) {
            val next = pending.indices
                .map { index ->
                    val candidate = pending[index]
                    val candidateBounds = candidate.localBlurBounds()
                    val mergedBounds = groupBounds.union(candidateBounds)
                    val separateArea = groupBounds.expandedArea(inputPaddingPx) +
                        candidateBounds.expandedArea(inputPaddingPx)
                    LocalBlurMergeCandidate(
                        index = index,
                        bounds = mergedBounds,
                        costRatio = mergedBounds.expandedArea(inputPaddingPx) / separateArea
                    )
                }
                .filter { candidate ->
                    candidate.costRatio <= maximumMergedInputAreaRatio.toDouble()
                }
                .minByOrNull(LocalBlurMergeCandidate::costRatio)
                ?: break
            groupedRegions += pending.removeAt(next.index)
            groupBounds = next.bounds
        }
        groups += AdvancedGlassLocalBlurGroup(
            bounds = groupBounds,
            regions = groupedRegions
        )
    }
    return AdvancedGlassLocalBlurPlan(
        radiusPx = radiusPx,
        downscaleFactor = downscaleFactor,
        inputPaddingPx = inputPaddingPx,
        groups = groups,
        rendererCacheKey = rendererCacheKey
    )
}

private data class LocalBlurMergeCandidate(
    val index: Int,
    val bounds: AdvancedGlassLocalBlurBounds,
    val costRatio: Double
)

private fun AdvancedGlassRenderRegion.hasValidBounds(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        right > left && bottom > top

private fun AdvancedGlassRenderRegion.localBlurBounds() = AdvancedGlassLocalBlurBounds(
    left = left,
    top = top,
    right = right,
    bottom = bottom
)

private const val LocalBlurInputPaddingRadiusMultiplier = 2f
private const val DefaultLocalBlurRendererCacheKey = 0
private val SupportedLocalBlurDownscaleFactors = setOf(1, 2, 4)
