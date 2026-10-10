#include "usb/pcm/usb_pcm_pipeline.h"
#include "usb/pcm/usb_pcm_resampler.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <chrono>
#include <cmath>
#include <utility>
#include <cstring>
#include <cstdint>
#include <limits>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr double kPi = 3.14159265358979323846;

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
    // sinc 滤波保留 34 帧前瞻，其余输入按 48000/44100 精确换算成输出帧
    assert(pipeline.queuedFrames() == 4763);
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

void verifiesPartialUnderrunFadesLastValidFramesToSilence() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(48000, 48000), &error));

    constexpr int16_t fullScale = std::numeric_limits<int16_t>::max();
    std::vector<uint8_t> input(4U * 4U, 0);
    for (size_t offset = 0; offset < input.size(); offset += sizeof(fullScale)) {
        std::memcpy(input.data() + offset, &fullScale, sizeof(fullScale));
    }
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());

    std::vector<uint8_t> output(8U * 4U, 0);
    assert(pipeline.fill(output.data(), output.size(), true) == input.size());

    const int16_t firstSample = readInt16Sample(output, 0);
    const int16_t fadedSample = readInt16Sample(output, 3U * 4U);
    const int16_t zeroFillSample = readInt16Sample(output, 4U * 4U);
    assert(firstSample > 32000);
    assert(fadedSample == 0);
    assert(zeroFillSample == 0);

    const auto snapshot = pipeline.snapshot();
    const auto missingBytes = static_cast<int64_t>(output.size() - input.size());
    assert(snapshot.underrunBytes == missingBytes);
    assert(snapshot.zeroFillBytes == missingBytes);
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

    constexpr int inputFrames = 9600;
    constexpr int inputChannels = 2;
    constexpr int inputSampleBytes = 4;
    std::vector<uint8_t> input(
        static_cast<size_t>(inputFrames * inputChannels * inputSampleBytes),
        0
    );
    for (int frame = 0; frame < inputFrames; ++frame) {
        const auto value = static_cast<float>(0.25 * std::sin(2.0 * kPi * 1000.0 * frame / 96000.0));
        const size_t frameOffset = static_cast<size_t>(frame * inputChannels * inputSampleBytes);
        writeFloatSample(input, frameOffset, value);
        writeFloatSample(input, frameOffset + inputSampleBytes, value);
    }

    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    std::vector<uint8_t> output(4000U * 4U, 0);
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());

    const auto snapshot = pipeline.snapshot();
    assert(snapshot.signalOutputFrames > 0);
    assert(snapshot.signalOutputBytes > 0);
    assert(snapshot.outputPeak > 0.24f && snapshot.outputPeak < 0.27f);
    assert(snapshot.lastOutputPeak > 0.24f);

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
    assert(snapshot.signalOutputBytes == static_cast<int64_t>(output.size()));
    assert(snapshot.outputPeak >= 0.5f);
    assert(snapshot.lastOutputPeak >= 0.5f);

    const float firstLeft = neri::usb::readIntegerPcmSample(output.data(), 4, 32);
    const float secondLeft = neri::usb::readIntegerPcmSample(output.data() + 8, 4, 32);
    assert(firstLeft > 0.49f && firstLeft <= 0.5f);
    assert(secondLeft < -0.49f && secondLeft >= -0.5f);
}

void verifies32BitIntegerPassThroughPreservesRawBytes() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    auto config = configFor32Bit(48000, 48000);
    config.input.encoding = 22;
    assert(pipeline.configure(config, &error));

    const std::array<uint8_t, 16> input {
        0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF,
        0xFF, 0xFF, 0xFF, 0x7F, 0x00, 0x00, 0x00, 0x80
    };
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());

    std::array<uint8_t, 16> output {};
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());
    assert(output == input);
}

