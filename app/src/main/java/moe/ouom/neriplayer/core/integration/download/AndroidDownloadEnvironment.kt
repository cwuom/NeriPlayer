package moe.ouom.neriplayer.core.integration.download

import android.content.Context
import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.host.DownloadEnvironment
import moe.ouom.neriplayer.core.startup.AppStartupWorkGate
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.core.startup.app.InstrumentationTestRuntime
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.traffic.TrafficUsageSource

internal object AndroidDownloadEnvironment : DownloadEnvironment {
    override val applicationContext get() = AppContainer.applicationContext
    override val sharedOkHttpClient get() = AppContainer.sharedOkHttpClient
    override val instrumentationTestActive get() = InstrumentationTestRuntime.isActive

    override fun recordDownloadBytes(networkType: TrafficNetworkType, bytes: Long) {
        AppContainer.trafficStatsRepo.recordNetworkBytes(
            networkType = networkType,
            bytes = bytes,
            source = TrafficUsageSource.DOWNLOAD
        )
    }

    override suspend fun mobileDataHighRiskPromptEnabled(): Boolean =
        AppContainer.settingsRepo.mobileDataHighRiskPromptEnabledFlow.first()

    override suspend fun awaitInteractiveContent() {
        AppStartupWorkGate.awaitInteractiveContentOrTimeout()
    }

    override fun scheduleQuarantineRecovery(context: Context) {
        LegacyJsonCleanupScheduler.scheduleQuarantineRecovery(context)
    }
}
