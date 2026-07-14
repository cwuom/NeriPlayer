#include "usb/pcm/usb_pcm_pipeline.h"

#include <array>
#include <cassert>
#include <chrono>
#include <cstring>
#include <cstdint>
#include <limits>
#include <string>
#include <thread>
#include <vector>

namespace {

neri::usb::PcmPipelineConfig configFor(int inputRate, int outputRate) {
    return {
        { outputRate, 2, 2, 16, 4 },
        { inputRate, 2, 2 },
        250,
        768,
        6
    };
}

neri::usb::PcmPipelineConfig configFor32Bit(int inputRate, int outputRate) {
    return {
        { outputRate, 2, 4, 32, 8 },
        { inputRate, 2, 4 },
        250,
        1536,
        12
    };
}

neri::usb::PcmPipelineConfig configFor24BitIn32Container(int inputRate, int outputRate) {
    return {
        { outputRate, 2, 4, 24, 8 },
        { inputRate, 2, 22 },
        250,
        1536,
        12
    };
}

void writeFloatSample(std::vector<uint8_t>& output, size_t byteOffset, float value) {
    std::memcpy(output.data() + byteOffset, &value, sizeof(value));
}

void writeInt32Sample(std::vector<uint8_t>& output, size_t byteOffset, int32_t value) {
    std::memcpy(output.data() + byteOffset, &value, sizeof(value));
}

int16_t readInt16Sample(const std::vector<uint8_t>& output, size_t byteOffset) {
    int16_t value = 0;
    std::memcpy(&value, output.data() + byteOffset, sizeof(value));
    return value;
}

void verifiesExactRatePassThroughAcrossWrites() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(48000, 48000), &error));
    const std::array<uint8_t, 8> first { 1, 0, 2, 0, 3, 0, 4, 0 };
    const std::array<uint8_t, 8> second { 5, 0, 6, 0, 7, 0, 8, 0 };
    assert(pipeline.write(first.data(), first.size(), &error) == first.size());
    assert(pipeline.write(second.data(), second.size(), &error) == second.size());

    std::array<uint8_t, 16> output {};
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());
    const std::array<uint8_t, 16> expected {
        1, 0, 2, 0, 3, 0, 4, 0,
        5, 0, 6, 0, 7, 0, 8, 0
    };
    assert(output == expected);
}

void verifiesStreamingResampleKeepsLongTermFrameCount() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(44100, 48000), &error));
    constexpr int inputFramesPerChunk = 441;
    constexpr int chunkCount = 10;
    std::vector<uint8_t> chunk(static_cast<size_t>(inputFramesPerChunk) * 4, 0);
    for (int chunkIndex = 0; chunkIndex < chunkCount; ++chunkIndex) {
        assert(pipeline.write(chunk.data(), chunk.size(), &error) == chunk.size());
    }
    assert(pipeline.queuedFrames() == 4799);
}

void verifiesPausePreservesQueuedAudio() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(48000, 48000), &error));
    const std::array<uint8_t, 8> input { 1, 0, 2, 0, 3, 0, 4, 0 };
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    std::array<uint8_t, 4> silence { 1, 1, 1, 1 };
    assert(pipeline.fill(silence.data(), silence.size(), false) == 0);
    assert((silence == std::array<uint8_t, 4> {}));
    assert(pipeline.queuedFrames() == 2);
    const auto snapshot = pipeline.snapshot();
    assert(snapshot.pausedZeroFillBytes == 4);
}

void verifiesResumeAfterSilentOutputRampsFromZero() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(48000, 48000), &error));
    pipeline.setTargetGain(1.0f);

    const std::array<uint8_t, 8> input {
        0xff, 0x7f, 0xff, 0x7f,
        0xff, 0x7f, 0xff, 0x7f
    };
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());

    std::array<uint8_t, 4> pausedOutput { 1, 1, 1, 1 };
    assert(pipeline.fill(pausedOutput.data(), pausedOutput.size(), false) == 0);
    assert((pausedOutput == std::array<uint8_t, 4> {}));

    std::vector<uint8_t> resumedOutput(8U, 0);
    assert(pipeline.fill(resumedOutput.data(), resumedOutput.size(), true) == input.size());

    const int16_t firstSample = readInt16Sample(resumedOutput, 0);
    const int16_t secondSample = readInt16Sample(resumedOutput, 4);
    assert(firstSample >= 0);
    assert(firstSample < 64);
    assert(secondSample > firstSample);

    const auto snapshot = pipeline.snapshot();
    assert(snapshot.appliedGain > 0.0f);
    assert(snapshot.appliedGain < 0.01f);
}

