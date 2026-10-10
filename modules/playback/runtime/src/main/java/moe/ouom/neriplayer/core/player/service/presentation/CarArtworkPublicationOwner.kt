package moe.ouom.neriplayer.core.player.service.presentation

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.model.SongItem

private data class CarArtworkRequest(val songKey: String, val source: String?, val bitmap: Bitmap)

internal class CarArtworkPublicationOwner(
    private val scope: CoroutineScope,
    private val port: PlaybackServicePresentationPort,
    private val onPublished: () -> Unit,
) {
    private var request: CarArtworkRequest? = null
    private var uri: String? = null
    private var job: Job? = null

    fun observe(song: SongItem?, artwork: PlaybackArtworkSnapshot): String? {
        val next = readyRequest(song, artwork)
        if (next != request) {
            job?.cancel()
            request = next
            uri = null
            if (next != null && song != null) publish(song, next)
        }
        return uri ?: song?.let(port::artworkUri)
    }

    private fun readyRequest(song: SongItem?, artwork: PlaybackArtworkSnapshot): CarArtworkRequest? {
        if (song == null || !artwork.mediaReady) return null
        val bitmap = artwork.mediaBitmap ?: return null
        return CarArtworkRequest(song.playbackVisualKey(), artwork.coverSource, bitmap)
    }

    private fun publish(song: SongItem, pending: CarArtworkRequest) {
        job = scope.launch {
            try {
                val published = port.publishArtwork(song, pending.bitmap, pending.source)
                if (request == pending && published != null) {
                    uri = published
                    onPublished()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w("NERI-APS", "Car artwork publication failed", error)
            }
        }
    }
}
