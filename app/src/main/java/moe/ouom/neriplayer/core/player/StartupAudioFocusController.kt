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
    private const val USB_EXCLUSIVE_RECLAIM_FOCUS_DELAY_MS = 120L
    private const val USB_EXCLUSIVE_RECLAIM_FOCUS_CONFIRM_DELAY_MS = 520L
    private const val USB_EXCLUSIVE_SUSTAINED_LOSS_PAUSE_MS = 2_400L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusGain = AudioManager.AUDIOFOCUS_GAIN
    private var hasFocus = false
    private var usbExclusiveGuardEnabled = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        val action = synchronized(lock) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> hasFocus = true
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> hasFocus = false
            }
            when {
                !usbExclusiveGuardEnabled || change == AudioManager.AUDIOFOCUS_GAIN ->
                    FocusLossAction.None
                change == AudioManager.AUDIOFOCUS_LOSS &&
                    PlayerManager.isRecentUsbExclusiveFocusDisruption() ->
                    FocusLossAction.ReclaimThenConfirmExternalAudio
                change == AudioManager.AUDIOFOCUS_LOSS ->
                    FocusLossAction.PauseForExternalAudio
                else ->
                    FocusLossAction.ReclaimShortSystemSound
            }
        }
        NPLogger.d(TAG, "focus change=$change hasFocus=${isFocused()}")
        when (action) {
            FocusLossAction.None -> {
                if (change == AudioManager.AUDIOFOCUS_GAIN) {
                    mainHandler.removeCallbacksAndMessages(RECLAIM_FOCUS_TOKEN)
                }
            }
            FocusLossAction.ReclaimShortSystemSound,
            FocusLossAction.ReclaimThenConfirmExternalAudio -> {
                PlayerManager.markUsbExclusiveFocusDisrupted(change)
                mainHandler.removeCallbacksAndMessages(RECLAIM_FOCUS_TOKEN)
                mainHandler.postDelayed(
                    { requestWithStoredManager("usb_exclusive_focus_loss:$change:immediate") },
                    RECLAIM_FOCUS_TOKEN,
                    0L
                )
                mainHandler.postDelayed(
                    { requestWithStoredManager("usb_exclusive_focus_loss:$change") },
                    RECLAIM_FOCUS_TOKEN,
                    USB_EXCLUSIVE_RECLAIM_FOCUS_DELAY_MS
                )
                mainHandler.postDelayed(
                    { requestWithStoredManager("usb_exclusive_focus_loss:$change:confirm") },
                    RECLAIM_FOCUS_TOKEN,
                    USB_EXCLUSIVE_RECLAIM_FOCUS_CONFIRM_DELAY_MS
                )
                if (action == FocusLossAction.ReclaimThenConfirmExternalAudio) {
                    mainHandler.postDelayed(
                        { pauseForSustainedUsbExclusiveFocusLoss(change) },
                        RECLAIM_FOCUS_TOKEN,
                        USB_EXCLUSIVE_SUSTAINED_LOSS_PAUSE_MS
                    )
                }
            }
            FocusLossAction.PauseForExternalAudio -> {
                mainHandler.removeCallbacksAndMessages(RECLAIM_FOCUS_TOKEN)
                PlayerManager.pauseForUsbExclusiveFocusLoss("audio_focus_loss")
            }
        }
    }

    fun updateForUsbExclusivePlayback(
        context: Context,
        enabled: Boolean,
        reason: String
    ) {
        val hadGuard = synchronized(lock) {
            val wasEnabled = usbExclusiveGuardEnabled
            usbExclusiveGuardEnabled = enabled
            wasEnabled
        }
        if (enabled) {
            request(
                context = context,
                reason = "usb_exclusive:$reason",
                requestedFocusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        } else if (hadGuard) {
            releaseInternal("usb_exclusive_disabled:$reason", force = true)
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
        val shouldUseUsbExclusiveGuard = enabled &&
            usbExclusivePlayback &&
            usbExclusiveNativeActive &&
            !allowMixedPlayback
        if (shouldUseUsbExclusiveGuard) {
            updateForUsbExclusivePlayback(
                context = context,
                enabled = true,
                reason = reason
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
        val releaseReason = when {
            !enabled -> "disabled:$reason"
            allowMixedPlayback -> "mixed_playback:$reason"
            !usbExclusivePlayback -> "usb_exclusive_off:$reason"
            !usbExclusiveNativeActive -> "usb_exclusive_not_native:$reason"
            transportActive -> "transport_active:$reason"
            else -> "usb_exclusive_guard_idle:$reason"
        }
        if (enabled && !allowMixedPlayback && !transportActive) {
            request(
                context = context,
                reason = reason,
                requestedFocusGain = AudioManager.AUDIOFOCUS_GAIN
            )
        } else {
            release(releaseReason)
        }
    }

    fun release(reason: String) {
        releaseInternal(reason, force = reason == "player_release")
    }

    fun forceRelease(reason: String) {
        releaseInternal(reason, force = true)
    }

    private fun releaseInternal(reason: String, force: Boolean) {
        val manager: AudioManager
        val request: AudioFocusRequest
        synchronized(lock) {
            if (!force && usbExclusiveGuardEnabled) {
                NPLogger.d(TAG, "release skipped for USB exclusive guard reason=$reason")
                return
            }
            if (force) {
                usbExclusiveGuardEnabled = false
                mainHandler.removeCallbacksAndMessages(RECLAIM_FOCUS_TOKEN)
            }
            manager = audioManager ?: return
            request = focusRequest ?: return
            hasFocus = false
            focusRequest = null
            audioManager = null
        }

        val result = manager.abandonAudioFocusRequest(request)
        NPLogger.d(TAG, "release reason=$reason result=$result")
    }

    private fun request(
        context: Context,
        reason: String,
        requestedFocusGain: Int
    ) {
        if (isFocused(requestedFocusGain)) {
            NPLogger.d(TAG, "request skipped reason=$reason alreadyFocused=true gain=$requestedFocusGain")
            return
        }
        val appContext = context.applicationContext
        val manager: AudioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousLease = synchronized(lock) {
            if (focusRequest != null && focusGain != requestedFocusGain) {
                val previousManager = audioManager
                val previousRequest = focusRequest
                hasFocus = false
                focusRequest = null
                audioManager = null
                if (previousManager != null && previousRequest != null) {
                    FocusLease(previousManager, previousRequest)
                } else {
                    null
                }
            } else {
                null
            }
        }
        previousLease?.manager?.abandonAudioFocusRequest(previousLease.request)
        val request = synchronized(lock) {
            audioManager = manager
            focusGain = requestedFocusGain
            focusRequest ?: buildFocusRequest(requestedFocusGain).also { focusRequest = it }
        }

        val result = manager.requestAudioFocus(request)
        synchronized(lock) {
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        NPLogger.d(
            TAG,
            "request reason=$reason gain=$requestedFocusGain result=$result hasFocus=${isFocused()}"
        )
    }

    private fun requestWithStoredManager(reason: String) {
        val manager: AudioManager
        val request: AudioFocusRequest
        synchronized(lock) {
            if (!usbExclusiveGuardEnabled) return
            manager = audioManager ?: return
            request = focusRequest ?: return
        }
        val result = manager.requestAudioFocus(request)
        synchronized(lock) {
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        NPLogger.d(
            TAG,
            "request reason=$reason gain=$focusGain result=$result hasFocus=${isFocused()}"
        )
    }

    private fun pauseForSustainedUsbExclusiveFocusLoss(change: Int) {
        val shouldPause = synchronized(lock) {
            usbExclusiveGuardEnabled && !hasFocus
        }
        if (!shouldPause) return
        if (PlayerManager.usbExclusiveAppInForeground) {
            requestWithStoredManager("usb_exclusive_focus_loss:$change:foreground_confirm")
            return
        }
        NPLogger.w(TAG, "sustained USB exclusive focus loss, pause for external audio change=$change")
        PlayerManager.pauseForUsbExclusiveFocusLoss("sustained_audio_focus_loss:$change")
    }

    private fun buildFocusRequest(requestedFocusGain: Int): AudioFocusRequest {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioFocusRequest.Builder(requestedFocusGain)
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

    private fun isUsbExclusiveGuardEnabled(): Boolean = synchronized(lock) {
        usbExclusiveGuardEnabled
    }

    private fun isFocused(requestedFocusGain: Int): Boolean = synchronized(lock) {
        hasFocus && focusGain == requestedFocusGain
    }

    private data class FocusLease(
        val manager: AudioManager,
        val request: AudioFocusRequest
    )

    private enum class FocusLossAction {
        None,
        ReclaimShortSystemSound,
        ReclaimThenConfirmExternalAudio,
        PauseForExternalAudio
    }

    private object RECLAIM_FOCUS_TOKEN
}
