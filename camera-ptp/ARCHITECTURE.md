# camera-ptp Architecture

## Overview

The camera-ptp module implements the PTP (Picture Transfer Protocol, ISO 15740) standard
with Canon EOS vendor extensions. It is organized into five layers, each with a clear
responsibility and dependency direction (lower layers never depend on higher ones).

## Layer Diagram

```
┌─────────────────────────────────────────────────┐
│  Layer 5 — backend                              │
│  PtpCameraBackend / PtpCameraBackendFactory     │
│  (CameraBackend adapter for Focus Stack app)    │
├─────────────────────────────────────────────────┤
│  Layer 4 — session                              │
│  PtpCamera, PtpSession, EventLoop,              │
│  CameraCapabilities, ImageDownload              │
├─────────────────────────────────────────────────┤
│  Layer 3 — canon                                │
│  CanonConstants, CanonEventParser,              │
│  CanonFocusDrive, CanonLiveViewDecoder,         │
│  CanonPropertyDecoder, CanonRemoteCapture       │
├─────────────────────────────────────────────────┤
│  Layer 2 — usb                                  │
│  PtpTransport (interface), UsbPtpTransport,     │
│  UsbDeviceScanner, UsbConstants                 │
├─────────────────────────────────────────────────┤
│  Layer 1 — core                                 │
│  PtpConstants, PtpContainer, PtpDataSet,        │
│  PtpDeviceInfo, PtpDeviceProperty,              │
│  PtpObjectInfo, PtpStorageInfo,                 │
│  PtpOperationRequest, PtpOperationResponse,     │
│  PtpTypeReader, PtpException                    │
└─────────────────────────────────────────────────┘
```

## Layers

### Layer 1 — core (`com.photographer.ptp.core`)

Protocol-level types defined by ISO 15740. Handles container parsing/serialization,
type reading (little-endian PTP wire format), device info parsing, and standard
operation/response codes.

### Layer 2 — usb (`com.photographer.ptp.usb`)

Transport abstraction. `PtpTransport` is the interface consumed by higher layers;
`UsbPtpTransport` implements it over USB bulk endpoints via usb4java.
`UsbDeviceScanner` discovers PTP-class USB devices on the host.

### Layer 3 — canon (`com.photographer.ptp.canon`)

Canon EOS vendor extensions. Defines vendor operation codes, property codes, and
event types. Includes helpers for remote shutter release, focus drive,
live-view frame decoding, property decoding, and event stream parsing.

### Layer 4 — session (`com.photographer.ptp.session`)

High-level camera API. `PtpSession` manages transaction IDs and operation execution.
`PtpCamera` provides the main public API (open, capture, live view, focus, properties).
`EventLoop` runs a background thread that polls Canon GetEvent and dispatches to
registered `PtpEventListener` instances.

### Layer 5 — backend (`com.photographer.ptp.backend`)

Bridge to the Focus Stack application. `PtpCameraBackend` implements the
`CameraBackend` interface from `common-api`, mapping PTP operations to the
interface expected by the app's camera service. `PtpCameraBackendFactory` creates
backend instances for discovered USB cameras.

## Key Design Decisions

- **Transport abstraction**: `PtpTransport` is an interface so the entire stack
  can be tested with `MockPtpTransport` (no USB hardware needed).
- **Config record**: `PtpCamera.Config` separates timing parameters from logic,
  allowing zero-delay configs in tests.
- **Safe resource cleanup**: Static factory methods (`PtpCamera.open*`) ensure
  the transport is closed if initialization fails.
- **Canon-only guards**: Methods that use vendor-specific operations (focus drive,
  Canon property getters) throw `PtpException` for non-Canon cameras rather than
  sending invalid operation codes.
