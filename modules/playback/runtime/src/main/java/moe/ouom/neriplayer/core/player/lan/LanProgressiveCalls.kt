@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call

internal class LanProgressiveCalls(private val client: Call.Factory) {
    private val active = ConcurrentHashMap.newKeySet<Call>()

    fun sourceFactory(bearer: String): DataSource.Factory = DataSource.Factory {
        var sourceCall: Call? = null
        val tracked = Call.Factory { request ->
            client.newCall(request).also { call ->
                sourceCall = call
                active.add(call)
            }
        }
        val source = OkHttpDataSource.Factory(tracked).setDefaultRequestProperties(
            mapOf("Authorization" to "Bearer $bearer", "Accept-Encoding" to "identity")
        ).createDataSource()
        object : DataSource by source {
            override fun close() {
                try { source.close() }
                finally {
                    sourceCall?.let(active::remove)
                    sourceCall = null
                }
            }
        }
    }

    fun replaceLoading(update: () -> Unit) {
        val previous = active.toList()
        try {
            // 先入队播放器命令，旧请求的取消回调到达时它已开始切换加载位置
            update()
        } finally {
            previous.forEach(Call::cancel)
        }
    }
}
