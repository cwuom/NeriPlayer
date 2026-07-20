#include "usb/feedback/usb_feedback_in_transfer_set.h"

#include <algorithm>
#include <chrono>
#include <exception>
#include <limits>
#include <utility>

namespace neri::usb::feedback {
namespace {

constexpr uint32_t kMaximumTransferCount = 64;
constexpr uint32_t kMaximumPacketCapacityBytes = 3072;

int64_t steadyClockNanoseconds() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

FeedbackInCompletionStatus mapTransferStatus(libusb_transfer_status status) {
    switch (status) {
        case LIBUSB_TRANSFER_COMPLETED:
            return FeedbackInCompletionStatus::Completed;
        case LIBUSB_TRANSFER_CANCELLED:
            return FeedbackInCompletionStatus::Cancelled;
        case LIBUSB_TRANSFER_NO_DEVICE:
            return FeedbackInCompletionStatus::NoDevice;
        case LIBUSB_TRANSFER_ERROR:
        case LIBUSB_TRANSFER_TIMED_OUT:
        case LIBUSB_TRANSFER_STALL:
        case LIBUSB_TRANSFER_OVERFLOW:
            return FeedbackInCompletionStatus::TransferError;
    }
    return FeedbackInCompletionStatus::TransferError;
}

bool configValid(const FeedbackInTransferConfig& config) {
    return config.deviceHandle != nullptr &&
        (config.endpointAddress & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN &&
        (config.endpointAddress & 0x0FU) != 0 &&
        config.packetCapacityBytes > 0 &&
        config.packetCapacityBytes <= kMaximumPacketCapacityBytes &&
        config.transferCount > 0 &&
        config.transferCount <= kMaximumTransferCount &&
        config.generation > 0;
}

} // namespace

FeedbackInTransferSet::FeedbackInTransferSet(FeedbackTransferBackend* backend) :
    backend_(backend) {
}

FeedbackInTransferSet::~FeedbackInTransferSet() {
    std::lock_guard<std::mutex> guard(mutex_);
    if (inFlight_ == 0) {
        releaseSlotsLocked();
    }
}

bool FeedbackInTransferSet::allocate(
    const FeedbackInTransferConfig& config,
    FeedbackInCompletionConsumer* consumer,
    std::string* error
) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (backend_ == nullptr || consumer == nullptr || !configValid(config)) {
        assignError(error, "feedback_transfer_config_invalid");
        return false;
    }
    if (state_ != FeedbackInTransferSetState::Empty || !slots_.empty() || inFlight_ != 0) {
        assignError(error, "feedback_transfer_set_not_empty");
        return false;
    }

