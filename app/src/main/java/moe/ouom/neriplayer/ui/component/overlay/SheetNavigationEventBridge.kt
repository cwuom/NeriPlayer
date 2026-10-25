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

    var previewEnabled: Boolean = true

    private val input = ForwardedInput()
    private val forwarder = Forwarder()

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

    private inner class Forwarder :
        NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, false) {
        private var forwarding = false

        override fun onBackStarted(event: NavigationEvent) {
            forwarding = previewEnabled
            if (forwarding) input.started(event)
        }

        override fun onBackProgressed(event: NavigationEvent) {
            if (forwarding) input.progressed(event)
        }

        override fun onBackCompleted() {
            // 关闭预览时只在松手后启动普通返回，不向弹层泄露中间进度
            if (!forwarding) input.started(NavigationEvent())
            forwarding = false
            input.completed()
        }

        override fun onBackCancelled() {
            if (forwarding) input.cancelled()
            forwarding = false
        }
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
