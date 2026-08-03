package moe.ouom.neriplayer.core.player.lyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.withSave
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_RENDER_STYLE_OUTLINE
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_RENDER_STYLE_SHADOW
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

internal class AnimatedOutlinedLyricTextView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val smoothInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val linearInterpolator = LinearInterpolator()

    private var lyricText = ""
    private var revealProgress = 1f
    private var alignmentFactor = 0.5f
    private var targetAlignmentFactor = 0.5f
    private var scrollOffset = 0f
    private var renderStyle = FLOATING_LYRICS_RENDER_STYLE_SHADOW
    private var shadowBlurRadiusPx = 0f
    private var shadowOffsetYPx = 0f
    private var appliedStyle: LyricTextStyle? = null
    private var scrollStartRequestId = 0L
    private var revealAnimator: ValueAnimator? = null
    private var alignmentAnimator: ValueAnimator? = null
    private var scrollAnimator: ValueAnimator? = null

    init {
        fillPaint.style = Paint.Style.FILL
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
        canvas.withSave {
            clipRect(revealLeft, 0f, revealRight, height.toFloat())
            if (outlinePaint.strokeWidth > 0f) {
                drawText(lyricText, baseX, baseline, outlinePaint)
            }
            drawText(lyricText, baseX, baseline, fillPaint)
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

    companion object {
        private const val SHORT_REVEAL_CHARACTER_COUNT = 10
        private const val SHORT_REVEAL_DURATION_PER_CHARACTER_MS = 36L
        private const val LONG_REVEAL_DURATION_PER_CHARACTER_MS = 18L
        private const val MIN_REVEAL_DURATION_MS = 360L
        private const val MAX_REVEAL_DURATION_MS = 900L
        private const val SHADOW_OFFSET_RATIO = 0.5f

        fun resolveRevealDurationMs(text: String): Long {
            val characterCount = text.codePointCount(0, text.length)
            val shortCharacterCount = characterCount.coerceAtMost(SHORT_REVEAL_CHARACTER_COUNT)
            val longCharacterCount = characterCount - shortCharacterCount
            return (
                shortCharacterCount.toLong() * SHORT_REVEAL_DURATION_PER_CHARACTER_MS +
                    longCharacterCount.toLong() * LONG_REVEAL_DURATION_PER_CHARACTER_MS
                ).coerceIn(MIN_REVEAL_DURATION_MS, MAX_REVEAL_DURATION_MS)
        }
    }

    private fun restartScrollAfterLayout() {
        if (revealProgress < 1f || lyricText.isBlank()) {
            return
        }
        stopScroll(resetOffset = true)
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
        if (width <= 0 || lyricText.isBlank()) {
            return
        }
        val contentWidth = (width - paddingLeft - paddingRight - outlinePaint.strokeWidth * 2f)
            .coerceAtLeast(1f)
        val overflow = (fillPaint.measureText(lyricText) - contentWidth).coerceAtLeast(0f)
        if (overflow <= dp(2)) {
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
}
