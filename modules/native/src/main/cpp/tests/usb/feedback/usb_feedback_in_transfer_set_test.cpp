#include "usb/feedback/usb_feedback_in_transfer_set.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <new>
#include <string>
#include <utility>
#include <vector>

namespace {

using neri::usb::feedback::FeedbackInCompletion;
using neri::usb::feedback::FeedbackInCompletionConsumer;
using neri::usb::feedback::FeedbackInCompletionStatus;
using neri::usb::feedback::FeedbackInTransferConfig;
using neri::usb::feedback::FeedbackInTransferSet;
using neri::usb::feedback::FeedbackInTransferSetState;
using neri::usb::feedback::FeedbackTransferBackend;

struct RecordedCompletion {
    uint64_t generation = 0;
    uint32_t slot = 0;
    FeedbackInCompletionStatus status = FeedbackInCompletionStatus::TransferError;
    std::vector<uint8_t> payload;
    uint64_t sequence = 0;
};

class RecordingConsumer final : public FeedbackInCompletionConsumer {
public:
    bool onFeedbackInCompletion(const FeedbackInCompletion& completion) override {
        RecordedCompletion record;
        record.generation = completion.generation;
        record.slot = completion.slot;
        record.status = completion.status;
        record.sequence = completion.sequence;
        if (completion.payload != nullptr && completion.payloadBytes > 0) {
            record.payload.assign(
                completion.payload,
                completion.payload + completion.payloadBytes
            );
        }
        completions.push_back(std::move(record));
        return allowResubmit;
    }

    bool allowResubmit = true;
    std::vector<RecordedCompletion> completions;
};

class FakeBackend final : public FeedbackTransferBackend {
public:
    ~FakeBackend() override {
        for (libusb_transfer* transfer : allocated) {
            if (std::find(freed.begin(), freed.end(), transfer) == freed.end()) {
                ::operator delete(transfer);
            }
        }
    }

    libusb_transfer* allocateTransfer(int isoPacketCount) override {
        if (isoPacketCount <= 0 || failAllocationAt == allocateCalls) {
            ++allocateCalls;
            return nullptr;
        }
        ++allocateCalls;
        const size_t bytes = sizeof(libusb_transfer) +
            static_cast<size_t>(isoPacketCount) * sizeof(libusb_iso_packet_descriptor);
        auto* transfer = static_cast<libusb_transfer*>(::operator new(bytes));
        std::memset(transfer, 0, bytes);
        transfer->num_iso_packets = isoPacketCount;
        allocated.push_back(transfer);
        return transfer;
    }

    int submitTransfer(libusb_transfer* transfer) override {
        ++submitCalls;
        submitted.push_back(transfer);
        return failSubmitAt == submitCalls ? LIBUSB_ERROR_IO : LIBUSB_SUCCESS;
    }

    int cancelTransfer(libusb_transfer* transfer) override {
        cancelled.push_back(transfer);
        return cancelResult;
    }

    void freeTransfer(libusb_transfer* transfer) override {
        freed.push_back(transfer);
        ::operator delete(transfer);
    }

    void complete(
        size_t slot,
        libusb_transfer_status transferStatus,
        libusb_transfer_status packetStatus,
        const uint8_t* payload,
        size_t payloadBytes,
        unsigned int actualLength
    ) {
        assert(slot < allocated.size());
        libusb_transfer* transfer = allocated[slot];
        assert(transfer != nullptr);
        if (payload != nullptr && payloadBytes > 0) {
            std::memcpy(transfer->buffer, payload, payloadBytes);
        }
        transfer->status = transferStatus;
        transfer->iso_packet_desc[0].status = packetStatus;
        transfer->iso_packet_desc[0].actual_length = actualLength;
        transfer->callback(transfer);
    }

