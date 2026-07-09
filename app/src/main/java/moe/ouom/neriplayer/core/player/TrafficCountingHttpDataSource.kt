package moe.ouom.neriplayer.core.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.TrafficStatsRepository
import moe.ouom.neriplayer.data.traffic.TrafficUsageSource

@UnstableApi
internal class TrafficCountingHttpDataSource(
    private val delegate: HttpDataSource,
    private val trafficStatsRepository: TrafficStatsRepository,
    private val usageSource: TrafficUsageSource = TrafficUsageSource.PLAYBACK
) : HttpDataSource {
    private var active = false
    private var accumulator = newAccumulator()

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        active = true
        accumulator = newAccumulator()
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = delegate.read(buffer, offset, length)
        if (active && read > 0) {
            accumulator.add(read.toLong())
        }
        return read
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        try {
            accumulator.flush()
        } finally {
            active = false
            delegate.close()
        }
    }

    override fun setRequestProperty(name: String, value: String) {
        delegate.setRequestProperty(name, value)
    }

    override fun clearRequestProperty(name: String) {
        delegate.clearRequestProperty(name)
    }

    override fun clearAllRequestProperties() {
        delegate.clearAllRequestProperties()
    }

    override fun getResponseCode(): Int = delegate.responseCode

    private fun newAccumulator(): TrafficByteAccumulator {
        val networkType: TrafficNetworkType = trafficStatsRepository.currentNetworkType()
        return TrafficByteAccumulator {
            trafficStatsRepository.recordNetworkBytes(
                networkType = networkType,
                bytes = it,
                source = usageSource
            )
        }
    }
}