template <size_t Bytes>
std::array<uint8_t, Bytes> bitPerfectPassThrough(
    int encoding,
    int subslotBytes,
    int bitsPerSample,
    const std::vector<uint8_t>& input
) {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    const neri::usb::PcmPipelineConfig config {
        { 48000, 2, subslotBytes, bitsPerSample, 2 * subslotBytes },
        { 48000, 2, encoding },
        250,
        1536,
        12
    };
    assert(pipeline.configure(config, &error));
    pipeline.setBitPerfect(true);
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    std::array<uint8_t, Bytes> output {};
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());
    return output;
}

void verifiesBigEndianIntegerInputStaysBitExact() {
    constexpr int kPcm16BitBigEndian = 0x10000000;
    constexpr int kPcm24BitBigEndian = 0x50000000;
    constexpr int kPcm32BitBigEndian = 0x60000000;

    // 32 位样本经 float 只剩 24 位有效位，0x12345679 会变成 0x12345680
    const std::vector<uint8_t> int32BigEndian {
        0x12, 0x34, 0x56, 0x79, 0x80, 0x00, 0x00, 0x01,
        0x7F, 0xFF, 0xFF, 0xFF, 0x80, 0x00, 0x00, 0x00
    };
    assert((bitPerfectPassThrough<16>(kPcm32BitBigEndian, 4, 32, int32BigEndian) ==
        std::array<uint8_t, 16> {
            0x79, 0x56, 0x34, 0x12, 0x01, 0x00, 0x00, 0x80,
            0xFF, 0xFF, 0xFF, 0x7F, 0x00, 0x00, 0x00, 0x80
        }));

    const std::vector<uint8_t> int24BigEndian { 0x12, 0x34, 0x56, 0x80, 0x00, 0x01 };
    assert((bitPerfectPassThrough<6>(kPcm24BitBigEndian, 3, 24, int24BigEndian) ==
        std::array<uint8_t, 6> { 0x56, 0x34, 0x12, 0x01, 0x00, 0x80 }));
    assert((bitPerfectPassThrough<8>(kPcm24BitBigEndian, 4, 24, int24BigEndian) ==
        std::array<uint8_t, 8> { 0x00, 0x56, 0x34, 0x12, 0x00, 0x01, 0x00, 0x80 }));
    assert((bitPerfectPassThrough<8>(kPcm24BitBigEndian, 4, 32, int24BigEndian) ==
        std::array<uint8_t, 8> { 0x00, 0x56, 0x34, 0x12, 0x00, 0x01, 0x00, 0x80 }));

    const std::vector<uint8_t> int16BigEndian { 0x12, 0x34, 0x80, 0x01 };
    assert((bitPerfectPassThrough<4>(kPcm16BitBigEndian, 2, 16, int16BigEndian) ==
        std::array<uint8_t, 4> { 0x34, 0x12, 0x01, 0x80 }));

    assert(neri::usb::readEncodedIntegerPcmSample(int32BigEndian.data(), kPcm32BitBigEndian) ==
        INT32_C(0x12345679));
    assert(neri::usb::readEncodedIntegerPcmSample(int24BigEndian.data() + 3, kPcm24BitBigEndian) ==
        -INT32_C(0x7FFFFF));
    assert(neri::usb::readEncodedIntegerPcmSample(int16BigEndian.data(), 2) == INT32_C(0x3412));
    assert(neri::usb::readEncodedIntegerPcmSample(int16BigEndian.data(), 4) == 0);
    std::array<uint8_t, 2> saturated {};
    neri::usb::writeIntegerPcmValue(saturated.data(), 2, 16, INT64_C(0x10000));
    assert((saturated == std::array<uint8_t, 2> { 0xFF, 0x7F }));
}

