package moe.ouom.neriplayer.ui.feedback

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import moe.ouom.neriplayer.R
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val StyledToastBackgroundColor = 0xFF323232.toInt()
private const val StyledToastTextColor = Color.WHITE
private const val SnackbarLayerZIndex = 50f
private const val FeedbackDedupWindowMs = 1_800L
private const val StyledToastBottomOffsetDp = 76
private const val StyledToastMinHeightDp = 48
private const val StyledToastHorizontalMarginDp = 32
private const val StyledToastHorizontalPaddingDp = 16
private const val StyledToastVerticalPaddingDp = 14
private const val NeriSnackbarMaxLines = 2
private const val NeriCompactSnackbarMaxLines = 1
internal const val NeriSnackbarTestTag = "neri_feedback_snackbar"

internal data class FeedbackDedupState(
    val message: String,
    val shownAtMs: Long
)

private val snackbarDedupLock = Any()
private val snackbarDedupStates = WeakHashMap<SnackbarHostState, FeedbackDedupState>()

internal data class AppFeedbackMessage(
    val text: String,
    val duration: SnackbarDuration,
    val actionLabel: String? = null,
    val withDismissAction: Boolean = false,
    val onActionPerformed: (suspend () -> Unit)? = null
)

internal enum class FeedbackDelivery {
    Snackbar,
    StyledToast,
    SystemToast
}

internal fun resolveFeedbackDelivery(
    isForeground: Boolean,
    hasSnackbarHost: Boolean,
    preferSnackbar: Boolean,
    forceToast: Boolean
): FeedbackDelivery {
    if (!isForeground) {
        return FeedbackDelivery.SystemToast
    }
    if (!forceToast && preferSnackbar && hasSnackbarHost) {
        return FeedbackDelivery.Snackbar
    }
    return FeedbackDelivery.StyledToast
}

internal fun canUseStyledToastView(
    isForeground: Boolean,
    sdkInt: Int
): Boolean = isForeground || sdkInt < Build.VERSION_CODES.R

