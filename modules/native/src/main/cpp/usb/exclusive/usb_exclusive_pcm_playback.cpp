#include "usb/exclusive/usb_exclusive_session_internal.h"

#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <exception>
#include <new>

#define LOG_TAG "NeriUsbExclusive"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace neri::usb::exclusive {

void subtractAtomicFloorZero(std::atomic<int64_t>& value, int64_t amount) {
    int64_t current = value.load();
    while (true) {
        const int64_t updated = std::max<int64_t>(0, current - amount);
        if (value.compare_exchange_weak(current, updated)) {
            return;
        }
    }
}

int64_t queuedPlayerReplayFrames(const UsbExclusiveHandle* handle) {
    if (handle == nullptr || handle->device.frameBytes <= 0) {
        return 0;
    }
    return static_cast<int64_t>(
        handle->player.playerReplayBuffer.queuedBytes() / static_cast<size_t>(handle->device.frameBytes)
    );
}

void clearPlayerReplayState(UsbExclusiveHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    handle->player.playerReplayBuffer.clear();
    handle->player.nextPlayerSequence.store(1);
    handle->player.preserveCancelledPlayerFrames.store(false);
    handle->player.playerReplayFailed.store(false);
}

bool preserveCancelledPlayerFrames(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    const uint8_t* payload,
    size_t payloadCapacity,
    int64_t completedPrefixFrames
) {
    if (handle == nullptr || userData == nullptr ||
        userData->queuedPlayerFrames <= 0) {
        return false;
    }
    if (payload == nullptr || handle->device.frameBytes <= 0 ||
        userData->playerSequence == 0) {
        handle->player.playerReplayFailed.store(true);
        return false;
    }
    const int64_t queuedFrames = userData->queuedPlayerFrames;
    const int64_t completedFrames = std::clamp<int64_t>(
        completedPrefixFrames,
        0,
        queuedFrames
    );
    const int64_t replayFrames = queuedFrames - completedFrames;
    const size_t replayOffset = static_cast<size_t>(completedFrames) *
        static_cast<size_t>(handle->device.frameBytes);
    const size_t replayBytes = static_cast<size_t>(replayFrames) *
        static_cast<size_t>(handle->device.frameBytes);
    if (replayOffset + replayBytes > payloadCapacity ||
        (replayBytes > 0 && !handle->player.playerReplayBuffer.push(
            userData->playerSequence,
            payload + replayOffset,
            replayBytes
        ))) {
        handle->player.playerReplayFailed.store(true);
        return false;
    }
    userData->queuedPlayerFrames = 0;
    userData->playerSequence = 0;
    subtractAtomicFloorZero(handle->player.stagedPlayerFrames, queuedFrames);
    if (completedFrames > 0) {
        handle->player.completedAudioFrames.fetch_add(completedFrames);
    }
    return true;
}

void settlePreparedPlayerFrames(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    int64_t completedFrames
) {
    if (handle == nullptr || userData == nullptr || userData->queuedPlayerFrames <= 0) {
        return;
    }
    const int64_t frames = userData->queuedPlayerFrames;
    userData->queuedPlayerFrames = 0;
    userData->playerSequence = 0;
    subtractAtomicFloorZero(handle->player.stagedPlayerFrames, frames);
    const int64_t boundedCompletedFrames = std::clamp<int64_t>(completedFrames, 0, frames);
    if (boundedCompletedFrames > 0) {
        handle->player.completedAudioFrames.fetch_add(boundedCompletedFrames);
    }
    const int64_t droppedFrames = frames - boundedCompletedFrames;
    if (droppedFrames > 0) {
        handle->player.pcmPipeline.addDroppedFrames(droppedFrames);
    }
}

void settlePreparedPlayerFrames(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    bool completed
) {
    const int64_t queuedFrames = userData != nullptr ? userData->queuedPlayerFrames : 0;
    settlePreparedPlayerFrames(handle, userData, completed ? queuedFrames : 0);
}