void verifiesStereoChannelPeaksPreserveChannelOrder() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    auto config = configFor(48000, 48000);
    config.input.encoding = 4;
    assert(pipeline.configure(config, &error));

    constexpr int inputFrames = 32;
    constexpr int inputChannels = 2;
    constexpr int inputSampleBytes = 4;
    std::vector<uint8_t> input(
        static_cast<size_t>(inputFrames * inputChannels * inputSampleBytes),
        0
    );
    for (int frame = 0; frame < inputFrames; ++frame) {
        const size_t frameOffset = static_cast<size_t>(
            frame * inputChannels * inputSampleBytes
        );
        writeFloatSample(input, frameOffset, 0.75f);
        writeFloatSample(input, frameOffset + inputSampleBytes, 0.25f);
    }

    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    std::vector<uint8_t> output(static_cast<size_t>(inputFrames) * 4U, 0);
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());

    const auto snapshot = pipeline.snapshot();
    assert(snapshot.channel0OutputPeak > 0.74f);
    assert(snapshot.channel0OutputPeak <= 0.75f);
    assert(snapshot.channel1OutputPeak > 0.24f);
    assert(snapshot.channel1OutputPeak <= 0.25f);
    assert(snapshot.lastChannel0OutputPeak == snapshot.channel0OutputPeak);
    assert(snapshot.lastChannel1OutputPeak == snapshot.channel1OutputPeak);
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
    assert(snapshot.signalOutputBytes == static_cast<int64_t>(output.size()));
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

    std::array<uint8_t, 3> positive24 { 0xFF, 0xFF, 0x7F };
    const float positive24Sample = neri::usb::readIntegerPcmSample(
        positive24.data(),
        3,
        24
    );
    std::array<uint8_t, 3> positive24RoundTrip {};
    neri::usb::writeIntegerPcmSample(
        positive24RoundTrip.data(),
        3,
        24,
        positive24Sample
    );
    assert(positive24RoundTrip == positive24);

    std::array<uint8_t, 2> positive16 { 0xFF, 0x7F };
    const float positive16Sample = neri::usb::readIntegerPcmSample(
        positive16.data(),
        2,
        16
    );
    std::array<uint8_t, 2> positive16RoundTrip {};
    neri::usb::writeIntegerPcmSample(
        positive16RoundTrip.data(),
        2,
        16,
        positive16Sample
    );
    assert(positive16RoundTrip == positive16);

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

std::vector<uint8_t> fullScale16BitFrames(size_t frames) {
    std::vector<uint8_t> input(frames * 4U, 0);
    for (size_t frame = 0; frame < frames; ++frame) {
        const int16_t left = static_cast<int16_t>(32767 - static_cast<int>(frame % 7U));
        const int16_t right = static_cast<int16_t>(-32768 + static_cast<int>(frame % 5U));
        std::memcpy(input.data() + frame * 4U, &left, sizeof(left));
        std::memcpy(input.data() + frame * 4U + 2U, &right, sizeof(right));
    }
    return input;
}

void verifiesBitPerfectResumeAndTransportStartKeepSamplesExact() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(48000, 48000), &error));
    pipeline.setBitPerfect(true);
    pipeline.setTargetGain(1.0f);

    const std::vector<uint8_t> input = fullScale16BitFrames(64U);
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());

    std::array<uint8_t, 4> pausedOutput { 1, 1, 1, 1 };
    assert(pipeline.fill(pausedOutput.data(), pausedOutput.size(), false) == 0);
    pipeline.armTransportStartRamp();

    std::vector<uint8_t> output(input.size(), 0);
    assert(pipeline.fill(output.data(), output.size(), true) == input.size());
    pipeline.applyTransportStartRamp(output.data(), output.size());
    assert(output == input);
    assert(pipeline.snapshot().appliedGain == 1.0f);
}

void verifiesBitPerfectMuteIsHardAndUnmuteIsExact() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(48000, 48000), &error));
    pipeline.setBitPerfect(true);
    const std::vector<uint8_t> input = fullScale16BitFrames(16U);
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());

    pipeline.setTargetGain(0.0f);
    std::vector<uint8_t> muted(input.size(), 1);
    assert(pipeline.fill(muted.data(), muted.size(), true) == input.size());
    assert(muted == std::vector<uint8_t>(input.size(), 0));

    pipeline.setTargetGain(1.0f);
    std::vector<uint8_t> unmuted(input.size(), 0);
    assert(pipeline.fill(unmuted.data(), unmuted.size(), true) == input.size());
    assert(unmuted == input);
}

