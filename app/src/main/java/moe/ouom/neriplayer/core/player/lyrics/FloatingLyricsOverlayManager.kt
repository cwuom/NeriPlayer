package moe.ouom.neriplayer.core.player.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_ALIGNMENT_LEFT
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_ALIGNMENT_RIGHT
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_TRANSLATION_STYLE_SCALE
import moe.ouom.neriplayer.data.settings.normalizeFloatingLyricsColorHex
import moe.ouom.neriplayer.data.settings.resolveFloatingLyricsPositionX
import moe.ouom.neriplayer.data.settings.resolveFloatingLyricsPositionY
import kotlin.math.roundToInt

@SuppressLint("StaticFieldLeak")
object FloatingLyricsOverlayManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var configurationCallbacks: ComponentCallbacks? = null
    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var lyricTextView: AnimatedOutlinedLyricTextView? = null
    private var translationTextView: AnimatedOutlinedLyricTextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var preferences = FloatingLyricsPreferences()
    private var appliedStylePreferences: FloatingLyricsPreferences? = null
    private var lyricLine: String? = null
    private var translationLine: String? = null
    private var pendingLyricLine: String? = null
    private var pendingTranslationLine: String? = null
    private var playbackActive = false
    private var longPressDragController: FloatingLyricsLongPressDragController? = null
    private var longPressDragInProgress = false
    private var positionChangeListener: ((Float, Float, Boolean) -> Unit)? = null
    private var contentUpdateScheduled = false
    private val contentUpdateRunnable = Runnable {
        contentUpdateScheduled = false
        lyricLine = pendingLyricLine
        translationLine = pendingTranslationLine
        syncOverlay()
    }
    private var layoutUpdateScheduled = false
    private val layoutUpdateRunnable = Runnable {
        layoutUpdateScheduled = false
        if (!longPressDragInProgress) {
            layoutParams?.let(::applyStoredPosition)
        }
        updateLayout()
    }
    private var startedActivityCount = 0

    fun initialize(app: Application) {
        if (application === app) {
            return
        }
        release()
        application = app
        windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount += 1
                syncOverlay()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                syncOverlay()
            }

            @Deprecated("kept for ActivityLifecycleCallbacks compatibility")
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = syncOverlay()
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        callbacks = lifecycleCallbacks
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        val appConfigurationCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                runOnMain {
                    rootView?.requestLayout()
                    scheduleLayoutUpdate()
                }
            }

            @Deprecated("kept for ComponentCallbacks compatibility")
            override fun onLowMemory() = Unit
        }
        configurationCallbacks = appConfigurationCallbacks
        app.registerComponentCallbacks(appConfigurationCallbacks)
        syncOverlay()
    }

    fun setPositionChangeListener(listener: ((Float, Float, Boolean) -> Unit)?) {
        runOnMain {
            positionChangeListener = listener
        }
    }

    fun updatePreferences(nextPreferences: FloatingLyricsPreferences) {
        runOnMain {
            preferences = nextPreferences.normalized()
            if (!shouldShowOverlay()) {
                removeOverlay()
                return@runOnMain
            }
            val needsInitialText = rootView == null
            ensureOverlay()
            updateOverlayStyle()
            if (needsInitialText) {
                updateOverlayText()
            }
            scheduleLayoutUpdate()
        }
    }

    fun updateContent(line: String?, translation: String?) {
        runOnMain {
            pendingLyricLine = line?.trim()?.takeIf { it.isNotEmpty() }
            pendingTranslationLine = translation?.trim()?.takeIf { it.isNotEmpty() }
            scheduleContentUpdate()
        }
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        runOnMain {
            if (playbackActive == isPlaying) {
                return@runOnMain
            }
            playbackActive = isPlaying
            lyricTextView?.setPlaybackActive(isPlaying)
            translationTextView?.setPlaybackActive(isPlaying)
        }
    }

    fun release() {
        runOnMain {
            removeOverlay()
            callbacks?.let { callback ->
                application?.unregisterActivityLifecycleCallbacks(callback)
            }
            configurationCallbacks?.let { callback ->
                application?.unregisterComponentCallbacks(callback)
            }
            callbacks = null
            configurationCallbacks = null
            application = null
            windowManager = null
            mainHandler.removeCallbacks(contentUpdateRunnable)
            mainHandler.removeCallbacks(layoutUpdateRunnable)
            contentUpdateScheduled = false
            layoutUpdateScheduled = false
            startedActivityCount = 0
            playbackActive = false
            positionChangeListener = null
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun openOverlayPermissionSettings(context: Context) {
        val packageUri = "package:${context.packageName}".toUri()
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallbackIntent)
        }
    }

    private fun syncOverlay() {
        runOnMain {
            if (!shouldShowOverlay()) {
                removeOverlay()
                return@runOnMain
            }
            ensureOverlay()
            updateOverlayStyle()
            updateOverlayText()
            scheduleLayoutUpdate()
        }
    }

    private fun shouldShowOverlay(): Boolean {
        val app = application ?: return false
        if (!preferences.enabled || lyricLine.isNullOrBlank()) {
            return false
        }
        if (!hasOverlayPermission(app)) {
            return false
        }
        return !(preferences.hideInApp && startedActivityCount > 0)
    }

    private fun ensureOverlay() {
        if (rootView != null) {
            return
        }
        val app = application ?: return
        val manager = windowManager ?: return
        val title = AnimatedOutlinedLyricTextView(app)
        val translation = AnimatedOutlinedLyricTextView(app)
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, 0)
            addView(title, matchWidthLayoutParams())
            addView(translation, matchWidthLayoutParams())
        }
        root.alpha = 1f
        title.alpha = 1f
        translation.alpha = 1f
        lyricTextView = title
        translationTextView = translation
        title.setPlaybackActive(playbackActive)
        translation.setPlaybackActive(playbackActive)
        rootView = root
        layoutParams = buildLayoutParams().also { params ->
            applyStoredPosition(params)
            manager.addView(root, params)
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            dp(preferences.maxWidthDp).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            resolveWindowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            alpha = 1f
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun updateOverlayText() {
        val nextLyric = lyricLine.orEmpty()
        val showTranslation = preferences.showTranslation && !translationLine.isNullOrBlank()
        val nextTranslation = if (showTranslation) translationLine.orEmpty() else ""
        val revealAnimationEnabled = preferences.revealAnimationEnabled
        val revealDurationMs = if (revealAnimationEnabled) {
            listOf(nextLyric, nextTranslation)
                .maxOf { AnimatedOutlinedLyricTextView.resolveRevealDurationMs(it) }
        } else {
            null
        }
        lyricTextView?.setLyricText(
            nextText = nextLyric,
            revealDurationMs = revealDurationMs,
            revealAnimationEnabled = revealAnimationEnabled
        )
        translationTextView?.apply {
            visibility = if (showTranslation) View.VISIBLE else View.GONE
            setLyricText(
                nextText = nextTranslation,
                revealDurationMs = revealDurationMs,
                revealAnimationEnabled = revealAnimationEnabled
            )
        }
    }

    private fun scheduleContentUpdate() {
        if (contentUpdateScheduled) {
            return
        }
        contentUpdateScheduled = true
        mainHandler.postDelayed(contentUpdateRunnable, CONTENT_UPDATE_COALESCE_MS)
    }

    private fun updateOverlayStyle() {
        val root = rootView ?: return
        if (appliedStylePreferences == preferences) {
            return
        }
        val maxWidthChanged = appliedStylePreferences?.maxWidthDp != preferences.maxWidthDp
        val textColor = "#${normalizeFloatingLyricsColorHex(preferences.textColorHex)}".toColorInt()
        val outlineColor = (
            "#${normalizeFloatingLyricsColorHex(preferences.outlineColorHex)}"
        ).toColorInt()
        val alignmentFactor = when (preferences.alignment) {
            FLOATING_LYRICS_ALIGNMENT_LEFT -> 0f
            FLOATING_LYRICS_ALIGNMENT_RIGHT -> 1f
            else -> 0.5f
        }
        root.background = null
        root.alpha = 1f
        layoutParams?.alpha = 1f
        layoutParams?.apply {
            width = dp(preferences.maxWidthDp).roundToInt()
            flags = resolveWindowFlags()
        }
        configureLongPressDrag(root)
        lyricTextView?.apply {
            setRevealAnimationEnabled(preferences.revealAnimationEnabled)
            setAlignmentFactor(alignmentFactor)
            setLyricStyle(
                textColor = withAlpha(textColor, preferences.lyricAlpha),
                effectColor = withAlpha(
                    outlineColor,
                    resolveFloatingLyricsEffectAlpha(preferences.lyricAlpha)
                ),
                textSizePx = sp(preferences.fontSizeSp),
                effectWidthPx = dp(preferences.outlineWidthDp),
                renderStyle = preferences.renderStyle,
                bold = true
            )
        }
        translationTextView?.apply {
            setRevealAnimationEnabled(preferences.revealAnimationEnabled)
            setAlignmentFactor(alignmentFactor)
            setLyricStyle(
                textColor = withAlpha(textColor, preferences.translationAlpha),
                effectColor = withAlpha(
                    outlineColor,
                    resolveFloatingLyricsEffectAlpha(preferences.translationAlpha)
                ),
                textSizePx = sp(
                    (preferences.fontSizeSp * FLOATING_LYRICS_TRANSLATION_STYLE_SCALE)
                        .coerceAtLeast(7f)
                ),
                effectWidthPx = dp(preferences.translationOutlineWidthDp),
                renderStyle = preferences.renderStyle,
                bold = false
            )
        }
        updateOverlayMinimumHeight()
        scheduleLayoutUpdate()
        if (maxWidthChanged) {
            lyricTextView?.refreshScrollAfterLayout()
            translationTextView?.refreshScrollAfterLayout()
        }
        appliedStylePreferences = preferences
    }

    private fun updateOverlayMinimumHeight() {
        val lyricHeight = lyricTextView?.preferredMeasuredHeightPx() ?: 0
        val translationHeight = translationTextView?.preferredMeasuredHeightPx() ?: 0
        rootView?.minimumHeight = lyricHeight + translationHeight
    }

    private fun scheduleLayoutUpdate() {
        val view = rootView ?: return
        if (layoutUpdateScheduled) {
            return
        }
        layoutUpdateScheduled = true
        view.postOnAnimation(layoutUpdateRunnable)
    }

    private fun applyStoredPosition(params: WindowManager.LayoutParams) {
        val view = rootView ?: return
        val screen = resolveScreenSize()
        val viewWidth = resolveViewWidth(view)
        val viewHeight = resolveViewHeight(view)
        val verticalRange = resolveVerticalDragRange(screen, viewHeight)
        val isLandscape = isFloatingLyricsLandscape(screen.x, screen.y)
        params.x = (
            (screen.x - viewWidth).coerceAtLeast(0) *
                resolveFloatingLyricsPositionX(preferences, isLandscape)
            ).roundToInt()
        params.y = (
            verticalRange.first +
                (verticalRange.size * resolveFloatingLyricsPositionY(preferences, isLandscape))
            ).roundToInt()
        clampParamsToScreen(params)
    }

    private fun clampParamsToScreen(params: WindowManager.LayoutParams) {
        val view = rootView ?: return
        val screen = resolveScreenSize()
        val viewWidth = resolveViewWidth(view)
        val viewHeight = resolveViewHeight(view)
        val verticalRange = resolveVerticalDragRange(screen, viewHeight)
        params.x = params.x.coerceIn(0, (screen.x - viewWidth).coerceAtLeast(0))
        params.y = params.y.coerceIn(verticalRange.first, verticalRange.last)
    }

    private fun updateLayout() {
        val manager = windowManager ?: return
        val view = rootView ?: return
        val params = layoutParams ?: return
        runCatching { manager.updateViewLayout(view, params) }
    }

    private fun resolveWindowFlags(): Int {
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return if (preferences.longPressDragEnabled) {
            baseFlags
        } else {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    private fun configureLongPressDrag(root: View) {
        if (!preferences.longPressDragEnabled) {
            longPressDragController?.cancel()
            longPressDragController = null
            longPressDragInProgress = false
            root.isLongClickable = false
            root.setOnTouchListener(null)
            return
        }
        if (longPressDragController != null) {
            return
        }
        root.isLongClickable = true
        longPressDragController = FloatingLyricsLongPressDragController(
            view = root,
            longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong(),
            touchSlopPx = ViewConfiguration.get(root.context).scaledTouchSlop.toFloat(),
            initialPositionProvider = {
                val params = layoutParams
                Point(params?.x ?: 0, params?.y ?: 0)
            },
            onDragStarted = {
                longPressDragInProgress = true
            },
            onDragPositionChanged = ::applyDraggedPosition,
            onDragEnded = ::persistDraggedPosition
        ).also(root::setOnTouchListener)
    }

    private fun applyDraggedPosition(x: Int, y: Int) {
        val params = layoutParams ?: return
        params.x = x
        params.y = y
        clampParamsToScreen(params)
        updateLayout()
    }

    private fun persistDraggedPosition(x: Int, y: Int) {
        applyDraggedPosition(x, y)
        longPressDragInProgress = false
        val params = layoutParams ?: return
        val view = rootView ?: return
        val screen = resolveScreenSize()
        val viewWidth = resolveViewWidth(view)
        val viewHeight = resolveViewHeight(view)
        val verticalRange = resolveVerticalDragRange(screen, viewHeight)
        val position = resolveFloatingLyricsDragPosition(
            xPx = params.x,
            yPx = params.y,
            horizontalRangePx = (screen.x - viewWidth).coerceAtLeast(0),
            verticalRange = verticalRange
        )
        val isLandscape = isFloatingLyricsLandscape(screen.x, screen.y)
        preferences = if (isLandscape) {
            preferences.copy(
                landscapePositionX = position.x,
                landscapePositionY = position.y
            )
        } else {
            preferences.copy(positionX = position.x, positionY = position.y)
        }
        positionChangeListener?.invoke(position.x, position.y, isLandscape)
    }

    private fun removeOverlay() {
        val manager = windowManager
        val view = rootView
        if (manager != null && view != null) {
            runCatching { manager.removeView(view) }
        }
        rootView = null
        longPressDragController?.cancel()
        longPressDragController = null
        longPressDragInProgress = false
        lyricTextView = null
        translationTextView = null
        layoutParams = null
        appliedStylePreferences = null
        mainHandler.removeCallbacks(contentUpdateRunnable)
        mainHandler.removeCallbacks(layoutUpdateRunnable)
        contentUpdateScheduled = false
        layoutUpdateScheduled = false
    }

    private fun resolveScreenSize(): Point {
        val manager = windowManager ?: return Point(1, 1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.currentWindowMetrics.bounds
            Point(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        } else {
            @Suppress("DEPRECATION")
            Point().also { manager.defaultDisplay.getRealSize(it) }
        }
    }

    private fun resolveVerticalDragRange(screen: Point, viewHeight: Int): IntRange {
        val minY = -resolveStatusBarInsetTop()
        val maxY = (screen.y - viewHeight).coerceAtLeast(minY + 1)
        return minY..maxY
    }

    private val IntRange.size: Int
        get() = (last - first).coerceAtLeast(1)

    private fun resolveViewWidth(view: View): Int {
        return view.width.takeIf { it > 0 }
            ?: layoutParams?.width?.takeIf { it > 0 }
            ?: dp(preferences.maxWidthDp).roundToInt()
    }

    private fun resolveViewHeight(view: View): Int {
        val currentHeight = view.height.coerceAtLeast(view.minimumHeight)
        return currentHeight.takeIf { it > 0 } ?: dp(48)
    }

    private fun resolveStatusBarInsetTop(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = windowManager ?: return 0
            manager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                .top
        } else {
            @Suppress("DEPRECATION")
            rootView?.rootWindowInsets?.systemWindowInsetTop ?: 0
        }
    }

    private fun matchWidthLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        return resolveFloatingLyricsColorWithAlpha(color, alpha)
    }

    private fun dp(value: Int): Int {
        val density = application?.resources?.displayMetrics?.density ?: 1f
        return (value * density).roundToInt()
    }

    private fun dp(value: Float): Float {
        val density = application?.resources?.displayMetrics?.density ?: 1f
        return value * density
    }

    private fun sp(value: Float): Float {
        val metrics = application?.resources?.displayMetrics
            ?: return value
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private const val CONTENT_UPDATE_COALESCE_MS = 16L
}

internal fun resolveFloatingLyricsAlphaByte(alpha: Float): Int {
    return when {
        !alpha.isFinite() -> 0
        alpha <= 0f -> 0
        alpha >= 1f -> 255
        else -> (alpha * 255f).roundToInt().coerceIn(0, 255)
    }
}

internal fun resolveFloatingLyricsEffectAlpha(alpha: Float): Float {
    val normalizedAlpha = alpha.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    return normalizedAlpha * normalizedAlpha
}

internal fun resolveFloatingLyricsColorWithAlpha(color: Int, alpha: Float): Int {
    val requestedAlpha = resolveFloatingLyricsAlphaByte(alpha)
    return (color and 0x00FFFFFF) or (requestedAlpha shl 24)
}

internal data class FloatingLyricsDragPosition(val x: Float, val y: Float)

internal fun isFloatingLyricsLandscape(screenWidthPx: Int, screenHeightPx: Int): Boolean {
    return screenWidthPx > screenHeightPx
}

internal fun resolveFloatingLyricsDragPosition(
    xPx: Int,
    yPx: Int,
    horizontalRangePx: Int,
    verticalRange: IntRange
): FloatingLyricsDragPosition {
    val safeHorizontalRange = horizontalRangePx.coerceAtLeast(0)
    val x = if (safeHorizontalRange == 0) {
        0f
    } else {
        xPx.coerceIn(0, safeHorizontalRange) / safeHorizontalRange.toFloat()
    }
    val yRangeSize = (verticalRange.last - verticalRange.first).coerceAtLeast(1)
    val y = (yPx.coerceIn(verticalRange.first, verticalRange.last) - verticalRange.first) /
        yRangeSize.toFloat()
    return FloatingLyricsDragPosition(x = x, y = y)
}
