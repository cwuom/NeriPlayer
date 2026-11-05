package moe.ouom.neriplayer.core.player.service.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger

private const val MEDIA_NOTIFICATION_WINDOW_MS = 500L
private const val MEDIA_NOTIFICATION_CONFIRMATION_MS = 1_000L
private const val MEDIA_NOTIFICATION_MAX_RETRIES = 2

private data class PendingPlaybackNotification(
    val snapshot: PlaybackNotificationSnapshot,
    val inputs: PlaybackServiceNotificationRenderInputs,
)

internal class PlaybackNotificationPublicationOwner(
    private val scope: CoroutineScope,
    private val port: PlaybackServicePresentationPort,
    private val prepareInputs: (PlaybackServiceNotificationRenderInputs) -> PlaybackServiceNotificationRenderInputs,
) {
    private var latest: PendingPlaybackNotification? = null
    private var pending: PendingPlaybackNotification? = null
    private var windowJob: Job? = null
    private var confirmationJob: Job? = null

    fun publish(snapshot: PlaybackNotificationSnapshot, inputs: PlaybackServiceNotificationRenderInputs, force: Boolean) {
        if (!force && latest?.snapshot == snapshot) return
        confirmationJob?.cancel()
        confirmationJob = null
        val publication = PendingPlaybackNotification(snapshot, inputs)
        latest = publication
        if (windowJob?.isActive == true) {
            pending = publication
        } else {
            send(publication, retry = 0)
        }
    }

    fun cancel() {
        latest = null
        pending = null
        windowJob?.cancel()
        windowJob = null
        confirmationJob?.cancel()
        confirmationJob = null
    }

    private fun send(publication: PendingPlaybackNotification, retry: Int) {
        if (latest !== publication) return
        val published = try {
            port.publishNotification(prepareInputs(publication.inputs))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            NPLogger.w("NERI-APS", "media notification publication failed", failure)
            false
        }
        windowJob = scope.launch {
            delay(MEDIA_NOTIFICATION_WINDOW_MS)
            windowJob = null
            val next = pending
            pending = null
            if (next != null) send(next, retry = 0)
        }
        val token = if (published) port.notificationPublicationToken() else null
        if (published && token == null) return
        confirmationJob = scope.launch {
            delay(MEDIA_NOTIFICATION_CONFIRMATION_MS * (retry + 1))
            if (latest !== publication || isConfirmed(token)) return@launch
            confirmationJob = null
            if (retry < MEDIA_NOTIFICATION_MAX_RETRIES) {
                send(publication, retry + 1)
            } else {
                NPLogger.w("NERI-APS", "media notification remains unconfirmed after bounded retries")
            }
        }
    }

    private fun isConfirmed(token: String?): Boolean {
        if (token == null) return false
        return try {
            port.isNotificationPublicationCurrent(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            NPLogger.w("NERI-APS", "media notification confirmation failed", failure)
            false
        }
    }
}