    config_ = config;
    consumer_ = consumer;
    state_ = FeedbackInTransferSetState::Empty;
    inFlight_ = 0;
    callbacksInProgress_ = 0;
    submissions_ = 0;
    completions_ = 0;
    transferErrors_ = 0;
    packetErrors_ = 0;
    invalidLengths_ = 0;
    staleCallbacks_ = 0;
    cancelNotFound_ = 0;
    cancelErrors_ = 0;
    nextSequence_ = 1;
    slots_.reserve(config.transferCount);
    try {
        for (uint32_t index = 0; index < config.transferCount; ++index) {
            Slot slot;
            slot.transfer = backend_->allocateTransfer(1);
            if (slot.transfer == nullptr) {
                assignError(error, "feedback_transfer_allocate_failed");
                releaseSlotsLocked();
                return false;
            }
            slot.buffer.assign(config.packetCapacityBytes, 0);
            slot.userData = SlotUserData { this, config.generation, index };
            slots_.push_back(std::move(slot));
            Slot& stored = slots_.back();
            libusb_fill_iso_transfer(
                stored.transfer,
                config.deviceHandle,
                config.endpointAddress,
                stored.buffer.data(),
                static_cast<int>(stored.buffer.size()),
                1,
                transferCallback,
                &stored.userData,
                0
            );
            stored.transfer->iso_packet_desc[0].length = config.packetCapacityBytes;
        }
    } catch (const std::exception&) {
        assignError(error, "feedback_transfer_allocation_exception");
        releaseSlotsLocked();
        return false;
    } catch (...) {
        assignError(error, "feedback_transfer_allocation_unknown_exception");
        releaseSlotsLocked();
        return false;
    }
    state_ = FeedbackInTransferSetState::Allocated;
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

bool FeedbackInTransferSet::submitSlotLocked(Slot* slot, std::string* error) {
    if (slot == nullptr || slot->transfer == nullptr || slot->inFlight || backend_ == nullptr) {
        assignError(error, "feedback_transfer_submit_state_invalid");
        return false;
    }
    slot->transfer->length = static_cast<int>(slot->buffer.size());
    slot->transfer->actual_length = 0;
    slot->transfer->iso_packet_desc[0].length =
        static_cast<unsigned int>(slot->buffer.size());
    slot->transfer->iso_packet_desc[0].actual_length = 0;
    const int rc = backend_->submitTransfer(slot->transfer);
    if (rc != LIBUSB_SUCCESS) {
        ++transferErrors_;
        state_ = FeedbackInTransferSetState::Failed;
        assignError(
            error,
            rc == LIBUSB_ERROR_NO_DEVICE
                ? "feedback_transfer_submit_no_device"
                : "feedback_transfer_submit_failed"
        );
        return false;
    }
    slot->inFlight = true;
    ++inFlight_;
    ++submissions_;
    return true;
}

bool FeedbackInTransferSet::submitAll(std::string* error) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (state_ != FeedbackInTransferSetState::Allocated) {
        assignError(error, "feedback_transfer_submit_requires_allocated");
        return false;
    }
    state_ = FeedbackInTransferSetState::Running;
    for (Slot& slot : slots_) {
        if (!submitSlotLocked(&slot, error)) {
            return false;
        }
    }
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

bool FeedbackInTransferSet::beginStop(std::string* error) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (state_ == FeedbackInTransferSetState::Empty ||
        state_ == FeedbackInTransferSetState::Drained) {
        if (error != nullptr) {
            error->clear();
        }
        return true;
    }
    state_ = FeedbackInTransferSetState::Stopping;
    bool success = true;
    for (Slot& slot : slots_) {
        if (!slot.inFlight || slot.transfer == nullptr) {
            continue;
        }
        const int rc = backend_->cancelTransfer(slot.transfer);
        if (rc == LIBUSB_ERROR_NOT_FOUND) {
            slot.inFlight = false;
            if (inFlight_ > 0) {
                --inFlight_;
            }
            ++cancelNotFound_;
        } else if (rc != LIBUSB_SUCCESS) {
            ++cancelErrors_;
            success = false;
        }
    }
    if (inFlight_ == 0) {
        state_ = FeedbackInTransferSetState::Drained;
    }
    if (success) {
        if (error != nullptr) {
            error->clear();
        }
    } else {
        assignError(error, "feedback_transfer_cancel_failed");
    }
    return success;
}

bool FeedbackInTransferSet::freeDrained(std::string* error) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (inFlight_ != 0 || callbacksInProgress_ != 0 ||
        (state_ != FeedbackInTransferSetState::Allocated &&
         state_ != FeedbackInTransferSetState::Drained &&
         state_ != FeedbackInTransferSetState::Failed)) {
        assignError(error, "feedback_transfer_free_requires_drained");
        return false;
    }
    releaseSlotsLocked();
    if (error != nullptr) {
        error->clear();
    }
    return true;
}

void LIBUSB_CALL FeedbackInTransferSet::transferCallback(
    libusb_transfer* transfer
) noexcept {
    if (transfer == nullptr) {
        return;
    }
    auto* userData = static_cast<SlotUserData*>(transfer->user_data);
    if (userData != nullptr && userData->owner != nullptr) {
        userData->owner->onTransferCompletion(userData, transfer);
    }
}

