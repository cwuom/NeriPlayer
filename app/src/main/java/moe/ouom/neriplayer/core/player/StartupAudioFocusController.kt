package moe.ouom.neriplayer.core.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import moe.ouom.neriplayer.util.NPLogger

internal object StartupAudioFocusController {
    private const val TAG = "NERI-StartupFocus"

    private val lock = Any()
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(lock) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> hasFocus = true
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> hasFocus = false
            }
        }
        NPLogger.d(TAG, "focus change=$change hasFocus=${isFocused()}")
    }

    fun updateForForeground(
        context: Context,
        enabled: Boolean,
        allowMixedPlayback: Boolean,
        transportActive: Boolean,
        reason: String
    ) {
        when {
            !enabled -> release("disabled:$reason")
            allowMixedPlayback -> release("mixed_playback:$reason")
            transportActive -> release("transport_active:$reason")
            else -> request(context, reason)
        }
    }

    fun release(reason: String) {
        val manager: AudioManager
        val request: AudioFocusRequest
        synchronized(lock) {
            manager = audioManager ?: return
            request = focusRequest ?: return
            if (!hasFocus) return
            hasFocus = false
        }

        val result = manager.abandonAudioFocusRequest(request)
        NPLogger.d(TAG, "release reason=$reason result=$result")
    }

    private fun request(context: Context, reason: String) {
        if (isFocused()) {
            NPLogger.d(TAG, "request skipped reason=$reason alreadyFocused=true")
            return
        }
        val appContext = context.applicationContext
        val manager: AudioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = synchronized(lock) {
            audioManager = manager
            focusRequest ?: buildFocusRequest().also { focusRequest = it }
        }

        val result = manager.requestAudioFocus(request)
        synchronized(lock) {
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        NPLogger.d(TAG, "request reason=$reason result=$result hasFocus=${isFocused()}")
    }

    private fun buildFocusRequest(): AudioFocusRequest {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(
                focusChangeListener,
                Handler(Looper.getMainLooper())
            )
            .build()
    }

    private fun isFocused(): Boolean = synchronized(lock) { hasFocus }
}
