#pragma once

#include "usb/feedback/usb_feedback_in_transfer_set.h"

namespace neri::usb::feedback {

class LibusbFeedbackTransferBackend final : public FeedbackTransferBackend {
public:
    libusb_transfer* allocateTransfer(int isoPacketCount) override;
    int submitTransfer(libusb_transfer* transfer) override;
    int cancelTransfer(libusb_transfer* transfer) override;
    void freeTransfer(libusb_transfer* transfer) override;
};

} // namespace neri::usb::feedback
