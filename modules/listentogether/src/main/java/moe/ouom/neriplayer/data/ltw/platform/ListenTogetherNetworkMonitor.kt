package moe.ouom.neriplayer.data.ltw.platform

interface ListenTogetherNetworkListener {
    fun onDefaultNetworkAvailable(network: Any)
    fun onDefaultNetworkValidated(network: Any)
    fun onDefaultNetworkLost(network: Any)
}

interface ListenTogetherNetworkMonitor {
    fun start(listener: ListenTogetherNetworkListener)
    fun stop()
}

object NoListenTogetherNetworkMonitor : ListenTogetherNetworkMonitor {
    override fun start(listener: ListenTogetherNetworkListener) = Unit
    override fun stop() = Unit
}
