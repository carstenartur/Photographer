# Camera setup

Start the backend and sign in using the [application quickstart](quickstart.md). A camera profile stores the transport and address for that camera. You can keep mock, network and USB profiles side by side.

## Hardware coverage

| Camera | What has been exercised with hardware |
|---|---|
| Canon PowerShot G5 X Mark II | CCAPI live view, remote capture and manual focus stepping, plus USB/PTP capture of CR3 files |
| Canon EOS R | USB/PTP capture of CR3 files at several ISO settings |

Camera control and RAW import have separate compatibility criteria. For files already on disk, see the [measured RAW profiles](cr3/decoder.md). For a connected CCAPI camera, the operations available to Photographer depend on the endpoints advertised in its current shooting mode. [Capability status](ccapi-capabilities.md)

## Connect over Wi-Fi with CCAPI

1. Enable Wi-Fi and Camera Control API on a CCAPI-capable camera. Menu names and activation requirements depend on model and firmware. Note the address and port shown by the camera.
2. Put the camera and the computer **running the backend** on a network where they can reach each other. A camera access point also works when the backend computer joins it. If you use Compose, the application container needs network access to this address.
3. From the backend host, request the CCAPI root. For example, replace the example address below with your camera's address.

   ```sh
   curl -i http://192.168.1.42:8080/ccapi
   ```

   A successful response describes the camera's API. A `401` response indicates that the server is reachable and requires credentials. The [authentication guide](ccapi-authentication.md) explains transport and credential configuration.
4. In Photographer choose **Cameras › Add Camera**. Enter a name, choose **Canon CCAPI** and enter the camera's address in **Host IP**. A plain host such as `192.168.1.42` selects HTTP on port 8080. For a different port or HTTPS, enter the base URL, for example `https://192.168.1.42:8443`. Choose **Create**. The camera card shows status and live view as they become available.

For networks that support multicast, the authenticated discovery endpoint can return camera addresses. Creating a profile is an explicit step. [Discovery API](ccapi-discovery.md)

## Capture a focus stack

Mount the camera on a tripod, use a fixed exposure and select a shooting mode that allows remote focus drive. On **Jobs**, create a job, select the camera and choose the focus step size and maximum number of frames. Start with a short run and check that sharpness moves between frames before starting a longer stack. For cameras that advertise an on-camera focus-bracketing sequence, see the [native bracketing guide](native-focus-bracketing.md).

## Connect over USB/PTP

Attach the camera to the computer running the backend, enable its USB/PTP connection and select **Canon USB (PTP)** in a new profile. The backend process needs access to the USB device. The default Compose file exposes network services and does not map a host USB device. A local application run is the simplest way to try USB. [PTP implementation and diagnostics](../camera-ptp/README.md)

## Troubleshooting

| Symptom | What to check |
|---|---|
| CCAPI request times out | Confirm the camera's displayed address, the backend host's route and the camera's Wi-Fi connection. |
| Camera responds, but an operation is unavailable | Check the advertised endpoint and method, shooting/playback mode and the camera's current state. [Capabilities](ccapi-capabilities.md) |
| Live view is busy during a job | Look for an active capture that owns the camera transport. [Session ownership](ccapi-session-ownership.md) |
