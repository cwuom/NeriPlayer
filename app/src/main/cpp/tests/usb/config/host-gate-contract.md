# Native USB Host Gate Contract

This public contract contains no device-derived descriptors, identifiers, logs,
or feedback traces. It defines the reproducible local boundary for the
feature-off USB host models:

- assertions remain enabled in every profile
- compiler warnings fail the gate unless an exact environment-owned baseline
  entry is documented
- Release, ASan+UBSan, and TSan execute the same CTest inventory
- the runner verifies the source fingerprint is stable for the whole attempt
- public Android CI remains unchanged and does not execute this local gate
- real-device qualification remains blocked outside the private evidence tree
