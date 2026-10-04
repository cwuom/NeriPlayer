package moe.ouom.neriplayer.core.player.service.car

import android.content.Intent
import android.media.session.MediaSession
import android.os.Binder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.player.service.AudioPlayerService

internal class CarPlaybackBinder(
    private val token: () -> MediaSession.Token?,
    val runtimeReady: StateFlow<Boolean>,
) : Binder() {
    val sessionToken: MediaSession.Token? get() = token()
}

internal class CarPlaybackBindingOwner(token: () -> MediaSession.Token?) {
    private val runtimeReady = MutableStateFlow(false)
    private val binder = CarPlaybackBinder(token, runtimeReady.asStateFlow())
    var isBound = false
        private set

    fun bind(intent: Intent?): CarPlaybackBinder? {
        if (!isCarBinding(intent)) return null
        isBound = true
        return binder
    }

    fun unbind(intent: Intent?): Boolean {
        if (!isCarBinding(intent)) return false
        isBound = false
        return true
    }

    fun rebind(intent: Intent?) {
        if (isCarBinding(intent)) isBound = true
    }

    fun markRuntimeReady(ready: Boolean) {
        runtimeReady.value = ready
    }

    private fun isCarBinding(intent: Intent?): Boolean = intent?.action == AudioPlayerService.ACTION_BIND_CAR
}
