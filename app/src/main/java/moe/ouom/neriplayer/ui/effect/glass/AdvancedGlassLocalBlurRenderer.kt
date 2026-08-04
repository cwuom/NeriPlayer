package moe.ouom.neriplayer.ui.effect.glass

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RenderNode
import android.graphics.RecordingCanvas
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.withSave
import kotlin.math.ceil

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AdvancedGlassLocalBlurRenderer {
    private val sourceNode = RenderNode("AdvancedGlassSource")
    private val downsampledSourceNode = RenderNode("AdvancedGlassDownsampledSource")
    private val effectNodes = mutableListOf<AdvancedGlassLocalBlurNode>()
    private val targets = mutableListOf<AdvancedGlassLocalBlurTarget>()
    private val blurEffects = mutableMapOf<Int, AndroidRenderEffect>()
    private var sourceWidth = -1
    private var sourceHeight = -1
    private var sourceGeneration = NoSourceGeneration
    private var downsampledSourceWidth = -1
    private var downsampledSourceHeight = -1
    private var downsampledSourceFactor = 1
    private var downsampledSourceGeneration = NoSourceGeneration
    private var activeLocalNodeCount = 0

    fun render(
        scope: ContentDrawScope,
        plan: AdvancedGlassLocalBlurPlan,
        freezeFrame: Boolean = false
    ) {
        if (plan.groups.isEmpty() || !scope.drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            scope.drawContent()
            return
        }
        val width = ceil(scope.size.width.toDouble()).toInt()
        val height = ceil(scope.size.height.toDouble()).toInt()
        if (width <= 0 || height <= 0) {
            scope.drawContent()
            return
        }

        if (freezeFrame && hasCachedLocalBlurFrame(width, height)) {
            drawCurrentContentWithCachedLocalBlur(scope)
            return
        }
        recordSource(scope, width, height)
        val blurSourceNode = resolveBlurSourceNode(
            width = width,
            height = height,
            downscaleFactor = plan.downscaleFactor
        )
        targets.clear()
        plan.groups.forEach { group ->
            group.bounds.expand(plan.inputPaddingPx).intersect(
                left = 0f,
                top = 0f,
                right = width.toFloat(),
                bottom = height.toFloat()
            )?.let { inputBounds -> targets += AdvancedGlassLocalBlurTarget(group, inputBounds) }
        }
        if (targets.isEmpty()) {
            activeLocalNodeCount = 0
            scope.drawContext.canvas.nativeCanvas.drawRenderNode(sourceNode)
            return
        }

        while (effectNodes.size < targets.size) {
            effectNodes += AdvancedGlassLocalBlurNode()
        }
        val blurEffect = blurEffectFor(plan.radiusPx / plan.downscaleFactor)
        targets.indices.forEach { index ->
            effectNodes[index].update(
                target = targets[index],
                downscaleFactor = plan.downscaleFactor,
                blurEffect = blurEffect,
                sourceNode = blurSourceNode,
                sourceGeneration = sourceGeneration
            )
        }
        activeLocalNodeCount = targets.size
        drawCachedLocalFrame(scope, activeLocalNodeCount)
    }

    private fun recordSource(scope: ContentDrawScope, width: Int, height: Int) {
        if (sourceWidth != width || sourceHeight != height) {
            sourceNode.setPosition(0, 0, width, height)
            sourceWidth = width
            sourceHeight = height
        }
        sourceNode.record { canvas ->
            val contentScope = scope
            contentScope.draw(
                density = contentScope,
                layoutDirection = contentScope.layoutDirection,
                canvas = Canvas(canvas),
                size = contentScope.size
            ) {
                contentScope.drawContent()
            }
        }
        sourceGeneration += 1L
    }

    private fun resolveBlurSourceNode(
        width: Int,
        height: Int,
        downscaleFactor: Int
    ): RenderNode {
        if (downscaleFactor == 1) return sourceNode

        val downsampledWidth = ceil(width.toDouble() / downscaleFactor).toInt()
        val downsampledHeight = ceil(height.toDouble() / downscaleFactor).toInt()
        if (downsampledSourceWidth != downsampledWidth ||
            downsampledSourceHeight != downsampledHeight ||
            downsampledSourceFactor != downscaleFactor ||
            downsampledSourceGeneration != sourceGeneration
        ) {
            downsampledSourceNode.setPosition(0, 0, downsampledWidth, downsampledHeight)
            downsampledSourceWidth = downsampledWidth
            downsampledSourceHeight = downsampledHeight
            downsampledSourceFactor = downscaleFactor
            downsampledSourceGeneration = sourceGeneration
            downsampledSourceNode.record { canvas ->
                val scale = 1f / downscaleFactor
                canvas.scale(scale, scale)
                canvas.drawRenderNode(sourceNode)
            }
        }
        return downsampledSourceNode
    }

    private fun blurEffectFor(radiusPx: Float): AndroidRenderEffect {
        val key = radiusPx.toBits()
        return blurEffects.getOrPut(key) {
            AndroidRenderEffect.createBlurEffect(
                radiusPx,
                radiusPx,
                Shader.TileMode.CLAMP
            )
        }
    }

    private fun drawCachedLocalFrame(scope: ContentDrawScope, activeNodeCount: Int) {
        with(scope.drawContext.canvas) {
            withSave {
                repeat(activeNodeCount) { index ->
                    clipPath(effectNodes[index].outputPath, ClipOp.Difference)
                }
                nativeCanvas.drawRenderNode(sourceNode)
            }
        }
        repeat(activeNodeCount) { index ->
            with(scope.drawContext.canvas) {
                withSave {
                    clipPath(effectNodes[index].outputPath)
                    nativeCanvas.drawRenderNode(effectNodes[index].renderNode)
                }
            }
        }
    }

    private fun hasCachedLocalBlurFrame(width: Int, height: Int): Boolean =
        activeLocalNodeCount > 0 &&
            sourceGeneration != NoSourceGeneration &&
            sourceWidth == width &&
            sourceHeight == height

    private fun drawCurrentContentWithCachedLocalBlur(scope: ContentDrawScope) {
        with(scope.drawContext.canvas) {
            withSave {
                repeat(activeLocalNodeCount) { index ->
                    clipPath(effectNodes[index].outputPath, ClipOp.Difference)
                }
                scope.drawContent()
            }
        }
        repeat(activeLocalNodeCount) { index ->
            with(scope.drawContext.canvas) {
                withSave {
                    clipPath(effectNodes[index].outputPath)
                    nativeCanvas.drawRenderNode(effectNodes[index].renderNode)
                }
            }
        }
    }
}

