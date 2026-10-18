package moe.ouom.neriplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/** False when the user turned off "predictive back preview"; hosts then stop seeking on drag. */
val LocalPredictiveBackPreviewEnabled = staticCompositionLocalOf { true }

/**
 * Sits between the activity dispatcher and a subtree. With preview on, gestures pass through
 * unchanged; with preview off, drag progress is swallowed and the gesture is replayed as an
 * instant start + complete on release, so the subtree still runs its normal back animation.
 * The bridge is always installed, so toggling never remounts the wrapped NavHost.
 */
@Composable
internal fun PredictiveBackPreviewGate(
    previewEnabled: Boolean,
    content: @Composable () -> Unit
) {
    val parentOwner = LocalNavigationEventDispatcherOwner.current
    val bridge = remember { PredictiveBackPreviewBridge() }
    SideEffect { bridge.previewEnabled = previewEnabled }
    DisposableEffect(parentOwner, bridge) {
        val detach = parentOwner?.let { bridge.attachTo(it.navigationEventDispatcher) }
        onDispose { detach?.invoke() }
    }
    DisposableEffect(bridge) { onDispose(bridge::dispose) }
    if (parentOwner == null) {
        CompositionLocalProvider(LocalPredictiveBackPreviewEnabled provides previewEnabled, content = content)
        return
    }
    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides bridge,
        LocalPredictiveBackPreviewEnabled provides previewEnabled,
        content = content
    )
}

private class PredictiveBackPreviewBridge : NavigationEventDispatcherOwner {
    override val navigationEventDispatcher: NavigationEventDispatcher = NavigationEventDispatcher()

    var previewEnabled: Boolean = true

    private val input = ForwardedInput()
    private val forwarder = Forwarder()

    init {
        input.onEnabledChanged = { enabled -> forwarder.isBackEnabled = enabled }
        navigationEventDispatcher.addInput(input)
    }

    fun attachTo(source: NavigationEventDispatcher): () -> Unit {
        source.addHandler(forwarder)
        return forwarder::remove
    }

    fun dispose() {
        forwarder.remove()
        navigationEventDispatcher.dispose()
    }

    private class ForwardedInput : NavigationEventInput() {
        var onEnabledChanged: (Boolean) -> Unit = {}

        override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) =
            onEnabledChanged(hasEnabledHandlers)

        fun started(event: NavigationEvent) = dispatchOnBackStarted(event)
        fun progressed(event: NavigationEvent) = dispatchOnBackProgressed(event)
        fun completed() = dispatchOnBackCompleted()
        fun cancelled() = dispatchOnBackCancelled()
    }

    private inner class Forwarder :
        NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, false) {
        private var deferredStart: NavigationEvent? = null
        private var forwarding = false

        override fun onBackStarted(event: NavigationEvent) {
            forwarding = previewEnabled
            if (forwarding) input.started(event) else deferredStart = event
        }

        override fun onBackProgressed(event: NavigationEvent) {
            if (forwarding) input.progressed(event)
        }

        override fun onBackCompleted() {
            if (!forwarding) {
                input.started(deferredStart ?: NavigationEvent())
            }
            deferredStart = null
            forwarding = false
            input.completed()
        }

        override fun onBackCancelled() {
            if (forwarding) input.cancelled()
            deferredStart = null
            forwarding = false
        }
    }
}
