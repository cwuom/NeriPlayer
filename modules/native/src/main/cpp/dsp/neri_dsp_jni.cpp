#include "dsp/neri_dsp_engine.h"

#include <android/log.h>
#include <jni.h>

#include <new>

#define LOG_TAG "NeriDsp"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using neri::dsp::Engine;

namespace {

constexpr int kStatsLength = 8;

Engine* engineFrom(jlong handle) {
    return reinterpret_cast<Engine*>(handle);
}

uint8_t* directAddress(JNIEnv* env, jobject buffer, jint offset, jint size) {
    if (buffer == nullptr || offset < 0 || size < 0) {
        return nullptr;
    }
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity < 0 || static_cast<jlong>(offset) + size > capacity) {
        return nullptr;
    }
    return data + offset;
}

} // namespace

extern "C"
JNIEXPORT jint JNICALL
Java_moe_ouom_neriplayer_core_player_audio_effects_AudioEffectsNativeBridge_nativeParamCount(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    return neri::dsp::kParamCount;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_audio_effects_AudioEffectsNativeBridge_nativeCreate(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    auto* engine = new (std::nothrow) Engine();
    return reinterpret_cast<jlong>(engine);
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_audio_effects_AudioEffectsNativeBridge_nativeRelease(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    delete engineFrom(handle);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_audio_effects_AudioEffectsNativeBridge_nativeConfigure(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint sampleRate,
    jint channelCount,
    jint encoding
) {
    Engine* engine = engineFrom(handle);
    if (engine == nullptr) {
        return JNI_FALSE;
    }
    try {
        return engine->configure(sampleRate, channelCount, encoding) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::bad_alloc&) {
        LOGW("configure failed: out of memory sampleRate=%d channels=%d", sampleRate, channelCount);
        return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_audio_effects_AudioEffectsNativeBridge_nativeSetParams(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jfloatArray params
) {
    Engine* engine = engineFrom(handle);
    if (engine == nullptr || params == nullptr) {
        return JNI_FALSE;
    }
    const jsize length = env->GetArrayLength(params);
    if (length != neri::dsp::kParamCount) {
        LOGW("setParams rejected: length=%d expected=%d", static_cast<int>(length), neri::dsp::kParamCount);
        return JNI_FALSE;
    }
    float values[neri::dsp::kParamCount];
    env->GetFloatArrayRegion(params, 0, length, values);
    return engine->setParams(values, length) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_moe_ouom_neriplayer_core_player_audio_effects_AudioEffectsNativeBridge_nativeReset(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    Engine* engine = engineFrom(handle);
    if (engine != nullptr) {
        engine->reset();
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_moe_ouom_neriplayer_core_player_audio_effects_AudioEffectsNativeBridge_nativeProcess(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jobject input,
    jint inputOffset,
    jobject output,
    jint outputOffset,
    jint size
) {
    Engine* engine = engineFrom(handle);
    if (engine == nullptr || !engine->configured()) {
        return -1;
    }
    const uint8_t* source = directAddress(env, input, inputOffset, size);
    uint8_t* target = directAddress(env, output, outputOffset, size);
    if (source == nullptr || target == nullptr || size % engine->frameBytes() != 0) {
        return -1;
    }
    return engine->process(source, target, static_cast<size_t>(size));
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_audio_effects_AudioEffectsNativeBridge_nativeReadStats(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlongArray output
) {
    Engine* engine = engineFrom(handle);
    if (engine == nullptr || output == nullptr || env->GetArrayLength(output) < kStatsLength) {
        return JNI_FALSE;
    }
    const neri::dsp::EngineStats stats = engine->takeStats();
    const jlong values[kStatsLength] = {
        stats.processedFrames,
        stats.processingNanos,
        static_cast<jlong>(std::lround(stats.maxLimiterReductionDb * 100.0f)),
        stats.limiterEvents,
        static_cast<jlong>(std::lround(stats.compressorReductionDb * 100.0f)),
        stats.activeStageMask,
        stats.sampleRate,
        stats.recoveries,
    };
    env->SetLongArrayRegion(output, 0, kStatsLength, values);
    return JNI_TRUE;
}
