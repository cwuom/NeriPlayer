package moe.ouom.neriplayer.ui.component.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner

/**
 * 主页面场景会显式提供自己的返回分发器，对话框里的子组合会继承它，
 * Material 底部面板的预测性返回因此注册到 Activity 一侧，收不到对话框窗口的手势，
 * 只能在松手后直接关闭。这里给面板一个独立的分发器，再把对话框自身分发器上的
 * 返回事件原样转发过去；面板内没有启用的返回处理时不拦截，仍按对话框默认行为关闭
 */
internal class SheetNavigationEventBridge : NavigationEventDispatcherOwner {
    override val navigationEventDispatcher: NavigationEventDispatcher = NavigationEventDispatcher()

    private val input = ForwardedInput()
    private val forwarder = Forwarder(input)

    init {
        input.onEnabledChanged = { enabled -> forwarder.isBackEnabled = enabled }
        navigationEventDispatcher.addInput(input)
    }

    /** 把 [source] 收到的返回手势转给面板；返回值用于解除转发 */
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

        override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) = onEnabledChanged(hasEnabledHandlers)

        fun started(event: NavigationEvent) = dispatchOnBackStarted(event)
        fun progressed(event: NavigationEvent) = dispatchOnBackProgressed(event)
        fun completed() = dispatchOnBackCompleted()
        fun cancelled() = dispatchOnBackCancelled()
    }

    private class Forwarder(private val input: ForwardedInput) :
        NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, false) {
        override fun onBackStarted(event: NavigationEvent) = input.started(event)
        override fun onBackProgressed(event: NavigationEvent) = input.progressed(event)
        override fun onBackCompleted() = input.completed()
        override fun onBackCancelled() = input.cancelled()
    }
}

/** 必须在对话框窗口内的组合中调用，才能从视图树取到对话框自己的分发器 */
@Composable
internal fun SheetNavigationEventForwarding(bridge: SheetNavigationEventBridge) {
    val dialogOwner = LocalView.current.findViewTreeNavigationEventDispatcherOwner() ?: return
    DisposableEffect(dialogOwner, bridge) {
        val detach = bridge.attachTo(dialogOwner.navigationEventDispatcher)
        onDispose(detach)
    }
}
