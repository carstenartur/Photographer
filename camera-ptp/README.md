# camera-ptp

Clean-room PTP (ISO 15740) implementation with Canon EOS vendor extensions.
This module is standalone and can be used independently of the Focus Stack application.

## Features

- USB PTP transport via usb4java, with a Windows Portable Devices fallback
- Canon EOS vendor extensions (remote capture, live view, focus drive, property control)
- Event-driven architecture with background event polling
- `CameraBackend` adapter for integration with the Focus Stack app

## Dependencies

| Dependency | Purpose |
|---|---|
| `common-api` | Shared interfaces (`CameraBackend`, `CaptureResult`, etc.) |
| `usb4java-javax` | USB device access (Apache-2.0) |
| `slf4j-api` | Logging facade |

## Usage

### Open a Canon camera over USB

```java
import com.photographer.ptp.session.PtpCamera;

try (var camera = PtpCamera.openUsb()) {
    var capabilities = camera.getCapabilities();
    var photo = camera.capture();
    byte[] imageData = photo.data();
    System.out.printf("%s: %d bytes (%s)%n", photo.fileName(), imageData.length, capabilities);
}
```

### Mock transport for testing

```java
MockPtpTransport mock = new MockPtpTransport();
PtpCamera camera = PtpCamera.open(mock, PtpCamera.Config.zeroDelays());
```

## Build

```bash
# From repository root
mvn -B clean verify -pl common-api,camera-ptp -am -Djava.awt.headless=true
```

## Tests

- Unit tests (`*Test.java`): run with `mvn test`, no hardware required
- Integration test (`UsbCanonCameraIT.java`): requires a connected Canon camera, tagged `real-camera`, excluded by default

## Canonical documentation

Transport placement and dependency direction are maintained in [Architecture](../docs/developer/architecture.md). Test-layer and real-camera evidence rules are maintained in [Testing](../docs/developer/testing.md).
