package moe.ouom.neriplayer.core.player.lan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** Owns device media volume observation for the LAN session. No player gain multiplication. */
internal class LanMediaVolume(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var listener: ((Float) -> Unit)? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") listener?.invoke(read())
        }
    }
    fun read(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }
    fun apply(value: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (value.coerceIn(0f, 1f) * max).roundToInt()
        if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        listener?.invoke(read())
    }
    fun start(changed: (Float) -> Unit) {
        stop(); listener = changed
        ContextCompat.registerReceiver(context, receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }
    fun stop() { if (registered) context.unregisterReceiver(receiver); registered = false; listener = null }
}
