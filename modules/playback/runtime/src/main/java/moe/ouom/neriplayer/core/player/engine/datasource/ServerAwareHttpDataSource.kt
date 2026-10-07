@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import java.io.EOFException
import java.io.IOException

/** Keep server read failures typed; Media3 retains ownership of retry offsets and cancellation. */
internal class ServerAwareHttpDataSource(private val delegate: HttpDataSource) : HttpDataSource by delegate {
    private var serverRequest = false
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        serverRequest = dataSpec.uri.host == ServerSongRef.RESOURCE_HOST
        remaining = C.LENGTH_UNSET.toLong()
        return try {
            delegate.open(dataSpec).also { remaining = it }
        } catch (error: IOException) {
            runCatching { delegate.close() }
            throw classify(error)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        val count = delegate.read(buffer, offset, length)
        if (serverRequest && count == C.RESULT_END_OF_INPUT && remaining > 0L) {
            throw EOFException("Server media ended before the declared length")
        }
        if (count > 0 && remaining != C.LENGTH_UNSET.toLong()) {
            remaining = (remaining - count).coerceAtLeast(0L)
        }
        count
    } catch (error: IOException) {
        throw classify(error)
    }

    override fun close() {
        try { delegate.close() }
        finally { serverRequest = false; remaining = C.LENGTH_UNSET.toLong() }
    }

    private fun classify(error: IOException): IOException = if (!serverRequest) error else {
        SubsonicException.find(error) ?: SubsonicException.transport(error)
    }
}