void verifiesBitPerfectPartialUnderrunKeepsValidFrames() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(48000, 48000), &error));
    pipeline.setBitPerfect(true);
    const std::vector<uint8_t> input = fullScale16BitFrames(4U);
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());

    std::vector<uint8_t> output(input.size() * 2U, 0);
    assert(pipeline.fill(output.data(), output.size(), true) == input.size());
    assert(std::equal(input.begin(), input.end(), output.begin()));
    assert(std::all_of(output.begin() + static_cast<std::ptrdiff_t>(input.size()), output.end(),
        [](uint8_t byte) { return byte == 0U; }));
}

void verifiesBitPerfectLeavingRestoresSmoothGain() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(48000, 48000), &error));
    pipeline.setBitPerfect(true);
    const std::vector<uint8_t> input = fullScale16BitFrames(8U);
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    std::vector<uint8_t> output(input.size(), 0);
    assert(pipeline.fill(output.data(), output.size(), true) == input.size());

    pipeline.setBitPerfect(false);
    pipeline.setTargetGain(0.5f);
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    assert(pipeline.fill(output.data(), output.size(), true) == input.size());
    const auto snapshot = pipeline.snapshot();
    assert(snapshot.appliedGain < 1.0f);
    assert(snapshot.appliedGain > 0.5f);
}

void verifiesIntegerUpconversionPadsWithoutChangingBits() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    auto config = configFor24BitIn32Container(48000, 48000);
    config.input.encoding = 2;
    assert(pipeline.configure(config, &error));
    pipeline.setBitPerfect(true);
    const std::vector<uint8_t> input = fullScale16BitFrames(32U);
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());

    std::vector<uint8_t> output(32U * 8U, 0);
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());
    for (size_t sample = 0; sample < 64U; ++sample) {
        int16_t source = 0;
        int32_t padded = 0;
        std::memcpy(&source, input.data() + sample * 2U, sizeof(source));
        std::memcpy(&padded, output.data() + sample * 4U, sizeof(padded));
        assert(padded == static_cast<int32_t>(static_cast<uint32_t>(source) << 16U));
    }
}

void verifiesFloatFromIntegerSourceRoundTripsExactly() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    auto config = configFor(48000, 48000);
    config.input.encoding = 4;
    assert(pipeline.configure(config, &error));
    pipeline.setBitPerfect(true);
    const std::vector<uint8_t> source = fullScale16BitFrames(32U);
    std::vector<uint8_t> input(source.size() * 2U, 0);
    for (size_t sample = 0; sample < 64U; ++sample) {
        int16_t value = 0;
        std::memcpy(&value, source.data() + sample * 2U, sizeof(value));
        writeFloatSample(input, sample * 4U, static_cast<float>(value) / 32768.0f);
    }
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());

    std::vector<uint8_t> output(source.size(), 0);
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());
    assert(output == source);
}

std::vector<float> stereoTone(int rate, double frequency, double amplitude, int frames) {
    std::vector<float> samples(static_cast<size_t>(frames) * 2U);
    for (int frame = 0; frame < frames; ++frame) {
        const auto value = static_cast<float>(amplitude * std::sin(2.0 * kPi * frequency * frame / rate));
        samples[static_cast<size_t>(frame) * 2U] = value;
        samples[static_cast<size_t>(frame) * 2U + 1U] = value;
    }
    return samples;
}

double peakAfter(const std::vector<float>& samples, size_t skipFrames) {
    double peak = 0.0;
    for (size_t index = skipFrames * 2U; index < samples.size(); ++index) {
        peak = std::max(peak, static_cast<double>(std::abs(samples[index])));
    }
    return peak;
}

