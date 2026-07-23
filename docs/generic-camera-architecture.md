# Generic Camera Architecture

The generic camera contracts are implemented incrementally, not merely proposed. Their current cross-module status is maintained in:

- [Developer Manual — Camera architecture](developer/architecture.md#camera-architecture)
- [Developer Manual — Camera modules and adapters](developer/modules.md#camera-modules-and-adapters)

Current boundary:

- `common-api` contains `GenericCameraBackend`, connection, capability, device, media, policy and event contracts;
- `CcapiCameraBackend` adapts the legacy production backend for device information, capability mapping, live view, capture and battery/storage status;
- CCAPI generic media list/download/delete remain explicitly unimplemented;
- `UsbCameraBackend` remains a capability-safe skeleton and is not yet the production bridge to the mature `camera-ptp` stack;
- the compact legacy `CameraBackend` remains the existing workflow/plugin boundary.

The older detailed migration text mixed implemented contracts with future steps. This page is retained as a stable redirect and concise status boundary; implementation claims must follow current code and tests.
