# Native USB Host Gate Contract

This public contract contains no device-derived descriptors, identifiers, logs,
or feedback traces. It defines the reproducible local boundary for the
feature-off USB host models:

- assertions remain enabled in every profile
- compiler warnings fail the gate unless an exact environment-owned baseline
  entry is documented
- Release, ASan+UBSan, and TSan execute the same CTest inventory
- the runner verifies the source fingerprint is stable for the whole attempt
- dedicated Android Native CI executes all three host profiles on native changes
- Android ABI compilation remains a separate job from the host model tests
- real-device qualification remains blocked outside the private evidence tree
