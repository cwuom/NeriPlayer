#pragma once

#include "libusb/libusb.h"

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace neri::usb::feedback {

enum class FeedbackInTransferSetState {
    Empty,
    Allocated,
    Running,
    Stopping,
    Drained,
    Failed
};

enum class FeedbackInCompletionStatus {
    Completed,
    Cancelled,
    NoDevice,
    TransferError,
    PacketError,
    InvalidLength,
    StaleGeneration
};

struct FeedbackInTransferConfig {
    libusb_device_handle* deviceHandle = nullptr;
    uint8_t endpointAddress = 0;
    uint32_t packetCapacityBytes = 0;
    uint32_t transferCount = 0;
    uint64_t generation = 0;
};

struct FeedbackInCompletion {
    uint64_t generation = 0;
    uint32_t slot = 0;
    FeedbackInCompletionStatus status = FeedbackInCompletionStatus::TransferError;
    const uint8_t* payload = nullptr;
    size_t payloadBytes = 0;
    int64_t receivedAtNs = 0;
    uint64_t sequence = 0;
};

struct FeedbackInTransferSetSnapshot {
    FeedbackInTransferSetState state = FeedbackInTransferSetState::Empty;
    uint64_t generation = 0;
    uint32_t allocated = 0;
    uint32_t inFlight = 0;
    uint64_t submissions = 0;
    uint64_t completions = 0;
    uint64_t transferErrors = 0;
    uint64_t packetErrors = 0;
    uint64_t invalidLengths = 0;
    uint64_t staleCallbacks = 0;
    uint64_t cancelNotFound = 0;
    uint64_t cancelErrors = 0;
    uint32_t callbacksInProgress = 0;
};

class FeedbackInCompletionConsumer {
public:
    virtual ~FeedbackInCompletionConsumer() = default;

    // Return true only when this slot may be resubmitted for the same generation
    virtual bool onFeedbackInCompletion(const FeedbackInCompletion& completion) = 0;
};

class FeedbackTransferBackend {
public:
    virtual ~FeedbackTransferBackend() = default;

    virtual libusb_transfer* allocateTransfer(int isoPacketCount) = 0;
    virtual int submitTransfer(libusb_transfer* transfer) = 0;
    virtual int cancelTransfer(libusb_transfer* transfer) = 0;
    virtual void freeTransfer(libusb_transfer* transfer) = 0;
};

class FeedbackInTransferSet {
public:
    explicit FeedbackInTransferSet(FeedbackTransferBackend* backend);
    ~FeedbackInTransferSet();

    FeedbackInTransferSet(const FeedbackInTransferSet&) = delete;
    FeedbackInTransferSet& operator=(const FeedbackInTransferSet&) = delete;

    bool allocate(
        const FeedbackInTransferConfig& config,
        FeedbackInCompletionConsumer* consumer,
        std::string* error
    );

    bool submitAll(std::string* error);
    bool beginStop(std::string* error);
    bool freeDrained(std::string* error);

    [[nodiscard]] FeedbackInTransferSetSnapshot snapshot() const;

private:
    struct SlotUserData {
        FeedbackInTransferSet* owner = nullptr;
        uint64_t generation = 0;
        uint32_t slot = 0;
    };

    struct Slot {
        libusb_transfer* transfer = nullptr;
        std::vector<uint8_t> buffer;
        SlotUserData userData;
        bool inFlight = false;
    };

    static void LIBUSB_CALL transferCallback(libusb_transfer* transfer) noexcept;

    void onTransferCompletion(
        SlotUserData* userData,
        libusb_transfer* transfer
    ) noexcept;

    bool submitSlotLocked(Slot* slot, std::string* error);
    void releaseSlotsLocked();
    void assignError(std::string* error, const char* value) const;

    FeedbackTransferBackend* backend_ = nullptr;
    FeedbackInCompletionConsumer* consumer_ = nullptr;
    FeedbackInTransferConfig config_;
    mutable std::mutex mutex_;
    std::vector<Slot> slots_;
    FeedbackInTransferSetState state_ = FeedbackInTransferSetState::Empty;
    uint32_t inFlight_ = 0;
    uint32_t callbacksInProgress_ = 0;
    uint64_t submissions_ = 0;
    uint64_t completions_ = 0;
    uint64_t transferErrors_ = 0;
    uint64_t packetErrors_ = 0;
    uint64_t invalidLengths_ = 0;
    uint64_t staleCallbacks_ = 0;
    uint64_t cancelNotFound_ = 0;
    uint64_t cancelErrors_ = 0;
    uint64_t nextSequence_ = 1;
};

const char* feedbackInTransferSetStateName(FeedbackInTransferSetState state);
const char* feedbackInCompletionStatusName(FeedbackInCompletionStatus status);

} // namespace neri::usb::feedback
