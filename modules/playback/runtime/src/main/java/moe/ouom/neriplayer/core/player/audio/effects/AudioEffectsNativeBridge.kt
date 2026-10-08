package moe.ouom.neriplayer.core.player.audio.effects

import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.playback.effects.NeriDspParams
import java.nio.ByteBuffer

internal const val AUDIO_EFFECTS_STATUS_IDLE = 1
internal const val AUDIO_EFFECTS_STATS_LENGTH = 8

/** 播放线程独占使用；所有方法都必须在同一个音频线程调用 */
internal interface AudioEffectsDspEngine {
    fun configure(sampleRate: Int, channelCount: Int, encoding: Int): Boolean
    fun setParams(params: FloatArray): Boolean
    fun reset()
    fun process(input: ByteBuffer, inputOffset: Int, output: ByteBuffer, outputOffset: Int, size: Int): Int
    fun readStats(output: LongArray): Boolean
    fun release()
}

internal class NativeAudioEffectsDspEngine private constructor(private var handle: Long) : AudioEffectsDspEngine {
    override fun configure(sampleRate: Int, channelCount: Int, encoding: Int): Boolean =
        handle != 0L && AudioEffectsNativeBridge.configure(handle, sampleRate, channelCount, encoding)

    override fun setParams(params: FloatArray): Boolean =
        handle != 0L && AudioEffectsNativeBridge.setParams(handle, params)

    override fun reset() {
        if (handle != 0L) AudioEffectsNativeBridge.reset(handle)
    }

    override fun process(input: ByteBuffer, inputOffset: Int, output: ByteBuffer, outputOffset: Int, size: Int): Int {
        if (handle == 0L) return -1
        return AudioEffectsNativeBridge.process(handle, input, inputOffset, output, outputOffset, size)
    }

    override fun readStats(output: LongArray): Boolean =
        handle != 0L && AudioEffectsNativeBridge.readStats(handle, output)

    override fun release() {
        val current = handle
        handle = 0L
        if (current != 0L) AudioEffectsNativeBridge.release(current)
    }

    companion object {
        fun createOrNull(): AudioEffectsDspEngine? {
            val handle = AudioEffectsNativeBridge.create()
            return if (handle == 0L) null else NativeAudioEffectsDspEngine(handle)
        }
    }
}

internal object AudioEffectsNativeBridge {
    private const val TAG = "NERI-AudioEffects"

    @Volatile
    private var libraryReady = false

    @Volatile
    private var loadAttempted = false

    fun ensureLoaded(): Boolean {
        if (libraryReady) return true
        if (loadAttempted) return false
        synchronized(this) {
            if (libraryReady) return true
            if (loadAttempted) return false
            loadAttempted = true
            libraryReady = try {
                System.loadLibrary("_neri")
                val nativeCount = nativeParamCount()
                if (nativeCount != NeriDspParams.COUNT) {
                    NPLogger.e(TAG, "DSP parameter layout mismatch: native=$nativeCount kotlin=${NeriDspParams.COUNT}")
                }
                nativeCount == NeriDspParams.COUNT
            } catch (error: LinkageError) {
                NPLogger.e(TAG, "DSP native library unavailable", error)
                false
            }
            return libraryReady
        }
    }

    fun create(): Long {
        if (!ensureLoaded()) return 0L
        return guarded("create", 0L) { nativeCreate() }
    }

    fun release(handle: Long) = guarded("release", Unit) { nativeRelease(handle) }

    fun configure(handle: Long, sampleRate: Int, channelCount: Int, encoding: Int): Boolean =
        guarded("configure", false) { nativeConfigure(handle, sampleRate, channelCount, encoding) }

    fun setParams(handle: Long, params: FloatArray): Boolean =
        guarded("setParams", false) { nativeSetParams(handle, params) }

    fun reset(handle: Long) = guarded("reset", Unit) { nativeReset(handle) }

    fun process(handle: Long, input: ByteBuffer, inputOffset: Int, output: ByteBuffer, outputOffset: Int, size: Int): Int =
        guarded("process", -1) { nativeProcess(handle, input, inputOffset, output, outputOffset, size) }

    fun readStats(handle: Long, output: LongArray): Boolean =
        guarded("readStats", false) { nativeReadStats(handle, output) }

    private inline fun <T> guarded(operation: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (error: LinkageError) {
            NPLogger.e(TAG, "$operation failed", error)
            fallback
        }
    }

    @JvmStatic
    private external fun nativeParamCount(): Int

    @JvmStatic
    private external fun nativeCreate(): Long

    @JvmStatic
    private external fun nativeRelease(handle: Long)

    @JvmStatic
    private external fun nativeConfigure(handle: Long, sampleRate: Int, channelCount: Int, encoding: Int): Boolean

    @JvmStatic
    private external fun nativeSetParams(handle: Long, params: FloatArray): Boolean

    @JvmStatic
    private external fun nativeReset(handle: Long)

    @JvmStatic
    private external fun nativeProcess(
        handle: Long,
        input: ByteBuffer,
        inputOffset: Int,
        output: ByteBuffer,
        outputOffset: Int,
        size: Int
    ): Int

    @JvmStatic
    private external fun nativeReadStats(handle: Long, output: LongArray): Boolean
}
