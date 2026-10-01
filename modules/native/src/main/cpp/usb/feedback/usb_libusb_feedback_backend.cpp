#include "usb/feedback/usb_libusb_feedback_backend.h"

namespace neri::usb::feedback {

libusb_transfer* LibusbFeedbackTransferBackend::allocateTransfer(int isoPacketCount) {
    return libusb_alloc_transfer(isoPacketCount);
}

int LibusbFeedbackTransferBackend::submitTransfer(libusb_transfer* transfer) {
    return libusb_submit_transfer(transfer);
}

int LibusbFeedbackTransferBackend::cancelTransfer(libusb_transfer* transfer) {
    return libusb_cancel_transfer(transfer);
}

void LibusbFeedbackTransferBackend::freeTransfer(libusb_transfer* transfer) {
    libusb_free_transfer(transfer);
}

} // namespace neri::usb::feedback