void verifiesTransportStartRampRearmsWithoutDroppingQueuedAudio() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(48000, 48000), &error));
    pipeline.setTargetGain(1.0f);

    constexpr size_t rampFrames = 48000U * 80U / 1000U;
    constexpr size_t retainedFrames = 2U;
    std::vector<uint8_t> input((rampFrames + retainedFrames) * 4U, 0);
    const int16_t fullScale = std::numeric_limits<int16_t>::max();
    for (size_t offset = 0; offset < input.size(); offset += sizeof(fullScale)) {
        std::memcpy(input.data() + offset, &fullScale, sizeof(fullScale));
    }
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());

    const size_t queuedBeforeStart = pipeline.queuedFrames();
    pipeline.armTransportStartRamp();
    assert(pipeline.queuedFrames() == queuedBeforeStart);

    std::vector<uint8_t> firstOutput(8U, 0);
    assert(pipeline.fill(firstOutput.data(), firstOutput.size(), true) == firstOutput.size());
    pipeline.applyTransportStartRamp(firstOutput.data(), firstOutput.size());
    const int16_t firstSample = readInt16Sample(firstOutput, 0);
    const int16_t secondSample = readInt16Sample(firstOutput, 4);
    assert(firstSample == 0);
    assert(secondSample > firstSample);

    std::vector<uint8_t> remainingRampOutput((rampFrames - 2U) * 4U, 0);
    assert(pipeline.fill(
        remainingRampOutput.data(),
        remainingRampOutput.size(),
        true
    ) == remainingRampOutput.size());
    pipeline.applyTransportStartRamp(
        remainingRampOutput.data(),
        remainingRampOutput.size()
    );
    const int16_t finalRampSample = readInt16Sample(
        remainingRampOutput,
        remainingRampOutput.size() - 4U
    );
    assert(finalRampSample > 32000);

    const size_t queuedBeforeRestart = pipeline.queuedFrames();
    assert(queuedBeforeRestart == retainedFrames);
    pipeline.armTransportStartRamp();
    assert(pipeline.queuedFrames() == queuedBeforeRestart);

    std::vector<uint8_t> restartedOutput(8U, 0);
    for (size_t offset = 0; offset < restartedOutput.size(); offset += sizeof(fullScale)) {
        std::memcpy(restartedOutput.data() + offset, &fullScale, sizeof(fullScale));
    }
    pipeline.applyTransportStartRamp(restartedOutput.data(), restartedOutput.size());
    assert(pipeline.queuedFrames() == queuedBeforeRestart);
    const int16_t restartedFirstSample = readInt16Sample(restartedOutput, 0);
    const int16_t restartedSecondSample = readInt16Sample(restartedOutput, 4);
    assert(restartedFirstSample == 0);
    assert(restartedSecondSample > restartedFirstSample);
}

void verifiesUnsupportedEncodingIsRejected() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    auto config = configFor(48000, 48000);
    config.input.encoding = -1;
    assert(!pipeline.configure(config, &error));
    assert(error == "unsupported_player_pcm_format");
}

void verifiesRuntimeRingResizePreservesQueuedAudio() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(48000, 48000), &error));
    const std::array<uint8_t, 8> input { 1, 0, 2, 0, 3, 0, 4, 0 };
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    assert(pipeline.resizeRingDuration(1000, 768, 6, &error));
    assert(pipeline.queuedFrames() == 2);

    std::array<uint8_t, 8> output {};
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());
    assert(output == input);
}

void verifiesHighResolutionRingUsesBoundedAllocation() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    auto config = configFor(768000, 768000);
    config.output = { 768000, 8, 4, 32, 32 };
    config.input = { 768000, 8, 2 };
    config.ringDurationMs = 12000;
    assert(pipeline.configure(config, &error));
    const auto snapshot = pipeline.snapshot();
    assert(snapshot.capacityBytes <= 64U * 1024U * 1024U);
    assert(snapshot.capacityBytes % 32U == 0U);
}