object AppFeedback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startedActivityCount = AtomicInteger(0)
    private val snackbarHostCount = AtomicInteger(0)
    private val events = Channel<AppFeedbackMessage>(
        capacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val toastDedupLock = Any()

    @Volatile
    private var lastToastDedupState: FeedbackDedupState? = null

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var initializedApplication: Application? = null

    @Synchronized
    fun initialize(application: Application) {
        if (initializedApplication === application) {
            return
        }
        initializedApplication = application
        applicationContext = application.applicationContext
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivityCount.incrementAndGet()
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivityCount.updateAndGet { count ->
                        (count - 1).coerceAtLeast(0)
                    }
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    fun show(
        context: Context? = null,
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        preferSnackbar: Boolean = true
    ) {
        showInternal(
            context = context,
            message = message,
            duration = duration,
            preferSnackbar = preferSnackbar,
            forceToast = false,
            actionLabel = null,
            withDismissAction = false,
            onActionPerformed = null
        )
    }

    fun showWithAction(
        context: Context? = null,
        message: String,
        actionLabel: String,
        duration: SnackbarDuration = SnackbarDuration.Long,
        withDismissAction: Boolean = true,
        onActionPerformed: suspend () -> Unit
    ) {
        showInternal(
            context = context,
            message = message,
            duration = duration,
            preferSnackbar = true,
            forceToast = false,
            actionLabel = actionLabel,
            withDismissAction = withDismissAction,
            onActionPerformed = onActionPerformed
        )
    }

    fun showToast(
        context: Context? = null,
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short
    ) {
        showInternal(
            context = context,
            message = message,
            duration = duration,
            preferSnackbar = false,
            forceToast = true,
            actionLabel = null,
            withDismissAction = false,
            onActionPerformed = null
        )
    }

    internal fun registerSnackbarHost() {
        snackbarHostCount.incrementAndGet()
    }

    internal fun unregisterSnackbarHost() {
        snackbarHostCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    internal suspend fun collectMessages(
        onMessage: suspend (AppFeedbackMessage) -> Unit
    ) {
        for (message in events) {
            onMessage(message)
        }
    }

    private fun showInternal(
        context: Context?,
        message: String,
        duration: SnackbarDuration,
        preferSnackbar: Boolean,
        forceToast: Boolean,
        actionLabel: String?,
        withDismissAction: Boolean,
        onActionPerformed: (suspend () -> Unit)?
    ) {
        val text = message.trim().takeIf { it.isNotEmpty() } ?: return
        val toastContext = context ?: applicationContext ?: return
        val isForeground = startedActivityCount.get() > 0
        if (shouldSkipGlobalDuplicate(text)) {
            return
        }
        when (
            resolveFeedbackDelivery(
                isForeground = isForeground,
                hasSnackbarHost = snackbarHostCount.get() > 0,
                preferSnackbar = preferSnackbar,
                forceToast = forceToast
            )
        ) {
            FeedbackDelivery.Snackbar -> {
                val event = AppFeedbackMessage(
                    text = text,
                    duration = duration,
                    actionLabel = actionLabel,
                    withDismissAction = withDismissAction,
                    onActionPerformed = onActionPerformed
                )
                if (!events.trySend(event).isSuccess) {
                    showToastOnMain(toastContext, text, duration, isForeground)
                }
            }
            FeedbackDelivery.StyledToast,
            FeedbackDelivery.SystemToast -> showToastOnMain(
                context = toastContext,
                message = text,
                duration = duration,
                isForeground = isForeground
            )
        }
    }

    private fun shouldSkipGlobalDuplicate(message: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(toastDedupLock) {
            val last = lastToastDedupState
            if (isDuplicateFeedbackMessage(last, message, now)) {
                return true
            }
            lastToastDedupState = FeedbackDedupState(message, now)
        }
        return false
    }

    private fun showToastOnMain(
        context: Context,
        message: String,
        duration: SnackbarDuration,
        isForeground: Boolean
    ) {
        val action = Runnable {
            val appContext = context.applicationContext ?: context
            if (canUseStyledToastView(isForeground, Build.VERSION.SDK_INT)) {
                showStyledToast(appContext, message, duration)
            } else {
                Toast.makeText(appContext, message, duration.toToastLength()).show()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    @Suppress("DEPRECATION")
    private fun showStyledToast(
        context: Context,
        message: String,
        duration: SnackbarDuration
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(StyledToastBackgroundColor)
        }
        val horizontalPadding = dp(StyledToastHorizontalPaddingDp)
        val maxTextWidth = (
            context.resources.displayMetrics.widthPixels -
                dp(StyledToastHorizontalMarginDp * 2) -
                horizontalPadding * 2
            ).coerceAtLeast(dp(120))
        val container = FrameLayout(context).apply {
            minimumHeight = dp(StyledToastMinHeightDp)
            setPadding(
                horizontalPadding,
                dp(StyledToastVerticalPaddingDp),
                horizontalPadding,
                dp(StyledToastVerticalPaddingDp)
            )
            this.background = background
            elevation = dp(6).toFloat()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val textView = TextView(context).apply {
            text = message
            setTextColor(StyledToastTextColor)
            textSize = 14f
            typeface = Typeface.DEFAULT
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            maxWidth = maxTextWidth
        }
        container.addView(
            textView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL
            )
        )
        Toast(context).apply {
            this.duration = duration.toToastLength()
            view = container
            setGravity(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                0,
                dp(StyledToastBottomOffsetDp)
            )
        }.show()
    }

    private fun SnackbarDuration.toToastLength(): Int {
        return when (this) {
            SnackbarDuration.Long,
            SnackbarDuration.Indefinite -> Toast.LENGTH_LONG
            SnackbarDuration.Short -> Toast.LENGTH_SHORT
        }
    }
}

suspend fun SnackbarHostState.showNeriSnackbar(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = if (actionLabel == null) {
        SnackbarDuration.Short
    } else {
        SnackbarDuration.Indefinite
    }
): SnackbarResult {
    val text = message.trim()
    if (text.isEmpty() || shouldSkipSnackbarDuplicate(this, text)) {
        return SnackbarResult.Dismissed
    }
    return showSnackbar(
        message = text,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = duration
    )
}

private fun shouldSkipSnackbarDuplicate(
    hostState: SnackbarHostState,
    message: String
): Boolean {
    if (hostState.currentSnackbarData?.visuals?.message == message) {
        return true
    }
    val now = SystemClock.elapsedRealtime()
    synchronized(snackbarDedupLock) {
        val last = snackbarDedupStates[hostState]
        if (isDuplicateFeedbackMessage(last, message, now)) {
            return true
        }
        snackbarDedupStates[hostState] = FeedbackDedupState(message, now)
    }
    return false
}

internal fun isDuplicateFeedbackMessage(
    last: FeedbackDedupState?,
    message: String,
    nowMs: Long
): Boolean {
    return last != null &&
        last.message == message &&
        nowMs - last.shownAtMs < FeedbackDedupWindowMs
}

internal fun resolveNeriSnackbarMessageMaxLines(
    actionLabel: String?,
    withDismissAction: Boolean
): Int {
    return if (actionLabel != null || withDismissAction) {
        NeriCompactSnackbarMaxLines
    } else {
        NeriSnackbarMaxLines
    }
}

@Composable
fun AppFeedbackHostEffect(snackbarHostState: SnackbarHostState) {
    DisposableEffect(snackbarHostState) {
        AppFeedback.registerSnackbarHost()
        onDispose { AppFeedback.unregisterSnackbarHost() }
    }
    LaunchedEffect(snackbarHostState) {
        AppFeedback.collectMessages { message ->
            val result = snackbarHostState.showNeriSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                withDismissAction = message.withDismissAction,
                duration = message.duration
            )
            if (result == SnackbarResult.ActionPerformed) {
                message.onActionPerformed?.invoke()
            }
        }
    }
}

@Composable
fun NeriSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    applyNavigationBarsPadding: Boolean = true,
    applyImePadding: Boolean = true
) {
    var hostModifier = modifier
        .zIndex(SnackbarLayerZIndex)
        .padding(bottom = bottomPadding)
    if (applyNavigationBarsPadding) {
        hostModifier = hostModifier.windowInsetsPadding(WindowInsets.navigationBars)
    }
    if (applyImePadding) {
        hostModifier = hostModifier.imePadding()
    }
    SnackbarHost(
        hostState = hostState,
        modifier = hostModifier,
        snackbar = ::NeriSnackbar
    )
}

@Composable
fun BoxScope.NeriOverlaySnackbarHost(
    hostState: SnackbarHostState,
    bottomPadding: Dp = 0.dp,
    applyNavigationBarsPadding: Boolean = true,
    applyImePadding: Boolean = true
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .zIndex(SnackbarLayerZIndex),
        contentAlignment = Alignment.BottomCenter
    ) {
        NeriSnackbarHost(
            hostState = hostState,
            modifier = Modifier.fillMaxWidth(),
            bottomPadding = bottomPadding,
            applyNavigationBarsPadding = applyNavigationBarsPadding,
            applyImePadding = applyImePadding
        )
    }
}

@Composable
private fun NeriSnackbar(snackbarData: SnackbarData) {
    val actionLabel = snackbarData.visuals.actionLabel
    val messageMaxLines = resolveNeriSnackbarMessageMaxLines(
        actionLabel = actionLabel,
        withDismissAction = snackbarData.visuals.withDismissAction
    )
    Snackbar(
        modifier = Modifier
            .padding(12.dp)
            .testTag(NeriSnackbarTestTag),
        action = if (actionLabel != null) {
            {
                TextButton(
                    onClick = snackbarData::performAction,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = SnackbarDefaults.actionColor
                    )
                ) {
                    Text(
                        text = actionLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            null
        },
        dismissAction = if (snackbarData.visuals.withDismissAction) {
            {
                IconButton(onClick = snackbarData::dismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_close)
                    )
                }
            }
        } else {
            null
        },
        actionOnNewLine = false
    ) {
        Text(
            text = snackbarData.visuals.message,
            maxLines = messageMaxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}