std::vector<float> resampleWhole(int inputRate, int outputRate, const std::vector<float>& input) {
    neri::usb::PcmResampler resampler;
    assert(resampler.configure(inputRate, outputRate, 2));
    std::vector<float> output;
    resampler.process(input.data(), static_cast<int>(input.size() / 2U), &output);
    return output;
}

void verifiesResamplerOutputDoesNotDependOnChunking() {
    const std::vector<float> input = stereoTone(44100, 997.0, 0.5, 44100);
    const std::vector<float> whole = resampleWhole(44100, 48000, input);

    neri::usb::PcmResampler resampler;
    assert(resampler.configure(44100, 48000, 2));
    std::vector<float> chunked;
    const int chunkSizes[] = { 1, 7, 64, 441, 1000, 3 };
    int offset = 0;
    int index = 0;
    const int totalFrames = static_cast<int>(input.size() / 2U);
    while (offset < totalFrames) {
        const int frames = std::min(chunkSizes[index++ % 6], totalFrames - offset);
        resampler.process(input.data() + static_cast<size_t>(offset) * 2U, frames, &chunked);
        offset += frames;
    }
    assert(chunked == whole);
    const int expectedFrames = static_cast<int>(
        ((44100 - resampler.halfTaps()) * 48000LL - 1) / 44100 + 1
    );
    assert(static_cast<int>(whole.size() / 2U) == expectedFrames);
}

void verifiesResamplerKeepsPassbandToneAccurate() {
    const std::vector<float> output = resampleWhole(44100, 48000, stereoTone(44100, 1000.0, 0.5, 44100));
    double maxError = 0.0;
    for (size_t frame = 200; frame < output.size() / 2U; ++frame) {
        const double expected = 0.5 * std::sin(2.0 * kPi * 1000.0 * static_cast<double>(frame) / 48000.0);
        maxError = std::max(maxError, std::abs(static_cast<double>(output[frame * 2U]) - expected));
    }
    assert(maxError < 1e-3);

    const std::vector<float> treble = resampleWhole(44100, 48000, stereoTone(44100, 18000.0, 0.5, 44100));
    const double treblePeak = peakAfter(treble, 200);
    assert(treblePeak > 0.5 * 0.944 && treblePeak < 0.5 * 1.06);
}

void verifiesResamplerRejectsContentAboveOutputNyquist() {
    const std::vector<float> folded = resampleWhole(192000, 48000, stereoTone(192000, 30000.0, 0.5, 96000));
    assert(peakAfter(folded, 400) < 0.5e-3);

    const std::vector<float> kept = resampleWhole(192000, 48000, stereoTone(192000, 1000.0, 0.5, 96000));
    assert(peakAfter(kept, 400) > 0.49);
}

void verifiesResamplerInputBoundNeverOverfillsOutput() {
    const std::vector<float> input = stereoTone(44100, 440.0, 0.5, 20000);
    for (const auto& rates : { std::pair<int, int>{ 44100, 48000 }, std::pair<int, int>{ 192000, 48000 } }) {
        neri::usb::PcmResampler resampler;
        assert(resampler.configure(rates.first, rates.second, 2));
        int offset = 0;
        for (const size_t maxOutput : { size_t { 0 }, size_t { 1 }, size_t { 37 }, size_t { 480 }, size_t { 5 } }) {
            const int accepted = resampler.inputFramesForOutput(4000, maxOutput);
            std::vector<float> output;
            const size_t produced = resampler.process(input.data() + static_cast<size_t>(offset) * 2U, accepted, &output);
            offset += accepted;
            assert(produced <= maxOutput);
            assert(maxOutput == 0 || produced + 2U >= maxOutput);
        }
    }
}

