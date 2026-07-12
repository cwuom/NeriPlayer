package moe.ouom.neriplayer.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkType as currentTrafficNetworkTypeImpl
import moe.ouom.neriplayer.data.traffic.hasLikelyInternetAccess as hasLikelyInternetAccessImpl
import moe.ouom.neriplayer.data.traffic.isOfflineModeNow as isOfflineModeNowImpl
import moe.ouom.neriplayer.data.traffic.isTrafficRiskNetworkNow as isTrafficRiskNetworkNowImpl
import moe.ouom.neriplayer.ui.network.rememberOfflineModeState as rememberOfflineModeStateImpl

@Composable
fun rememberOfflineModeState(): State<Boolean> {
    return rememberOfflineModeStateImpl()
}

fun Context.hasLikelyInternetAccess(): Boolean {
    return this.hasLikelyInternetAccessImpl()
}

fun Context.isOfflineModeNow(): Boolean {
    return this.isOfflineModeNowImpl()
}

fun Context.currentTrafficNetworkType(): TrafficNetworkType {
    return this.currentTrafficNetworkTypeImpl()
}

fun Context.isTrafficRiskNetworkNow(): Boolean {
    return this.isTrafficRiskNetworkNowImpl()
}
