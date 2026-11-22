@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan.timing

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink

internal class LanHardwareRenderersFactory(
    context: Context,
    private val clock: LanHardwareAudioClock
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val system = AudioTrackAudioOutputProvider.Builder(context).build()
        val routes = context.getSystemService(AudioManager::class.java)
        var active: LanHardwareAudioOutput? = null
        val outputs = object : ForwardingAudioOutputProvider(system) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
                LanHardwareAudioOutput(system.getAudioOutput(config), config, routes, clock).also { active = it }
        }
        val sink = DefaultAudioSink.Builder(context)
            .setAudioOutputProvider(outputs)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(false)
            .build()
        return object : ForwardingAudioSink(sink) {
            private var offsetUs = C.TIME_UNSET

            override fun getAudioCapabilities() = system.audioCapabilities

            override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
                offsetUs = outputStreamOffsetUs
                super.setOutputStreamOffsetUs(outputStreamOffsetUs)
            }

            override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                val mediaUs = super.getCurrentPositionUs(sourceEnded)
                if (mediaUs != AudioSink.CURRENT_POSITION_NOT_SET && offsetUs != C.TIME_UNSET && mediaUs >= offsetUs) {
                    // Media3 已根据调速和 seek 的检查点将输出位置换算为媒体时间
                    active?.observeMediaPosition(mediaUs - offsetUs)
                }
                return mediaUs
            }
        }
    }
}
