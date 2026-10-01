package moe.ouom.neriplayer.core.download.host

import android.content.Context
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import okhttp3.OkHttpClient

interface DownloadEnvironment {
    val applicationContext: Context
    val sharedOkHttpClient: OkHttpClient
    val instrumentationTestActive: Boolean

    fun recordDownloadBytes(networkType: TrafficNetworkType, bytes: Long)
    suspend fun mobileDataHighRiskPromptEnabled(): Boolean
    suspend fun awaitInteractiveContent()
    fun scheduleQuarantineRecovery(context: Context)
}
