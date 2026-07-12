package moe.ouom.neriplayer.core.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveSessionController
import moe.ouom.neriplayer.util.NPLogger

internal object StartupAudioFocusController {
    private const val TAG = "NERI-StartupFocus"
    private val lock = Any()
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var usbExclusiveGuardEnabled = false
    private var requestedFocusGain = 0
    private var focusLeaseGeneration = 0L

    fun updateForUsbExclusivePlayback(
        context: Context,
        enabled: Boolean,
        reason: String
    ) {
        val hadGuard = synchronized(lock) { usbExclusiveGuardEnabled }
        if (enabled) {
            val focusGranted = request(
                context = context,
                reason = "usb_exclusive_guard:$reason",
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                usbExclusiveGuard = true
            )
            UsbExclusiveSessionController.setPlayerFocusSuppressed(
                suppressed = !focusGranted,
                reason = "usb_exclusive_guard:$reason"
            )
            if (!focusGranted) {
                PlayerManager.pauseForUsbExclusiveFocusLoss(AudioManager.AUDIOFOCUS_LOSS)
            }
            NPLogger.d(
                TAG,
                "USB exclusive holds transient exclusive audio focus granted=$focusGranted reason=$reason " +
                    "package=${context.applicationContext.packageName}"
            )
        } else if (hadGuard) {
            releaseInternal("usb_exclusive_disabled:$reason", clearUsbGuard = true)
        }
    }

    fun updateForForeground(
        context: Context,
        enabled: Boolean,
        allowMixedPlayback: Boolean,
        usbExclusivePlayback: Boolean,
        usbExclusiveNativeActive: Boolean,
        transportActive: Boolean,
        reason: String
    ) {
        val shouldUseUsbExclusiveGuard = usbExclusivePlayback &&
            usbExclusiveNativeActive &&
            !allowMixedPlayback
        if (shouldUseUsbExclusiveGuard) {
            updateForUsbExclusivePlayback(
                context = context,
                enabled = true,
                reason = "$reason:native=$usbExclusiveNativeActive"
            )
            return
        }
        if (isUsbExclusiveGuardEnabled()) {
            updateForUsbExclusivePlayback(
                context = context,
                enabled = false,
                reason = reason
            )
        }
        if (enabled && !allowMixedPlayback && !transportActive) {
            request(
                context = context,
                reason = reason,
                focusGain = AudioManager.AUDIOFOCUS_GAIN,
                usbExclusiveGuard = false
            )
        } else {
            release("focus_not_owned:$reason:transport=$transportActive")
        }
    }

    fun release(reason: String) {
        releaseInternal(reason, clearUsbGuard = true)
    }

    fun forceRelease(reason: String) {
        release(reason)
    }

    fun acquireUsbExclusiveTransportFocus(
        context: Context,
        enabled: Boolean,
        reason: String
    ): Boolean {
        if (!enabled) return true
        val focusGranted = request(
            context = context,
            reason = "usb_exclusive_transport:$reason",
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            usbExclusiveGuard = true
        )
        if (!focusGranted) {
            UsbExclusiveSessionController.setPlayerFocusSuppressed(
                suppressed = true,
                reason = "usb_exclusive_transport_denied:$reason"
            )
            PlayerManager.pauseForUsbExclusiveFocusLoss(AudioManager.AUDIOFOCUS_LOSS)
        }
        return focusGranted
    }

    private fun request(
        context: Context,
        reason: String,
        focusGain: Int,
        usbExclusiveGuard: Boolean
    ): Boolean {
        val matchingLeaseExists = synchronized(lock) {
            focusRequest != null &&
                hasFocus &&
                requestedFocusGain == focusGain &&
                usbExclusiveGuardEnabled == usbExclusiveGuard
        }
        if (matchingLeaseExists) return true
        releaseInternal("replace_focus:$reason", clearUsbGuard = false)
        val manager = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val lease = synchronized(lock) {
            usbExclusiveGuardEnabled = usbExclusiveGuard
            requestedFocusGain = focusGain
            focusLeaseGeneration += 1L
            val generation = focusLeaseGeneration
            val request = buildFocusRequest(
                generation = generation,
                focusGain = focusGain,
                usbExclusiveGuard = usbExclusiveGuard
            )
            audioManager = manager
            focusRequest = request
            generation to request
        }
        val (generation, request) = lease
        val result = manager.requestAudioFocus(request)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        val stillCurrent = synchronized(lock) {
            if (focusLeaseGeneration != generation || focusRequest !== request) {
                false
            } else {
                hasFocus = granted
                if (!granted) {
                    audioManager = null
                    focusRequest = null
                    requestedFocusGain = 0
                    usbExclusiveGuardEnabled = false
                }
                true
            }
        }
        if (!stillCurrent && granted) {
            runCatching { manager.abandonAudioFocusRequest(request) }
        }
        NPLogger.d(TAG, "request reason=$reason result=$result owned=${isFocused()}")
        return stillCurrent && granted
    }

    private fun releaseInternal(reason: String, clearUsbGuard: Boolean) {
        val lease = synchronized(lock) {
            if (clearUsbGuard) {
                usbExclusiveGuardEnabled = false
            }
            focusLeaseGeneration += 1L
            hasFocus = false
            requestedFocusGain = 0
            val manager = audioManager
            val request = focusRequest
            audioManager = null
            focusRequest = null
            if (manager != null && request != null) manager to request else null
        }
        UsbExclusiveSessionController.setPlayerFocusSuppressed(false, reason)
        val result = lease?.let { (manager, request) ->
            runCatching { manager.abandonAudioFocusRequest(request) }
                .onFailure { error ->
                    NPLogger.w(TAG, "release failed reason=$reason", error)
                }
                .getOrNull()
        }
        NPLogger.d(TAG, "release reason=$reason result=${result ?: "not_owned"}")
    }

    private fun buildFocusRequest(
        generation: Long,
        focusGain: Int,
        usbExclusiveGuard: Boolean
    ): AudioFocusRequest {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener listener@{ change ->
            val focusOwned = synchronized(lock) {
                if (focusLeaseGeneration != generation || focusRequest == null) {
                    return@listener
                }
                hasFocus = change == AudioManager.AUDIOFOCUS_GAIN
                hasFocus
            }
            if (usbExclusiveGuard) {
                val suppressPlayerPcm = shouldSuppressUsbExclusiveForFocusChange(change)
                if (suppressPlayerPcm) {
                    PlayerManager.markUsbExclusiveFocusDisrupted(change)
                }
                if (shouldPauseUsbExclusiveForFocusChange(change)) {
                    PlayerManager.pauseForUsbExclusiveFocusLoss(change)
                }
                UsbExclusiveSessionController.setPlayerFocusSuppressed(
                    suppressed = suppressPlayerPcm,
                    reason = "audio_focus_change:$change"
                )
            }
            NPLogger.d(TAG, "focus change=$change owned=$focusOwned generation=$generation")
        }
        return AudioFocusRequest.Builder(focusGain)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(!usbExclusiveGuard)
            .setOnAudioFocusChangeListener(
                listener,
                Handler(Looper.getMainLooper())
            )
            .build()
    }

    private fun isFocused(): Boolean = synchronized(lock) { hasFocus }

    private fun isUsbExclusiveGuardEnabled(): Boolean = synchronized(lock) {
        usbExclusiveGuardEnabled
    }

}

internal fun shouldSuppressUsbExclusiveForFocusChange(change: Int): Boolean {
    return when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> false
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> true
        else -> true
    }
}

internal fun shouldPauseUsbExclusiveForFocusChange(change: Int): Boolean {
    return when (change) {
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> true
        else -> false
    }
}
