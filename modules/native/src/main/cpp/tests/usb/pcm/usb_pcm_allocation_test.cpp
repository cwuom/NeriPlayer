#include "usb/pcm/usb_pcm_pipeline.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <string>
#include <vector>

namespace {

size_t allocationCount = 0;
size_t failureCountdown = std::numeric_limits<size_t>::max();
bool countAllocations = false;
bool allocationFailed = false;

void* allocate(size_t bytes) {
    if (countAllocations) {
        ++allocationCount;
    }
    if (failureCountdown != std::numeric_limits<size_t>::max()) {
        if (failureCountdown == 0) {
            failureCountdown = std::numeric_limits<size_t>::max();
            allocationFailed = true;
            throw std::bad_alloc();
        }
        --failureCountdown;
    }
    if (void* pointer = std::malloc(bytes == 0 ? 1 : bytes)) {
        return pointer;
    }
    throw std::bad_alloc();
}

neri::usb::PcmPipelineConfig configFor(int inputRate = 44100, int outputRate = 48000) {
    return { { outputRate, 2, 2, 16, 4 }, { inputRate, 2, 2 }, 250, 768, 6 };
}

std::array<uint8_t, 1764> inputChunk() {
    std::array<uint8_t, 1764> input {};
    for (size_t frame = 0; frame < input.size() / 4U; ++frame) {
        const auto left = static_cast<int16_t>(static_cast<int>(frame % 97U) * 257 - 12000);
        const auto right = static_cast<int16_t>(9000 - static_cast<int>(frame % 71U) * 251);
        std::memcpy(input.data() + frame * 4U, &left, sizeof(left));
        std::memcpy(input.data() + frame * 4U + 2U, &right, sizeof(right));
    }
    return input;
}

void preparePipeline(neri::usb::PcmPipeline& pipeline, std::string& error, bool warmUp) {
    error.reserve(128);
    assert(pipeline.configure(configFor(), &error));
    pipeline.setBitPerfect(true);
    if (warmUp) {
        const auto input = inputChunk();
        assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    }
}

bool performOperation(neri::usb::PcmPipeline& pipeline, bool drain, std::string& error) {
    if (drain) {
        return pipeline.drainResampler(&error);
    }
    const auto input = inputChunk();
    return pipeline.write(input.data(), input.size(), &error) == input.size();
}

std::vector<uint8_t> finishOutput(neri::usb::PcmPipeline& pipeline, std::string& error) {
    const auto input = inputChunk();
    assert(pipeline.write(input.data(), input.size(), &error) == input.size());
    assert(pipeline.drainResampler(&error));
    std::vector<uint8_t> output(pipeline.queuedFrames() * 4U);
    assert(pipeline.fill(output.data(), output.size(), true) == output.size());
    return output;
}

size_t operationAllocations(bool drain, bool warmUp) {
    neri::usb::PcmPipeline pipeline;
    std::string error;
    preparePipeline(pipeline, error, warmUp);
    allocationCount = 0;
    countAllocations = true;
    assert(performOperation(pipeline, drain, error));
    countAllocations = false;
    return allocationCount;
}

void verifiesFailurePreservesRetryOutput(bool drain, bool warmUp, size_t failureIndex) {
    neri::usb::PcmPipeline baseline;
    std::string error;
    preparePipeline(baseline, error, warmUp);
    assert(performOperation(baseline, drain, error));
    const auto expected = finishOutput(baseline, error);

    neri::usb::PcmPipeline pipeline;
    preparePipeline(pipeline, error, warmUp);
    const auto before = pipeline.snapshot();
    allocationFailed = false;
    failureCountdown = failureIndex;
    const bool succeeded = performOperation(pipeline, drain, error);
    failureCountdown = std::numeric_limits<size_t>::max();
    assert(allocationFailed);
    assert(!succeeded);
    assert(error == "pcm_conversion_allocation_failed");
    const auto after = pipeline.snapshot();
    assert(after.inputBytes == before.inputBytes);
    assert(after.levelBytes == before.levelBytes);

    assert(performOperation(pipeline, drain, error));
    assert(error.empty());
    assert(finishOutput(pipeline, error) == expected);
}

void verifiesSteadyWritesReuseAllocations() {
    for (const auto rates : { std::array<int, 2> { 44100, 48000 },
                             std::array<int, 2> { 192000, 48000 } }) {
        neri::usb::PcmPipeline pipeline;
        std::string error;
        assert(pipeline.configure(configFor(rates[0], rates[1]), &error));
        const auto input = inputChunk();
        std::array<uint8_t, 4096> output {};
        for (int index = 0; index < 10; ++index) {
            assert(pipeline.write(input.data(), input.size(), &error) == input.size());
            pipeline.fill(output.data(), output.size(), true);
        }
        allocationCount = 0;
        countAllocations = true;
        for (int index = 0; index < 1000; ++index) {
            assert(pipeline.write(input.data(), input.size(), &error) == input.size());
            pipeline.fill(output.data(), output.size(), true);
        }
        countAllocations = false;
        assert(allocationCount == 0);
    }
}

} // namespace

void* operator new(size_t bytes) { return allocate(bytes); }
void* operator new[](size_t bytes) { return allocate(bytes); }
void operator delete(void* pointer) noexcept { std::free(pointer); }
void operator delete[](void* pointer) noexcept { std::free(pointer); }
void operator delete(void* pointer, size_t) noexcept { std::free(pointer); }
void operator delete[](void* pointer, size_t) noexcept { std::free(pointer); }

int main(int argc, char** argv) {
    if (argc == 2 && std::string(argv[1]) == "steady") {
        verifiesSteadyWritesReuseAllocations();
        return 0;
    }
    if (argc == 3) {
        verifiesFailurePreservesRetryOutput(
            std::string(argv[1]) == "drain",
            true,
            static_cast<size_t>(std::strtoul(argv[2], nullptr, 10))
        );
        return 0;
    }
    for (const bool drain : { false, true }) {
        for (const bool warmUp : { false, true }) {
            const size_t allocations = operationAllocations(drain, warmUp);
            for (size_t index = 0; index < allocations; ++index) {
                verifiesFailurePreservesRetryOutput(drain, warmUp, index);
            }
        }
    }
    verifiesSteadyWritesReuseAllocations();
    return 0;
}