void verifiesResamplerDrainEmitsLookaheadTail() {
    const std::vector<float> input = stereoTone(44100, 997.0, 0.5, 44100);
    neri::usb::PcmResampler resampler;
    assert(resampler.configure(44100, 48000, 2));
    std::vector<float> output;
    const size_t streamed = resampler.process(input.data(), 44100, &output);
    const size_t tail = resampler.drain(&output);
    // 一秒 44.1 kHz 输入要完整换算成一秒 48 kHz 输出，前瞻窗口里的尾部不能丢
    assert(streamed + tail == 48000U);
    assert(peakAfter(output, streamed) > 0.4);

    std::vector<float> padded = input;
    padded.resize(input.size() + static_cast<size_t>(resampler.halfTaps()) * 2U, 0.0f);
    assert(output == resampleWhole(44100, 48000, padded));

    assert(resampler.drain(&output) == 0U);
    std::vector<float> restarted;
    resampler.process(input.data(), 44100, &restarted);
    assert(restarted == resampleWhole(44100, 48000, input));
}

void verifiesPipelineDrainQueuesResamplerTail() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    assert(pipeline.configure(configFor(44100, 48000), &error));
    std::vector<uint8_t> chunk(441U * 4U, 0);
    for (int chunkIndex = 0; chunkIndex < 10; ++chunkIndex) {
        assert(pipeline.write(chunk.data(), chunk.size(), &error) == chunk.size());
    }
    assert(pipeline.queuedFrames() == 4763);
    assert(pipeline.drainResampler(&error));
    // 4410 帧 44.1 kHz 输入正好是 4800 帧 48 kHz 输出
    assert(pipeline.queuedFrames() == 4800);
    assert(pipeline.drainResampler(&error));
    assert(pipeline.queuedFrames() == 4800);

    neri::usb::PcmPipeline passThrough;
    assert(passThrough.configure(configFor(48000, 48000), &error));
    assert(passThrough.write(chunk.data(), chunk.size(), &error) == chunk.size());
    assert(passThrough.drainResampler(&error));
    assert(passThrough.queuedFrames() == 441);
}

void verifiesPipelineDrainWaitsForRingSpace() {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    auto config = configFor(44100, 48000);
    config.ringDurationMs = 1;
    config.transferBytes = 4;
    config.transferCount = 1;
    assert(pipeline.configure(config, &error));
    const size_t capacityBytes = pipeline.snapshot().capacityBytes;
    std::vector<uint8_t> input(4410U * 4U, 0);
    assert(pipeline.write(input.data(), input.size(), &error) > 0U);
    const size_t queuedBeforeDrain = pipeline.queuedFrames();

    assert(!pipeline.drainResampler(&error));
    assert(pipeline.queuedFrames() == queuedBeforeDrain);

    std::vector<uint8_t> output(capacityBytes, 0);
    assert(pipeline.fill(output.data(), output.size(), true) == queuedBeforeDrain * 4U);
    assert(pipeline.drainResampler(&error));
    assert(pipeline.queuedFrames() > 0U);
}

