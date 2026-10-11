package moe.ouom.neriplayer.data.ltw.session.connection

import moe.ouom.neriplayer.data.ltw.platform.ListenTogetherNetworkListener

/**
 * Reports a default network only when it appears or first validates, because capability callbacks
 * repeat for the same network on every signal change and would otherwise restart reconnects.
 */
internal class ListenTogetherDefaultNetworkTracker(
    private val onAvailable: () -> Unit,
    private val onLost: () -> Unit
) : ListenTogetherNetworkListener {
    @Volatile
    private var network: Any? = null
    @Volatile
    private var validated = false

    override fun onDefaultNetworkAvailable(network: Any) {
        if (this.network == network) return
        track(network, validated = false)
    }

    override fun onDefaultNetworkValidated(network: Any) {
        if (this.network == network && validated) return
        track(network, validated = true)
    }

    override fun onDefaultNetworkLost(network: Any) {
        if (this.network != network) return
        this.network = null
        validated = false
        onLost()
    }

    private fun track(network: Any, validated: Boolean) {
        this.network = network
        this.validated = validated
        onAvailable()
    }
}