void verifiesBackpressureSnapshotTracksFullRing() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    auto config = configFor(48000, 48000);
    config.ringDurationMs = 1;
    config.transferBytes = 4;
    config.transferCount = 1;
    assert(pipeline.configure(config, &error));

    const auto initial = pipeline.snapshot();
    std::vector<uint8_t> input(initial.capacityBytes, 0);
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    assert(pipeline.write(input.data(), 4, &error) == 0);

    const auto full = pipeline.snapshot();
    assert(full.freeBytes == 0);
    assert(full.maxLevelBytes == full.capacityBytes);
    assert(full.backpressureEvents == 1);
    assert(full.backpressureCurrentUs >= 0);

    std::this_thread::sleep_for(std::chrono::milliseconds(1));
    std::array<uint8_t, 4> output {};
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());

    const auto recovered = pipeline.snapshot();
    assert(recovered.freeBytes >= output.size());
    assert(recovered.backpressureEvents == 1);
    assert(recovered.backpressureCurrentUs == 0);
    assert(recovered.backpressureTotalUs >= full.backpressureCurrentUs);
}

void verifiesFloatInputResampleProducesUsbSignalStats() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    auto config = configFor(96000, 48000);
    config.input.encoding = 4;
    assert(pipeline.configure(config, &error));

    constexpr int inputFrames = 960;
    constexpr int inputChannels = 2;
    constexpr int inputSampleBytes = 4;
    std::vector<uint8_t> input(
        static_cast<size_t>(inputFrames * inputChannels * inputSampleBytes),
        0
    );
    for (int frame = 0; frame < inputFrames; ++frame) {
        const float value = (frame % 2 == 0) ? 0.25f : -0.25f;
        const size_t frameOffset = static_cast<size_t>(frame * inputChannels * inputSampleBytes);
        writeFloatSample(input, frameOffset, value);
        writeFloatSample(input, frameOffset + inputSampleBytes, value);
    }

    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    std::vector<uint8_t> output(480U * 4U, 0);
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());

    const auto snapshot = pipeline.snapshot();
    assert(snapshot.signalOutputFrames > 0);
    assert(snapshot.signalOutputBytes > 0);
    assert(snapshot.outputPeak > 0.0f);
    assert(snapshot.lastOutputPeak > 0.0f);

    bool hasNonZeroOutput = false;
    for (const uint8_t byte : output) {
        if (byte != 0U) {
            hasNonZeroOutput = true;
            break;
        }
    }
    assert(hasNonZeroOutput);
}

void verifiesFloatInputPassThroughProduces32BitUsbSignal() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor32Bit(96000, 96000), &error));

    constexpr int inputFrames = 192;
    constexpr int inputChannels = 2;
    constexpr int inputSampleBytes = 4;
    std::vector<uint8_t> input(
        static_cast<size_t>(inputFrames * inputChannels * inputSampleBytes),
        0
    );
    for (int frame = 0; frame < inputFrames; ++frame) {
        const float value = frame % 2 == 0 ? 0.5f : -0.5f;
        const size_t frameOffset = static_cast<size_t>(frame * inputChannels * inputSampleBytes);
        writeFloatSample(input, frameOffset, value);
        writeFloatSample(input, frameOffset + inputSampleBytes, value);
    }

    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    std::vector<uint8_t> output(static_cast<size_t>(inputFrames) * 8U, 0);
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());

    const auto snapshot = pipeline.snapshot();
    assert(snapshot.signalOutputFrames == inputFrames);
    assert(snapshot.signalOutputBytes == output.size());
    assert(snapshot.outputPeak >= 0.5f);
    assert(snapshot.lastOutputPeak >= 0.5f);

    const float firstLeft = neri::usb::readIntegerPcmSample(output.data(), 4, 32);
    const float secondLeft = neri::usb::readIntegerPcmSample(output.data() + 8, 4, 32);
    assert(firstLeft > 0.49f && firstLeft <= 0.5f);
    assert(secondLeft < -0.49f && secondLeft >= -0.5f);
}

