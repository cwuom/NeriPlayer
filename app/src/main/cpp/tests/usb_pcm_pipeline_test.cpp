#include "usb_pcm_pipeline.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <string>
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

void verifiesIntegerCodecDepthsAndEndianInputs() {
    std::array<uint8_t, 4> output {};
    neri::usb::writeIntegerPcmSample(output.data(), 3, 24, -1.0f);
    assert(output[0] == 0x00);
    assert(output[1] == 0x00);
    assert(output[2] == 0x80);
    assert(neri::usb::readIntegerPcmSample(output.data(), 3, 24) == -1.0f);

    constexpr std::array<uint8_t, 2> littleEndianHalf { 0x00, 0x40 };
    constexpr std::array<uint8_t, 2> bigEndianHalf { 0x40, 0x00 };
    assert(neri::usb::readEncodedPcmSample(littleEndianHalf.data(), 2) == 0.5f);
    assert(neri::usb::readEncodedPcmSample(
        bigEndianHalf.data(),
        0x10000000
    ) == 0.5f);
}

} // namespace

int main() {
    verifiesExactRatePassThroughAcrossWrites();
    verifiesStreamingResampleKeepsLongTermFrameCount();
    verifiesPausePreservesQueuedAudio();
    verifiesUnsupportedEncodingIsRejected();
    verifiesRuntimeRingResizePreservesQueuedAudio();
    verifiesHighResolutionRingUsesBoundedAllocation();
    verifiesIntegerCodecDepthsAndEndianInputs();
    return 0;
}