void FeedbackInTransferSet::onTransferCompletion(
    SlotUserData* userData,
    libusb_transfer* transfer
) noexcept {
    bool callbackRegistered = false;
    try {
        FeedbackInCompletion completion;
        FeedbackInCompletionConsumer* consumer = nullptr;
        bool mayResubmit = false;
        {
            std::lock_guard<std::mutex> guard(mutex_);
            if (userData == nullptr || transfer == nullptr ||
                userData->slot >= slots_.size()) {
                ++transferErrors_;
                state_ = FeedbackInTransferSetState::Failed;
                return;
            }
            Slot& slot = slots_[userData->slot];
            if (slot.transfer != transfer || !slot.inFlight) {
                ++transferErrors_;
                state_ = FeedbackInTransferSetState::Failed;
                return;
            }
            slot.inFlight = false;
            ++completions_;

            completion.generation = userData->generation;
            completion.slot = userData->slot;
            completion.receivedAtNs = steadyClockNanoseconds();
            completion.sequence = nextSequence_++;
            completion.status = mapTransferStatus(transfer->status);
            if (userData->generation != config_.generation) {
                completion.status = FeedbackInCompletionStatus::StaleGeneration;
                ++staleCallbacks_;
            } else if (completion.status == FeedbackInCompletionStatus::Completed) {
                const libusb_iso_packet_descriptor& packet = transfer->iso_packet_desc[0];
                if (packet.status == LIBUSB_TRANSFER_NO_DEVICE) {
                    completion.status = FeedbackInCompletionStatus::NoDevice;
                } else if (packet.status != LIBUSB_TRANSFER_COMPLETED) {
                    completion.status = FeedbackInCompletionStatus::PacketError;
                    ++packetErrors_;
                } else if (packet.actual_length > packet.length ||
                    packet.actual_length > slot.buffer.size()) {
                    completion.status = FeedbackInCompletionStatus::InvalidLength;
                    ++invalidLengths_;
                } else {
                    completion.payload = slot.buffer.data();
                    completion.payloadBytes = packet.actual_length;
                }
            }
            if (completion.status == FeedbackInCompletionStatus::TransferError) {
                ++transferErrors_;
            }
            consumer = consumer_;
            mayResubmit = state_ == FeedbackInTransferSetState::Running &&
                completion.status != FeedbackInCompletionStatus::StaleGeneration;
            ++callbacksInProgress_;
            callbackRegistered = true;
        }

        bool consumerAllowsResubmit = false;
        if (consumer != nullptr) {
            consumerAllowsResubmit = consumer->onFeedbackInCompletion(completion);
        }
        std::lock_guard<std::mutex> guard(mutex_);
        if (inFlight_ > 0) {
            --inFlight_;
        }
        if (callbacksInProgress_ > 0) {
            --callbacksInProgress_;
        }
        callbackRegistered = false;
        if (mayResubmit && consumerAllowsResubmit &&
            state_ == FeedbackInTransferSetState::Running &&
            userData->generation == config_.generation &&
            userData->slot < slots_.size()) {
            std::string ignoredError;
            submitSlotLocked(&slots_[userData->slot], &ignoredError);
        }
        if (state_ == FeedbackInTransferSetState::Stopping && inFlight_ == 0 &&
            callbacksInProgress_ == 0) {
            state_ = FeedbackInTransferSetState::Drained;
        }
    } catch (...) {
        try {
            std::lock_guard<std::mutex> guard(mutex_);
            if (callbackRegistered) {
                if (inFlight_ > 0) {
                    --inFlight_;
                }
                if (callbacksInProgress_ > 0) {
                    --callbacksInProgress_;
                }
            }
            ++transferErrors_;
            state_ = FeedbackInTransferSetState::Failed;
        } catch (...) {
        }
    }
}

FeedbackInTransferSetSnapshot FeedbackInTransferSet::snapshot() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return FeedbackInTransferSetSnapshot {
        state_,
        config_.generation,
        static_cast<uint32_t>(slots_.size()),
        inFlight_,
        submissions_,
        completions_,
        transferErrors_,
        packetErrors_,
        invalidLengths_,
        staleCallbacks_,
        cancelNotFound_,
        cancelErrors_,
        callbacksInProgress_
    };
}

void FeedbackInTransferSet::releaseSlotsLocked() {
    if (backend_ != nullptr) {
        for (Slot& slot : slots_) {
            if (slot.transfer != nullptr) {
                backend_->freeTransfer(slot.transfer);
                slot.transfer = nullptr;
            }
        }
    }
    slots_.clear();
    consumer_ = nullptr;
    config_ = {};
    inFlight_ = 0;
    callbacksInProgress_ = 0;
    state_ = FeedbackInTransferSetState::Empty;
}

void FeedbackInTransferSet::assignError(
    std::string* error,
    const char* value
) const {
    if (error != nullptr) {
        *error = value;
    }
}

const char* feedbackInTransferSetStateName(FeedbackInTransferSetState state) {
    switch (state) {
        case FeedbackInTransferSetState::Empty: return "empty";
        case FeedbackInTransferSetState::Allocated: return "allocated";
        case FeedbackInTransferSetState::Running: return "running";
        case FeedbackInTransferSetState::Stopping: return "stopping";
        case FeedbackInTransferSetState::Drained: return "drained";
        case FeedbackInTransferSetState::Failed: return "failed";
    }
    return "unknown";
}

const char* feedbackInCompletionStatusName(FeedbackInCompletionStatus status) {
    switch (status) {
        case FeedbackInCompletionStatus::Completed: return "completed";
        case FeedbackInCompletionStatus::Cancelled: return "cancelled";
        case FeedbackInCompletionStatus::NoDevice: return "no_device";
        case FeedbackInCompletionStatus::TransferError: return "transfer_error";
        case FeedbackInCompletionStatus::PacketError: return "packet_error";
        case FeedbackInCompletionStatus::InvalidLength: return "invalid_length";
        case FeedbackInCompletionStatus::StaleGeneration: return "stale_generation";
    }
    return "unknown";
}

} // namespace neri::usb::feedback