    int failAllocationAt = -1;
    int failSubmitAt = -1;
    int allocateCalls = 0;
    int submitCalls = 0;
    int cancelResult = LIBUSB_SUCCESS;
    std::vector<libusb_transfer*> allocated;
    std::vector<libusb_transfer*> submitted;
    std::vector<libusb_transfer*> cancelled;
    std::vector<libusb_transfer*> freed;
};

FeedbackInTransferConfig config(uint32_t transferCount = 2) {
    return FeedbackInTransferConfig {
        reinterpret_cast<libusb_device_handle*>(static_cast<uintptr_t>(1)),
        0x84,
        4,
        transferCount,
        9
    };
}

void submitsCompletesAndResubmitsIndependently() {
    FakeBackend backend;
    RecordingConsumer consumer;
    FeedbackInTransferSet transfers(&backend);
    std::string error;
    assert(transfers.allocate(config(), &consumer, &error));
    assert(error.empty());
    assert(transfers.submitAll(&error));
    auto state = transfers.snapshot();
    assert(state.state == FeedbackInTransferSetState::Running);
    assert(state.allocated == 2U);
    assert(state.inFlight == 2U);

    const std::array<uint8_t, 4> payload { 0x00, 0x00, 0x06, 0x00 };
    backend.complete(
        0,
        LIBUSB_TRANSFER_COMPLETED,
        LIBUSB_TRANSFER_COMPLETED,
        payload.data(),
        payload.size(),
        static_cast<unsigned int>(payload.size())
    );
    state = transfers.snapshot();
    assert(state.inFlight == 2U);
    assert(state.submissions == 3U);
    assert(state.completions == 1U);
    assert(consumer.completions.size() == 1U);
    assert(consumer.completions.front().generation == 9U);
    assert(consumer.completions.front().payload ==
        std::vector<uint8_t>(payload.begin(), payload.end()));

    assert(transfers.beginStop(&error));
    backend.complete(
        0,
        LIBUSB_TRANSFER_CANCELLED,
        LIBUSB_TRANSFER_CANCELLED,
        nullptr,
        0,
        0
    );
    backend.complete(
        1,
        LIBUSB_TRANSFER_CANCELLED,
        LIBUSB_TRANSFER_CANCELLED,
        nullptr,
        0,
        0
    );
    state = transfers.snapshot();
    assert(state.state == FeedbackInTransferSetState::Drained);
    assert(state.inFlight == 0U);
    assert(transfers.freeDrained(&error));
    assert(backend.freed.size() == 2U);
}

void rejectsInvalidLengthsWithoutResubmit() {
    FakeBackend backend;
    RecordingConsumer consumer;
    consumer.allowResubmit = false;
    FeedbackInTransferSet transfers(&backend);
    std::string error;
    assert(transfers.allocate(config(1), &consumer, &error));
    assert(transfers.submitAll(&error));
    backend.complete(
        0,
        LIBUSB_TRANSFER_COMPLETED,
        LIBUSB_TRANSFER_COMPLETED,
        nullptr,
        0,
        5
    );
    const auto state = transfers.snapshot();
    assert(state.invalidLengths == 1U);
    assert(state.inFlight == 0U);
    assert(consumer.completions.front().status ==
        FeedbackInCompletionStatus::InvalidLength);
    assert(transfers.beginStop(&error));
    assert(transfers.freeDrained(&error));
}

void cancelNotFoundSettlesWithoutCallback() {
    FakeBackend backend;
    backend.cancelResult = LIBUSB_ERROR_NOT_FOUND;
    RecordingConsumer consumer;
    FeedbackInTransferSet transfers(&backend);
    std::string error;
    assert(transfers.allocate(config(1), &consumer, &error));
    assert(transfers.submitAll(&error));
    assert(transfers.beginStop(&error));
    const auto state = transfers.snapshot();
    assert(state.state == FeedbackInTransferSetState::Drained);
    assert(state.inFlight == 0U);
    assert(state.cancelNotFound == 1U);
    assert(transfers.freeDrained(&error));
}

void partialSubmitFailureRemainsDrainable() {
    FakeBackend backend;
    backend.failSubmitAt = 2;
    RecordingConsumer consumer;
    FeedbackInTransferSet transfers(&backend);
    std::string error;
    assert(transfers.allocate(config(), &consumer, &error));
    assert(!transfers.submitAll(&error));
    assert(error == "feedback_transfer_submit_failed");
    assert(transfers.snapshot().inFlight == 1U);
    assert(transfers.beginStop(&error));
    backend.complete(
        0,
        LIBUSB_TRANSFER_CANCELLED,
        LIBUSB_TRANSFER_CANCELLED,
        nullptr,
        0,
        0
    );
    assert(transfers.snapshot().state == FeedbackInTransferSetState::Drained);
    assert(transfers.freeDrained(&error));
}

void rejectsInvalidConfigurationAndExposesNames() {
    FakeBackend backend;
    RecordingConsumer consumer;
    FeedbackInTransferSet transfers(&backend);
    std::string error;
    auto invalid = config();
    invalid.endpointAddress = 0x04;
    assert(!transfers.allocate(invalid, &consumer, &error));
    assert(error == "feedback_transfer_config_invalid");
    assert(std::string(neri::usb::feedback::feedbackInTransferSetStateName(
        FeedbackInTransferSetState::Stopping
    )) == "stopping");
    assert(std::string(neri::usb::feedback::feedbackInCompletionStatusName(
        FeedbackInCompletionStatus::PacketError
    )) == "packet_error");
}

} // namespace

int main() {
    submitsCompletesAndResubmitsIndependently();
    rejectsInvalidLengthsWithoutResubmit();
    cancelNotFoundSettlesWithoutCallback();
    partialSubmitFailureRemainsDrainable();
    rejectsInvalidConfigurationAndExposesNames();
    return 0;
}
