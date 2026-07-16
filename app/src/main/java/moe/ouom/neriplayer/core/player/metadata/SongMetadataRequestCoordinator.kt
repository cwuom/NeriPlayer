package moe.ouom.neriplayer.core.player.metadata

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class SongMetadataRequestToken(
    val songKey: String,
    val generation: Long,
    val isAuto: Boolean
)

internal class SongMetadataRequestCoordinator {
    private val nextGeneration = AtomicLong(0L)
    private val latestRequests = ConcurrentHashMap<String, SongMetadataRequestToken>()

    fun begin(songKey: String, isAuto: Boolean): SongMetadataRequestToken? {
        val nextToken = SongMetadataRequestToken(
            songKey = songKey,
            generation = nextGeneration.incrementAndGet(),
            isAuto = isAuto
        )
        var acceptedToken: SongMetadataRequestToken? = null
        latestRequests.compute(songKey) { _, currentToken ->
            if (isAuto && currentToken?.isAuto == false) {
                currentToken
            } else {
                acceptedToken = nextToken
                nextToken
            }
        }
        return acceptedToken
    }

    fun isLatest(token: SongMetadataRequestToken): Boolean {
        return latestRequests[token.songKey] == token
    }

    fun complete(token: SongMetadataRequestToken) {
        latestRequests.remove(token.songKey, token)
    }
}