bool fillPlayerTransfer(
    UsbExclusiveHandle* handle,
    TransferUserData* userData,
    uint8_t* buffer,
    size_t transferSize
) {
    if ((userData != nullptr && userData->forceSilence) ||
        handle->player.playerStartupPreroll.fillSilenceIfNeeded(
            buffer,
            transferSize,
            handle->device.frameBytes
        )) {
        if (userData != nullptr && userData->forceSilence) {
            std::memset(buffer, 0, transferSize);
        }
        if (userData != nullptr) {
            userData->queuedPlayerFrames = 0;
            userData->playerSequence = 0;
        }
        return true;
    }
    const bool renderPlayerPcm = handle->player.playbackEnabled.load() &&
        handle->recovery.deviceOnline.load() &&
        !handle->player.focusMuted.load();
    const size_t replayBytes = renderPlayerPcm
        ? handle->player.playerReplayBuffer.read(buffer, transferSize)
        : 0;
    const size_t pipelineBytes = handle->player.pcmPipeline.fill(
        buffer + replayBytes,
        transferSize - replayBytes,
        renderPlayerPcm
    );
    const size_t playerBytes = replayBytes + pipelineBytes;
    if (playerBytes > 0) {
        handle->player.pcmPipeline.applyTransportStartRamp(buffer, playerBytes);
    }
    if (userData != nullptr) {
        const int64_t queuedFrames = static_cast<int64_t>(
            playerBytes / static_cast<size_t>(std::max(1, handle->device.frameBytes))
        );
        userData->queuedPlayerFrames = queuedFrames;
        userData->playerSequence = queuedFrames > 0
            ? handle->player.nextPlayerSequence.fetch_add(1)
            : 0;
        handle->player.stagedPlayerFrames.fetch_add(queuedFrames);
    }
    return true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeConfigurePlayerBufferDuration(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jint durationMs
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeConfigurePlayerBufferDuration rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->recovery.closing.load() || holder->device.devh == nullptr) {
        LOGW("nativeConfigurePlayerBufferDuration rejected: closing handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    const int rawDurationMs = static_cast<int>(durationMs);
    const int requestedDurationMs = std::clamp(
        rawDurationMs,
        kMinimumPcmRingDurationMs,
        kMaximumPcmRingDurationMs
    );
    if (requestedDurationMs != rawDurationMs) {
        LOGW(
            "nativeConfigurePlayerBufferDuration clamped: requested=%d applied=%d",
            rawDurationMs,
            requestedDurationMs
        );
    }
    if (holder->transfer.streamSource.load() == StreamSource::PlayerPcm) {
        std::string resizeError;
        if (!holder->player.pcmPipeline.resizeRingDuration(
                requestedDurationMs,
                holder->transfer.transferBytes,
                holder->transfer.baseTransferCount,
                &resizeError
            )) {
            setError(holder.get(), resizeError);
            LOGW(
                "nativeConfigurePlayerBufferDuration resize failed: handle=%lld error=%s",
                static_cast<long long>(handleValue),
                resizeError.c_str()
            );
            return JNI_FALSE;
        }
    }
    holder->player.pcmRingDurationMs = requestedDurationMs;
    LOGI(
        "nativeConfigurePlayerBufferDuration: handle=%lld requested=%d applied=%d "
        "targetTransfers=%d activeTransfers=%d",
        static_cast<long long>(handleValue),
        durationMs,
        holder->player.pcmRingDurationMs,
        holder->transfer.targetTransferCount.load(),
        holder->transfer.inFlightTransfers.load()
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePreparePlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jint inputSampleRate,
    jint inputChannelCount,
    jint inputEncoding
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativePreparePlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->recovery.closing.load() || holder->device.devh == nullptr) {
        LOGW("nativePreparePlayerPcm rejected: closing handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    LOGI(
        "nativePreparePlayerPcm request: handle=%lld inputSr=%d inputCh=%d inputEncoding=%d "
        "outputSr=%d outputCh=%d bits=%d bufferMs=%d running=%d",
        static_cast<long long>(handleValue),
        inputSampleRate,
        inputChannelCount,
        inputEncoding,
        holder->device.sampleRate,
        holder->device.channelCount,
        holder->device.bitsPerSample,
        holder->player.pcmRingDurationMs,
        holder->transfer.running.load() ? 1 : 0
    );
    if (holder->transfer.running.load()) {
        if (!stopStreamingInternal(holder.get())) {
            return JNI_FALSE;
        }
    }
    const int bytesPerSample = neri::usb::bytesPerSampleForEncoding(inputEncoding);
    if (bytesPerSample <= 0 || inputSampleRate < 8000 || inputSampleRate > 768000 ||
        inputChannelCount < 1 || inputChannelCount > 8) {
        setError(holder.get(), "unsupported_player_pcm_format");
        LOGE(
            "nativePreparePlayerPcm unsupported input: sr=%d ch=%d encoding=%d",
            inputSampleRate,
            inputChannelCount,
            inputEncoding
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineConfig config {
        {
            holder->device.sampleRate,
            holder->device.channelCount,
            holder->device.subslotBytes,
            holder->device.bitsPerSample,
            holder->device.frameBytes
        },
        {
            inputSampleRate > 0 ? inputSampleRate : holder->device.sampleRate,
            inputChannelCount > 0 ? inputChannelCount : holder->device.channelCount,
            inputEncoding
        },
        holder->player.pcmRingDurationMs,
        holder->transfer.transferBytes,
        holder->transfer.baseTransferCount
    };
    std::string pipelineError;
    if (!holder->player.pcmPipeline.configure(config, &pipelineError)) {
        setError(holder.get(), pipelineError);
        LOGE("nativePreparePlayerPcm pipeline configure failed: %s", pipelineError.c_str());
        return JNI_FALSE;
    }
    clearPlayerReplayState(holder.get());
    holder->transfer.streamSource.store(StreamSource::PlayerPcm);
    holder->player.playbackEnabled.store(false);
    holder->player.playerPaused.store(false);
    holder->player.stagedPlayerFrames.store(0);
    holder->player.completedAudioFrames.store(0);
    clearError(holder.get());
    LOGI(
        "nativePreparePlayerPcm ok: handle=%lld ringMs=%d transferBytes=%d "
        "baseTransfers=%d reserveTransfers=%d",
        static_cast<long long>(handleValue),
        holder->player.pcmRingDurationMs,
        holder->transfer.transferBytes,
        holder->transfer.baseTransferCount,
        holder->transfer.transferCount
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeWritePlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jobject buffer,
    jint offset,
    jint size,
    jfloat volume
) {
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || buffer == nullptr || size <= 0 || offset < 0) {
        LOGW(
            "nativeWritePlayerPcm rejected: handle=%lld buffer=%d offset=%d size=%d",
            static_cast<long long>(handleValue),
            buffer != nullptr ? 1 : 0,
            offset,
            size
        );
        return 0;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->recovery.closing.load() || !holder->recovery.deviceOnline.load() || holder->device.devh == nullptr ||
        holder->transfer.streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativeWritePlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->recovery.closing.load() ? 1 : 0,
            holder->device.devh != nullptr ? 1 : 0,
            sourceName(holder->transfer.streamSource.load())
        );
        return 0;
    }
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (data == nullptr || capacity < 0 || static_cast<jlong>(offset) + size > capacity) {
        setError(holder.get(), "invalid_direct_pcm_buffer");
        LOGE(
            "nativeWritePlayerPcm invalid direct buffer: handle=%lld capacity=%lld offset=%d size=%d",
            static_cast<long long>(handleValue),
            static_cast<long long>(capacity),
            offset,
            size
        );
        return 0;
    }
    const float requestedVolume = std::clamp(volume, 0.0f, 1.0f);
    holder->player.playerVolume.store(requestedVolume);
    holder->player.pcmPipeline.setTargetGain(holder->player.focusMuted.load() ? 0.0f : requestedVolume);
    std::string pipelineError;
    size_t written = 0;
    try {
        written = holder->player.pcmPipeline.write(
            data + offset,
            static_cast<size_t>(size),
            &pipelineError
        );
    } catch (const std::bad_alloc&) {
        pipelineError = "pcm_write_allocation_failed";
    } catch (const std::exception& error) {
        pipelineError = std::string("pcm_write_failed:") + error.what();
    }
    if (!pipelineError.empty()) {
        setError(holder.get(), pipelineError);
        LOGW("nativeWritePlayerPcm pipeline warning: %s", pipelineError.c_str());
    }
    if (written > 0) {
        activateBufferedIsoReserveTransfers(holder.get());
    }
    if (written == 0 || written < static_cast<size_t>(size)) {
        const int warningIndex = holder->player.shortWriteWarnings.fetch_add(1);
        if (warningIndex < 8) {
            const neri::usb::PcmPipelineSnapshot pcm = holder->player.pcmPipeline.snapshot();
            LOGW(
                "nativeWritePlayerPcm short write: handle=%lld requested=%d written=%zu "
                "level=%zu/%zu free=%zu backpressureEvents=%lld backpressureCurrentMs=%lld "
                "running=%d playback=%d input=%lld output=%lld dropped=%lld underrun=%lld",
                static_cast<long long>(handleValue),
                size,
                written,
                pcm.levelBytes,
                pcm.capacityBytes,
                pcm.freeBytes,
                static_cast<long long>(pcm.backpressureEvents),
                static_cast<long long>(pcm.backpressureCurrentUs / 1000),
                holder->transfer.running.load() ? 1 : 0,
                holder->player.playbackEnabled.load() ? 1 : 0,
                static_cast<long long>(pcm.inputBytes),
                static_cast<long long>(pcm.outputBytes),
                static_cast<long long>(pcm.droppedBytes),
                static_cast<long long>(pcm.underrunBytes)
            );
        }
    }
    return static_cast<jint>(written);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeDrainPlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->recovery.closing.load() || !holder->recovery.deviceOnline.load() || holder->device.devh == nullptr ||
        holder->transfer.streamSource.load() != StreamSource::PlayerPcm) {
        return JNI_FALSE;
    }
    std::string pipelineError;
    bool drained = false;
    try {
        drained = holder->player.pcmPipeline.drainResampler(&pipelineError);
    } catch (const std::exception& error) {
        pipelineError = std::string("pcm_drain_failed:") + error.what();
    }
    if (!pipelineError.empty()) {
        setError(holder.get(), pipelineError);
        LOGW("nativeDrainPlayerPcm pipeline warning: %s", pipelineError.c_str());
    }
    if (drained) {
        activateBufferedIsoReserveTransfers(holder.get());
    }
    return drained ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePlayPlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativePlayPlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->recovery.closing.load() || !holder->recovery.deviceOnline.load() || holder->device.devh == nullptr ||
        holder->transfer.streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativePlayPlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->recovery.closing.load() ? 1 : 0,
            holder->device.devh != nullptr ? 1 : 0,
            sourceName(holder->transfer.streamSource.load())
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineSnapshot before = holder->player.pcmPipeline.snapshot();
    LOGI(
        "nativePlayPlayerPcm request: handle=%lld running=%d queued=%zu/%zu completed=%lld",
        static_cast<long long>(handleValue),
        holder->transfer.running.load() ? 1 : 0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->player.completedAudioFrames.load())
    );
    holder->player.playerPaused.store(false);
    holder->player.playbackEnabled.store(true);
    if (startStreamingSafely(holder.get(), StreamSource::PlayerPcm)) {
        LOGI("nativePlayPlayerPcm ok: handle=%lld", static_cast<long long>(handleValue));
        return JNI_TRUE;
    }
    holder->player.playbackEnabled.store(false);
    LOGE(
        "nativePlayPlayerPcm failed: handle=%lld error=%s",
        static_cast<long long>(handleValue),
        getErrorCopy(holder.get()).c_str()
    );
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeStartPlayerPcm(
    JNIEnv* env,
    jclass clazz,
    jlong handleValue
) {
    return Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePlayPlayerPcm(
        env,
        clazz,
        handleValue
    );
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativePausePlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativePausePlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->recovery.closing.load() || holder->device.devh == nullptr ||
        holder->transfer.streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativePausePlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->recovery.closing.load() ? 1 : 0,
            holder->device.devh != nullptr ? 1 : 0,
            sourceName(holder->transfer.streamSource.load())
        );
        return JNI_FALSE;
    }
    const neri::usb::PcmPipelineSnapshot before = holder->player.pcmPipeline.snapshot();
    holder->player.playbackEnabled.store(false);
    holder->player.playerReplayFailed.store(false);
    holder->player.preserveCancelledPlayerFrames.store(true);
    const bool stopped = stopStreamingInternal(holder.get());
    holder->player.preserveCancelledPlayerFrames.store(false);
    const bool replayPreserved = !holder->player.playerReplayFailed.load();
    const bool paused = stopped && replayPreserved;
    holder->player.playerPaused.store(paused);
    if (!replayPreserved) {
        setError(holder.get(), "pause_replay_preservation_failed");
    }
    LOGI(
        "nativePausePlayerPcm %s: handle=%lld running=%d level=%zu/%zu queued=%lld replay=%lld",
        paused ? "ok" : "failed",
        static_cast<long long>(handleValue),
        holder->transfer.running.load() ? 1 : 0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->player.pcmPipeline.queuedFrames()),
        static_cast<long long>(queuedPlayerReplayFrames(holder.get()))
    );
    return paused ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeFlushPlayerPcm(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        LOGW("nativeFlushPlayerPcm rejected: invalid handle=%lld", static_cast<long long>(handleValue));
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> apiGuard(holder->apiLock);
    if (holder->recovery.closing.load() || holder->device.devh == nullptr ||
        holder->transfer.streamSource.load() != StreamSource::PlayerPcm) {
        LOGW(
            "nativeFlushPlayerPcm rejected by state: handle=%lld closing=%d devh=%d source=%s",
            static_cast<long long>(handleValue),
            holder->recovery.closing.load() ? 1 : 0,
            holder->device.devh != nullptr ? 1 : 0,
            sourceName(holder->transfer.streamSource.load())
        );
        return JNI_FALSE;
    }

    const bool transportWasRunning = holder->transfer.running.load();
    holder->player.playbackEnabled.store(false);
    holder->player.playerPaused.store(false);
    const neri::usb::PcmPipelineSnapshot before = holder->player.pcmPipeline.snapshot();
    LOGI(
        "nativeFlushPlayerPcm begin: handle=%lld restart=%d resume=%d level=%zu/%zu completed=%lld",
        static_cast<long long>(handleValue),
        transportWasRunning ? 1 : 0,
        0,
        before.levelBytes,
        before.capacityBytes,
        static_cast<long long>(holder->player.completedAudioFrames.load())
    );
    if (!stopStreamingInternal(holder.get())) {
        return JNI_FALSE;
    }
    clearPlayerReplayState(holder.get());
    holder->player.pcmPipeline.clear();
    holder->player.pcmPipeline.resetCounters();
    holder->player.stagedPlayerFrames.store(0);
    holder->player.completedAudioFrames.store(0);

    clearError(holder.get());
    LOGI(
        "nativeFlushPlayerPcm done: handle=%lld running=%d playback=%d",
        static_cast<long long>(handleValue),
        holder->transfer.running.load() ? 1 : 0,
        holder->player.playbackEnabled.load() ? 1 : 0
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeSetPlayerVolume(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jfloat volume
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->recovery.closing.load() || !holder->recovery.deviceOnline.load()) {
        return JNI_FALSE;
    }
    const float requestedVolume = std::clamp(static_cast<float>(volume), 0.0f, 1.0f);
    holder->player.playerVolume.store(requestedVolume);
    holder->player.pcmPipeline.setTargetGain(holder->player.focusMuted.load() ? 0.0f : requestedVolume);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeSetPlayerBitPerfect(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jboolean enabled
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->recovery.closing.load()) {
        return JNI_FALSE;
    }
    const bool bitPerfect = enabled == JNI_TRUE;
    if (holder->player.pcmPipeline.bitPerfect() != bitPerfect) {
        holder->player.pcmPipeline.setBitPerfect(bitPerfect);
        LOGI(
            "nativeSetPlayerBitPerfect: handle=%lld enabled=%d",
            static_cast<long long>(handleValue),
            bitPerfect ? 1 : 0
        );
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeSetPlayerFocusMuted(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue,
    jboolean muted
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->recovery.closing.load() || !holder->recovery.deviceOnline.load()) {
        return JNI_FALSE;
    }
    const bool shouldMute = muted == JNI_TRUE;
    holder->player.focusMuted.store(shouldMute);
    holder->player.pcmPipeline.setTargetGain(
        shouldMute ? 0.0f : std::clamp(holder->player.playerVolume.load(), 0.0f, 1.0f)
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeGetCompletedAudioFrames(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    return holder != nullptr ? static_cast<jlong>(holder->player.completedAudioFrames.load()) : 0L;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeGetQueuedPlayerFrames(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr) {
        return 0L;
    }
    return static_cast<jlong>(holder->player.pcmPipeline.queuedFrames()) +
        holder->player.stagedPlayerFrames.load() +
        queuedPlayerReplayFrames(holder.get());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_nativeGetPlayerPcmFreeBytes(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handleValue
) {
    static_cast<void>(env);
    const auto holder = acquireHandle(handleValue);
    if (holder == nullptr || holder->recovery.closing.load()) {
        return -1L;
    }
    return static_cast<jlong>(holder->player.pcmPipeline.snapshot().freeBytes);
}

} // namespace neri::usb::exclusive