private data class AdvancedGlassLocalBlurTarget(
    val group: AdvancedGlassLocalBlurGroup,
    val inputBounds: AdvancedGlassLocalBlurBounds
)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AdvancedGlassLocalBlurNode {
    val renderNode = RenderNode("AdvancedGlassRegion")
    val outputPath = Path()

    private var inputBounds = AdvancedGlassLocalBlurBounds(0f, 0f, 0f, 0f)
    private var regions: List<AdvancedGlassRenderRegion> = emptyList()
    private var appliedBlurEffect: AndroidRenderEffect? = null
    private var downscaleFactor = 1
    private var recordedSourceNode: RenderNode? = null
    private var recordedSourceGeneration = NoSourceGeneration

    fun update(
        target: AdvancedGlassLocalBlurTarget,
        downscaleFactor: Int,
        blurEffect: AndroidRenderEffect,
        sourceNode: RenderNode,
        sourceGeneration: Long
    ) {
        var requiresSourceRecord = false
        if (inputBounds != target.inputBounds || this.downscaleFactor != downscaleFactor) {
            inputBounds = target.inputBounds
            this.downscaleFactor = downscaleFactor
            renderNode.setPosition(
                0,
                0,
                ceil(inputBounds.width.toDouble() / downscaleFactor).toInt().coerceAtLeast(1),
                ceil(inputBounds.height.toDouble() / downscaleFactor).toInt().coerceAtLeast(1)
            )
            renderNode.pivotX = 0f
            renderNode.pivotY = 0f
            renderNode.translationX = inputBounds.left
            renderNode.translationY = inputBounds.top
            renderNode.scaleX = downscaleFactor.toFloat()
            renderNode.scaleY = downscaleFactor.toFloat()
            requiresSourceRecord = true
        }
        if (regions != target.group.regions) {
            regions = target.group.regions
            updateOutputPath()
        }
        if (appliedBlurEffect !== blurEffect) {
            appliedBlurEffect = blurEffect
            renderNode.setRenderEffect(blurEffect)
        }
        if (
            recordedSourceNode !== sourceNode ||
            recordedSourceGeneration != sourceGeneration
        ) {
            recordedSourceNode = sourceNode
            recordedSourceGeneration = sourceGeneration
            requiresSourceRecord = true
        }
        if (requiresSourceRecord) {
            renderNode.record { canvas ->
                canvas.translate(
                    -inputBounds.left / downscaleFactor,
                    -inputBounds.top / downscaleFactor
                )
                canvas.drawRenderNode(sourceNode)
            }
        }
    }

    private fun updateOutputPath() {
        outputPath.updateForRegions(regions)
    }
}

private fun Path.updateForRegions(regions: List<AdvancedGlassRenderRegion>) {
    rewind()
    regions.forEach { region ->
        val radii = region.cornerRadiiPx
        addRoundRect(
            RoundRect(
                left = region.left,
                top = region.top,
                right = region.right,
                bottom = region.bottom,
                topLeftCornerRadius = CornerRadius(radii.topLeft, radii.topLeft),
                topRightCornerRadius = CornerRadius(radii.topRight, radii.topRight),
                bottomRightCornerRadius = CornerRadius(radii.bottomRight, radii.bottomRight),
                bottomLeftCornerRadius = CornerRadius(radii.bottomLeft, radii.bottomLeft)
            )
        )
    }
}

private const val NoSourceGeneration = -1L

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private inline fun RenderNode.record(block: (RecordingCanvas) -> Unit) {
    try {
        beginRecording().apply(block)
    } finally {
        endRecording()
    }
}