void verifies32BitInputCanDrive24BitUsb32Container() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor24BitIn32Container(96000, 96000), &error));

    constexpr int inputFrames = 192;
    constexpr int inputChannels = 2;
    constexpr int inputSampleBytes = 4;
    std::vector<uint8_t> input(
        static_cast<size_t>(inputFrames * inputChannels * inputSampleBytes),
        0
    );
    for (int frame = 0; frame < inputFrames; ++frame) {
        const int32_t value = frame % 2 == 0 ? INT32_C(0x40000000) : -INT32_C(0x40000000);
        const size_t frameOffset = static_cast<size_t>(frame * inputChannels * inputSampleBytes);
        writeInt32Sample(input, frameOffset, value);
        writeInt32Sample(input, frameOffset + inputSampleBytes, value);
    }

    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    std::vector<uint8_t> output(static_cast<size_t>(inputFrames) * 8U, 0);
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());

    const auto snapshot = pipeline.snapshot();
    assert(snapshot.signalOutputFrames == inputFrames);
    assert(snapshot.signalOutputBytes == output.size());
    assert(snapshot.outputPeak >= 0.5f);
    assert(snapshot.lastOutputPeak >= 0.5f);

    const float firstLeft = neri::usb::readIntegerPcmSample(output.data(), 4, 24);
    const float secondLeft = neri::usb::readIntegerPcmSample(output.data() + 8, 4, 24);
    assert(firstLeft > 0.49f && firstLeft <= 0.5f);
    assert(secondLeft < -0.49f && secondLeft >= -0.5f);
}

void verifiesIntegerCodecDepthsAndEndianInputs() {
    std::array<uint8_t, 4> output {};
    neri::usb::writeIntegerPcmSample(output.data(), 3, 24, -1.0f);
    assert(output[0] == 0x00);
    assert(output[1] == 0x00);
    assert(output[2] == 0x80);
    assert(neri::usb::readIntegerPcmSample(output.data(), 3, 24) == -1.0f);

    std::array<uint8_t, 4> padded24Output {};
    neri::usb::writeIntegerPcmSample(padded24Output.data(), 4, 24, -1.0f);
    assert(padded24Output[0] == 0x00);
    assert(padded24Output[1] == 0x00);
    assert(padded24Output[2] == 0x00);
    assert(padded24Output[3] == 0x80);
    assert(neri::usb::readIntegerPcmSample(padded24Output.data(), 4, 24) == -1.0f);

    constexpr std::array<uint8_t, 2> littleEndianHalf { 0x00, 0x40 };
    constexpr std::array<uint8_t, 2> bigEndianHalf { 0x40, 0x00 };
    assert(neri::usb::readEncodedPcmSample(littleEndianHalf.data(), 2) == 0.5f);
    assert(neri::usb::readEncodedPcmSample(
        bigEndianHalf.data(),
        0x10000000
    ) == 0.5f);

    std::array<uint8_t, 4> floatInfinity {};
    const float infinity = std::numeric_limits<float>::infinity();
    std::memcpy(floatInfinity.data(), &infinity, sizeof(infinity));
    assert(neri::usb::readEncodedPcmSample(floatInfinity.data(), 4) == 0.0f);

    std::array<uint8_t, 2> finiteGuardOutput {};
    neri::usb::writeIntegerPcmSample(
        finiteGuardOutput.data(),
        2,
        16,
        std::numeric_limits<float>::infinity()
    );
    assert((finiteGuardOutput == std::array<uint8_t, 2> {}));
}

} // namespace

int main() {
    verifiesExactRatePassThroughAcrossWrites();
    verifiesStreamingResampleKeepsLongTermFrameCount();
    verifiesPausePreservesQueuedAudio();
    verifiesResumeAfterSilentOutputRampsFromZero();
    verifiesTransportStartRampRearmsWithoutDroppingQueuedAudio();
    verifiesUnsupportedEncodingIsRejected();
    verifiesRuntimeRingResizePreservesQueuedAudio();
    verifiesHighResolutionRingUsesBoundedAllocation();
    verifiesBackpressureSnapshotTracksFullRing();
    verifiesFloatInputResampleProducesUsbSignalStats();
    verifiesFloatInputPassThroughProduces32BitUsbSignal();
    verifies32BitInputCanDrive24BitUsb32Container();
    verifiesIntegerCodecDepthsAndEndianInputs();
    return 0;
}
