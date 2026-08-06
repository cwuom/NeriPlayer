package moe.ouom.neriplayer.core.player.lyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.withSave
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_RENDER_STYLE_OUTLINE
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_RENDER_STYLE_SHADOW
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal class AnimatedOutlinedLyricTextView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val baseFillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val eraseFillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val edgeMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeMaskXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val eraseFillXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val smoothInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val linearInterpolator = LinearInterpolator()

    private var lyricText = ""
    private var revealProgress = 1f
    private var alignmentFactor = 0.5f
    private var targetAlignmentFactor = 0.5f
    private var scrollOffset = 0f
    private var playbackActive = false
    private var renderStyle = FLOATING_LYRICS_RENDER_STYLE_SHADOW
    private var shadowBlurRadiusPx = 0f
    private var shadowOffsetYPx = 0f
    private var appliedStyle: LyricTextStyle? = null
    private var scrollStartRequestId = 0L
    private var revealAnimator: ValueAnimator? = null
    private var alignmentAnimator: ValueAnimator? = null
    private var scrollAnimator: ValueAnimator? = null
    private var edgeMaskShader: LinearGradient? = null
    private var edgeMaskWidthPx = 0f
    private var edgeMaskFadeWidthPx = 0f

    init {
        fillPaint.style = Paint.Style.FILL
        baseFillPaint.style = Paint.Style.FILL
        eraseFillPaint.style = Paint.Style.FILL
        outlinePaint.style = Paint.Style.STROKE
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.strokeCap = Paint.Cap.ROUND
        setPadding(dp(3), 0, dp(3), 0)
    }

    fun setLyricText(
        nextText: String,
        revealDurationMs: Long? = null,
        revealAnimationEnabled: Boolean = true
    ) {
        if (lyricText == nextText) {
            return
        }
        lyricText = nextText
        stopScroll(resetOffset = true)
        requestLayout()
        if (revealAnimationEnabled) {
            startRevealAnimation(revealDurationMs)
        } else {
            showTextWithoutReveal()
        }
    }

    fun setLyricStyle(
        textColor: Int,
        effectColor: Int,
        textSizePx: Float,
        effectWidthPx: Float,
        renderStyle: String,
        bold: Boolean
    ) {
        val nextStyle = LyricTextStyle(
            textColor = textColor,
            effectColor = effectColor,
            textSizePx = textSizePx,
            effectWidthPx = effectWidthPx.coerceAtLeast(0f),
            renderStyle = if (renderStyle == FLOATING_LYRICS_RENDER_STYLE_OUTLINE) {
                FLOATING_LYRICS_RENDER_STYLE_OUTLINE
            } else {
                FLOATING_LYRICS_RENDER_STYLE_SHADOW
            },
            bold = bold
        )
        if (appliedStyle == nextStyle) {
            return
        }
        appliedStyle = nextStyle
        this.renderStyle = nextStyle.renderStyle
        val typeface = Typeface.create(
            Typeface.DEFAULT,
            if (nextStyle.bold) Typeface.BOLD else Typeface.NORMAL
        )
        val usesOutline = this.renderStyle == FLOATING_LYRICS_RENDER_STYLE_OUTLINE
        shadowBlurRadiusPx = if (usesOutline) 0f else nextStyle.effectWidthPx
        shadowOffsetYPx = if (usesOutline) 0f else nextStyle.effectWidthPx * SHADOW_OFFSET_RATIO
        val horizontalPadding = if (usesOutline) {
            ceil(nextStyle.effectWidthPx + dp(1)).toInt()
        } else {
            ceil(shadowBlurRadiusPx + dp(1)).toInt()
        }
        val verticalPadding = if (usesOutline) {
            ceil(nextStyle.effectWidthPx * 0.5f).toInt()
        } else {
            ceil(shadowBlurRadiusPx + abs(shadowOffsetYPx) + dp(1)).toInt()
        }
        fillPaint.color = nextStyle.textColor
        fillPaint.textSize = nextStyle.textSizePx
        fillPaint.typeface = typeface
        baseFillPaint.color = nextStyle.textColor
        baseFillPaint.textSize = nextStyle.textSizePx
        baseFillPaint.typeface = typeface
        baseFillPaint.clearShadowLayer()
        eraseFillPaint.color = 0xFFFFFFFF.toInt()
        eraseFillPaint.textSize = nextStyle.textSizePx
        eraseFillPaint.typeface = typeface
        eraseFillPaint.clearShadowLayer()
        outlinePaint.color = nextStyle.effectColor
        outlinePaint.textSize = nextStyle.textSizePx
        outlinePaint.typeface = typeface
        outlinePaint.strokeWidth = if (usesOutline) nextStyle.effectWidthPx else 0f
        if (usesOutline || shadowBlurRadiusPx <= 0f) {
            fillPaint.clearShadowLayer()
            setLayerType(LAYER_TYPE_NONE, null)
        } else {
            fillPaint.setShadowLayer(
                shadowBlurRadiusPx,
                0f,
                shadowOffsetYPx,
                nextStyle.effectColor
            )
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        requestLayout()
        invalidate()
        restartScrollAfterLayout()
    }

    fun setAlignmentFactor(nextFactor: Float) {
        val normalized = nextFactor.coerceIn(0f, 1f)
        if (abs(targetAlignmentFactor - normalized) < 0.001f) {
            return
        }
        targetAlignmentFactor = normalized
        alignmentAnimator?.cancel()
        alignmentAnimator = ValueAnimator.ofFloat(alignmentFactor, normalized).apply {
            var canceled = false
            duration = 420L
            interpolator = smoothInterpolator
            addUpdateListener { animator ->
                alignmentFactor = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        alignmentFactor = normalized
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    fun setRevealAnimationEnabled(enabled: Boolean) {
        if (!enabled && revealProgress < 1f) {
            showTextWithoutReveal()
        }
    }

    fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) {
            return
        }
        playbackActive = active
        if (active) {
            val animator = scrollAnimator
            if (animator?.isPaused == true) {
                animator.resume()
            } else {
                restartScrollAfterLayout()
            }
        } else {
            scrollStartRequestId += 1
            scrollAnimator?.takeIf { it.isStarted }?.pause()
        }
    }

    internal fun refreshScrollAfterLayout() {
        restartScrollAfterLayout()
    }

    fun preferredMeasuredHeightPx(): Int {
        val fontMetrics = fillPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        return ceil(textHeight + outlinePaint.strokeWidth + paddingTop + paddingBottom)
            .toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = fillPaint.measureText(lyricText).takeIf { it > 0f } ?: 1f
        val fontMetrics = fillPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val desiredWidth = ceil(textWidth + outlinePaint.strokeWidth * 2f + paddingLeft + paddingRight)
            .toInt()
        val desiredHeight = ceil(textHeight + outlinePaint.strokeWidth + paddingTop + paddingBottom)
            .toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lyricText.isBlank()) {
            return
        }
        if (revealProgress <= 0f) {
            return
        }
        val outlineWidth = outlinePaint.strokeWidth
        val contentLeft = paddingLeft + outlineWidth
        val contentRight = width - paddingRight - outlineWidth
        val contentWidth = (contentRight - contentLeft).coerceAtLeast(1f)
        val fullTextWidth = fillPaint.measureText(lyricText)
        val overflow = (fullTextWidth - contentWidth).coerceAtLeast(0f)
        val edgeMaskProgress = resolveEdgeMaskProgress(
            scrollOffsetPx = scrollOffset,
            overflowPx = overflow,
            activationDistancePx = dp(EDGE_MASK_ACTIVATION_DISTANCE_DP).toFloat(),
            transitionDistancePx = dp(EDGE_MASK_TRANSITION_DISTANCE_DP).toFloat()
        )
        val baseX = if (fullTextWidth <= contentWidth) {
            contentLeft + (contentWidth - fullTextWidth) * alignmentFactor
        } else {
            contentLeft - scrollOffset
        }
        val fontMetrics = fillPaint.fontMetrics
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val baseline = paddingTop + (contentHeight - textHeight) / 2f - fontMetrics.ascent

        val usesShadow = renderStyle == FLOATING_LYRICS_RENDER_STYLE_SHADOW
        val revealBoundLeft = if (usesShadow) 0f else contentLeft
        val revealBoundRight = if (usesShadow) width.toFloat() else contentRight
        val revealEffectExtent = if (usesShadow) {
            shadowBlurRadiusPx + abs(shadowOffsetYPx)
        } else {
            outlineWidth
        }
        val revealLeft = if (revealProgress >= 1f) {
            revealBoundLeft
        } else {
            (baseX - revealEffectExtent).coerceIn(revealBoundLeft, revealBoundRight)
        }
        val revealRight = if (revealProgress >= 1f) {
            revealBoundRight
        } else {
            (baseX + fullTextWidth * revealProgress + revealEffectExtent)
                .coerceIn(revealBoundLeft, revealBoundRight)
        }
        if (revealRight <= revealLeft) {
            return
        }
        val hasTextEffect = outlinePaint.strokeWidth > 0f || shadowBlurRadiusPx > 0f
        if (hasTextEffect) {
            canvas.withSave {
                clipRect(revealLeft, 0f, revealRight, height.toFloat())
                drawText(lyricText, baseX, baseline, baseFillPaint)
            }
            val layerSaveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.withSave {
                clipRect(revealLeft, 0f, revealRight, height.toFloat())
                if (outlinePaint.strokeWidth > 0f) {
                    drawText(lyricText, baseX, baseline, outlinePaint)
                } else {
                    drawText(lyricText, baseX, baseline, fillPaint)
                }
                eraseFillPaint.xfermode = eraseFillXfermode
                drawText(lyricText, baseX, baseline, eraseFillPaint)
                eraseFillPaint.xfermode = null
            }
            if (edgeMaskProgress > 0f) {
                drawEdgeMask(canvas, contentWidth, edgeMaskProgress)
            }
            canvas.restoreToCount(layerSaveCount)
        } else {
            canvas.withSave {
                clipRect(revealLeft, 0f, revealRight, height.toFloat())
                drawText(lyricText, baseX, baseline, baseFillPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        super.onDetachedFromWindow()
    }

    private fun startRevealAnimation(durationMs: Long? = null) {
        revealAnimator?.cancel()
        stopScroll()
        revealProgress = if (lyricText.isBlank()) 1f else 0f
        invalidate()
        if (lyricText.isBlank()) {
            return
        }
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            var canceled = false
            duration = durationMs ?: resolveRevealDurationMs(lyricText)
            interpolator = linearInterpolator
            addUpdateListener { animator ->
                revealProgress = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        revealProgress = 1f
                        restartScrollAfterLayout()
                    }
                }
            })
            start()
        }
    }

    private fun showTextWithoutReveal() {
        revealAnimator?.cancel()
        stopScroll()
        revealProgress = 1f
        invalidate()
        restartScrollAfterLayout()
    }

    private fun restartScrollAfterLayout() {
        stopScroll(resetOffset = true)
        if (!playbackActive || revealProgress < 1f || lyricText.isBlank()) {
            return
        }
        invalidate()
        val requestId = scrollStartRequestId
        val expectedText = lyricText
        post {
            if (requestId == scrollStartRequestId && expectedText == lyricText && revealProgress >= 1f) {
                startScrollIfNeeded()
            }
        }
    }

    private fun startScrollIfNeeded() {
        if (!playbackActive || width <= 0 || lyricText.isBlank()) {
            return
        }
        val contentWidth = (width - paddingLeft - paddingRight - outlinePaint.strokeWidth * 2f)
            .coerceAtLeast(1f)
        val overflow = (fillPaint.measureText(lyricText) - contentWidth).coerceAtLeast(0f)
        if (!shouldStartScroll(
                playbackActive = playbackActive,
                viewWidthPx = width,
                contentWidthPx = contentWidth,
                textWidthPx = fillPaint.measureText(lyricText),
                thresholdPx = dp(MIN_SCROLL_OVERFLOW_DP).toFloat()
            )
        ) {
            return
        }
        val pxPerSecond = dp(38).coerceAtLeast(1).toFloat()
        val durationMs = ((overflow / pxPerSecond) * 1000f)
            .roundToLong()
            .coerceIn(2200L, 9000L)
        scrollAnimator = ValueAnimator.ofFloat(0f, overflow).apply {
            startDelay = 520L
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = linearInterpolator
            addUpdateListener { animator ->
                scrollOffset = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun cancelAnimations() {
        revealAnimator?.cancel()
        alignmentAnimator?.cancel()
        stopScroll()
        revealAnimator = null
        alignmentAnimator = null
    }

    private fun stopScroll(resetOffset: Boolean = false) {
        scrollStartRequestId += 1
        scrollAnimator?.cancel()
        scrollAnimator = null
        if (resetOffset) {
            scrollOffset = 0f
        }
    }

    private fun drawEdgeMask(
        canvas: Canvas,
        contentWidthPx: Float,
        progress: Float
    ) {
        val normalizedProgress = progress.coerceIn(0f, 1f)
        if (normalizedProgress <= 0f) {
            return
        }
        val fadeWidthPx = resolveEdgeFadeWidthPx(
            contentWidthPx = contentWidthPx,
            density = resources.displayMetrics.density
        )
        val viewWidthPx = width.toFloat()
        if (viewWidthPx <= 0f || fadeWidthPx <= 0f) {
            return
        }
        if (edgeMaskShader == null ||
            edgeMaskWidthPx != viewWidthPx ||
            edgeMaskFadeWidthPx != fadeWidthPx
        ) {
            val edgeFraction = (fadeWidthPx / viewWidthPx).coerceIn(
                0.01f,
                EDGE_FADE_MAX_FRACTION
            )
            edgeMaskShader = LinearGradient(
                0f,
                0f,
                viewWidthPx,
                0f,
                intArrayOf(
                    android.graphics.Color.BLACK,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.BLACK
                ),
                floatArrayOf(0f, edgeFraction, 1f - edgeFraction, 1f),
                Shader.TileMode.CLAMP
            )
            edgeMaskWidthPx = viewWidthPx
            edgeMaskFadeWidthPx = fadeWidthPx
        }
        edgeMaskPaint.shader = edgeMaskShader
        edgeMaskPaint.xfermode = edgeMaskXfermode
        edgeMaskPaint.alpha = (normalizedProgress * 255f).roundToInt()
        canvas.drawRect(0f, 0f, viewWidthPx, height.toFloat(), edgeMaskPaint)
        edgeMaskPaint.alpha = 255
        edgeMaskPaint.xfermode = null
        edgeMaskPaint.shader = null
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToLong().toInt()
    }

    private data class LyricTextStyle(
        val textColor: Int,
        val effectColor: Int,
        val textSizePx: Float,
        val effectWidthPx: Float,
        val renderStyle: String,
        val bold: Boolean
    )

    companion object {
        private const val SHORT_REVEAL_CHARACTER_COUNT = 10
        private const val SHORT_REVEAL_DURATION_PER_CHARACTER_MS = 36L
        private const val LONG_REVEAL_DURATION_PER_CHARACTER_MS = 18L
        private const val MIN_REVEAL_DURATION_MS = 360L
        private const val MAX_REVEAL_DURATION_MS = 900L
        private const val SHADOW_OFFSET_RATIO = 0.5f
        private const val MIN_SCROLL_OVERFLOW_DP = 2
        private const val EDGE_MASK_ACTIVATION_DISTANCE_DP = 8
        private const val EDGE_MASK_TRANSITION_DISTANCE_DP = 12
        private const val EDGE_FADE_WIDTH_DP = 24f
        private const val EDGE_FADE_MAX_FRACTION = 0.22f

        fun resolveRevealDurationMs(text: String): Long {
            val characterCount = text.codePointCount(0, text.length)
            val shortCharacterCount = characterCount.coerceAtMost(SHORT_REVEAL_CHARACTER_COUNT)
            val longCharacterCount = characterCount - shortCharacterCount
            return (
                shortCharacterCount.toLong() * SHORT_REVEAL_DURATION_PER_CHARACTER_MS +
                    longCharacterCount.toLong() * LONG_REVEAL_DURATION_PER_CHARACTER_MS
                ).coerceIn(MIN_REVEAL_DURATION_MS, MAX_REVEAL_DURATION_MS)
        }

        internal fun shouldStartScroll(
            playbackActive: Boolean,
            viewWidthPx: Int,
            contentWidthPx: Float,
            textWidthPx: Float,
            thresholdPx: Float
        ): Boolean {
            return playbackActive && viewWidthPx > 0 &&
                textWidthPx - contentWidthPx > thresholdPx
        }

        internal fun resolveEdgeMaskProgress(
            scrollOffsetPx: Float,
            overflowPx: Float,
            activationDistancePx: Float,
            transitionDistancePx: Float
        ): Float {
            if (!scrollOffsetPx.isFinite() || !overflowPx.isFinite()) {
                return 0f
            }
            val overflow = overflowPx.coerceAtLeast(0f)
            val activationDistance = activationDistancePx
                .takeIf { it.isFinite() }
                ?.coerceAtLeast(0f)
                ?: return 0f
            val availableFadeDistance = overflow * 0.5f - activationDistance
            if (availableFadeDistance <= 0f) {
                return 0f
            }
            val transitionDistance = transitionDistancePx
                .takeIf { it.isFinite() && it > 0f }
                ?.coerceAtMost(availableFadeDistance)
                ?: return 0f
            val clampedOffset = scrollOffsetPx.coerceIn(0f, overflow)
            val distanceFromEndpoint = minOf(clampedOffset, overflow - clampedOffset)
            val progress = ((distanceFromEndpoint - activationDistance) / transitionDistance)
                .coerceIn(0f, 1f)
            return progress * progress * (3f - 2f * progress)
        }

        internal fun resolveEdgeFadeWidthPx(
            contentWidthPx: Float,
            density: Float
        ): Float {
            val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
            return minOf(
                EDGE_FADE_WIDTH_DP * safeDensity,
                contentWidthPx.coerceAtLeast(1f) * EDGE_FADE_MAX_FRACTION
            )
        }
    }
}
