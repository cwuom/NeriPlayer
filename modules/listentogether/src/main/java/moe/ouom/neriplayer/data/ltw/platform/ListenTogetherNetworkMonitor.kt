package moe.ouom.neriplayer.data.ltw.platform

interface ListenTogetherNetworkListener {
    fun onDefaultNetworkAvailable(network: Any)
    fun onDefaultNetworkValidated(network: Any)
    fun onDefaultNetworkLost(network: Any)
}

interface ListenTogetherNetworkMonitor {
    fun start(listener: ListenTogetherNetworkListener)
    fun stop()

    /** 离线时注册默认网络回调不会收到任何事件，开始监听前用它确定初始状态 */
    fun hasDefaultNetwork(): Boolean = true
}

object NoListenTogetherNetworkMonitor : ListenTogetherNetworkMonitor {
    override fun start(listener: ListenTogetherNetworkListener) = Unit
    override fun stop() = Unit
}