void verifiesChannelLayoutsCarryStereoWithoutDuplicatingExtraChannels() {
    const std::array<uint8_t, 4> stereoFrame { 0x00, 0x10, 0x00, 0xF0 };
    std::string error;

    neri::usb::PcmPipeline quad;
    auto quadConfig = configFor(48000, 48000);
    quadConfig.output = { 48000, 4, 2, 16, 8 };
    assert(quad.configure(quadConfig, &error));
    assert(quad.write(stereoFrame.data(), stereoFrame.size(), &error) == stereoFrame.size());
    std::array<uint8_t, 8> quadOutput {};
    assert(quad.fill(quadOutput.data(), quadOutput.size(), true) == quadOutput.size());
    assert(readInt16Sample({ quadOutput.begin(), quadOutput.end() }, 0) == 0x1000);
    assert(readInt16Sample({ quadOutput.begin(), quadOutput.end() }, 2) == static_cast<int16_t>(0xF000));
    assert(readInt16Sample({ quadOutput.begin(), quadOutput.end() }, 4) == 0);
    assert(readInt16Sample({ quadOutput.begin(), quadOutput.end() }, 6) == 0);

    neri::usb::PcmPipeline mono;
    auto monoConfig = configFor(48000, 48000);
    monoConfig.output = { 48000, 1, 2, 16, 2 };
    assert(mono.configure(monoConfig, &error));
    const std::array<uint8_t, 4> balancedFrame { 0x00, 0x20, 0x00, 0x10 };
    assert(mono.write(balancedFrame.data(), balancedFrame.size(), &error) == balancedFrame.size());
    std::array<uint8_t, 2> monoOutput {};
    assert(mono.fill(monoOutput.data(), monoOutput.size(), true) == monoOutput.size());
    assert(readInt16Sample({ monoOutput.begin(), monoOutput.end() }, 0) == 0x1800);

    neri::usb::PcmPipeline widened;
    auto widenedConfig = configFor(48000, 48000);
    widenedConfig.input.channelCount = 1;
    widenedConfig.output = { 48000, 4, 2, 16, 8 };
    assert(widened.configure(widenedConfig, &error));
    const std::array<uint8_t, 2> monoFrame { 0x00, 0x08 };
    assert(widened.write(monoFrame.data(), monoFrame.size(), &error) == monoFrame.size());
    std::array<uint8_t, 8> widenedOutput {};
    assert(widened.fill(widenedOutput.data(), widenedOutput.size(), true) == widenedOutput.size());
    assert(readInt16Sample({ widenedOutput.begin(), widenedOutput.end() }, 0) == 0x0800);
    assert(readInt16Sample({ widenedOutput.begin(), widenedOutput.end() }, 2) == 0x0800);
    assert(readInt16Sample({ widenedOutput.begin(), widenedOutput.end() }, 4) == 0);
}

} // namespace

int main() {
    verifiesChannelLayoutsCarryStereoWithoutDuplicatingExtraChannels();
    verifiesResamplerOutputDoesNotDependOnChunking();
    verifiesResamplerKeepsPassbandToneAccurate();
    verifiesResamplerRejectsContentAboveOutputNyquist();
    verifiesResamplerInputBoundNeverOverfillsOutput();
    verifiesResamplerDrainEmitsLookaheadTail();
    verifiesPipelineDrainQueuesResamplerTail();
    verifiesPipelineDrainWaitsForRingSpace();
    verifiesBitPerfectResumeAndTransportStartKeepSamplesExact();
    verifiesBitPerfectMuteIsHardAndUnmuteIsExact();
    verifiesBitPerfectPartialUnderrunKeepsValidFrames();
    verifiesBitPerfectLeavingRestoresSmoothGain();
    verifiesIntegerUpconversionPadsWithoutChangingBits();
    verifiesFloatFromIntegerSourceRoundTripsExactly();
    verifiesExactRatePassThroughAcrossWrites();
    verifiesStreamingResampleKeepsLongTermFrameCount();
    verifiesPausePreservesQueuedAudio();
    verifiesResumeAfterSilentOutputRampsFromZero();
    verifiesTransportStartRampRearmsWithoutDroppingQueuedAudio();
    verifiesPartialUnderrunFadesLastValidFramesToSilence();
    verifiesUnsupportedEncodingIsRejected();
    verifiesRuntimeRingResizePreservesQueuedAudio();
    verifiesHighResolutionRingUsesBoundedAllocation();
    verifiesBackpressureSnapshotTracksFullRing();
    verifiesFloatInputResampleProducesUsbSignalStats();
    verifiesFloatInputPassThroughProduces32BitUsbSignal();
    verifies32BitIntegerPassThroughPreservesRawBytes();
    verifiesBigEndianIntegerInputStaysBitExact();
    verifiesStereoChannelPeaksPreserveChannelOrder();
    verifies32BitInputCanDrive24BitUsb32Container();
    verifiesIntegerCodecDepthsAndEndianInputs();
    return 0;
}
