# Camera Setup

This guide explains how to prepare a compatible Canon camera for use with Canon
Focus Stack over Wi-Fi using the Canon Camera Control API (CCAPI).

---

## Supported Cameras

| Camera | CCAPI Version | Status |
|---|---|---|
| Canon PowerShot G5 X Mark II | 1.1+ | ✅ Tested |
| Canon PowerShot G7 X Mark III | 1.1+ | ✅ Tested |

Other Canon cameras that support CCAPI may also work but have not been verified.

---

## Step 1 — Enable CCAPI on the Camera

Canon CCAPI is **not** enabled by default. You must activate it through the
camera's menu system.

1. Power on the camera.
2. Navigate to **Menu → Wireless settings → Wi-Fi settings**.
3. Enable Wi-Fi if it is not already on.
4. Look for **Camera Control API (CCAPI)** or **Web API** in the wireless
   settings and enable it.
5. Note the camera's IP address displayed on screen (e.g., `192.168.1.42`).

> **Tip:** If you do not see a CCAPI option, make sure your camera's firmware is
> up to date. Visit [Canon's support site](https://www.usa.canon.com/support)
> to download the latest firmware.

---

## Step 2 — Connect Camera and Computer to the Same Network

Both the camera and the computer running Canon Focus Stack must be on the
**same Wi-Fi network**.

### Option A — Existing Wi-Fi Network

Connect both devices to your home or studio Wi-Fi router.

### Option B — Camera as Access Point

Some Canon cameras can create their own Wi-Fi access point:

1. On the camera, select **Camera Access Point Mode** in the Wi-Fi settings.
2. On your computer, connect to the camera's Wi-Fi network using the password
   displayed on the camera's screen.

> **Recommendation:** For lowest latency, use a dedicated 5 GHz Wi-Fi network
> with minimal congestion.

---

## Step 3 — Verify Connectivity

From your computer, verify that the camera is reachable:

```bash
# Replace with your camera's IP address
curl http://192.168.1.42:8080/ccapi
```

You should receive a JSON response listing the available CCAPI versions:

```json
{
  "ver100": "/ccapi/ver100",
  "ver110": "/ccapi/ver110"
}
```

---

## Step 4 — Configure Canon Focus Stack

Tell the system where to find the camera by setting the environment variable
before starting services:

### Docker Compose

Edit `docker-compose.yml` or create a `.env` file in the project root:

```env
CAMERA_CCAPI_URL=http://192.168.1.42:8080
CAMERA_MOCK_ENABLED=false
```

Then restart:

```bash
docker compose up --build
```

### Development Mode

Export the variable before starting camera-service:

```bash
export CAMERA_CCAPI_URL=http://192.168.1.42:8080
export CAMERA_MOCK_ENABLED=false
cd camera-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## Camera Settings Recommendations

For best focus-stacking results:

| Setting | Recommended Value | Reason |
|---|---|---|
| Mode | Aperture Priority (Av) or Manual (M) | Consistent exposure across the stack. |
| Aperture | f/5.6 – f/8 | Good balance of sharpness and depth of field per frame. |
| ISO | Lowest native (ISO 125) | Minimize noise for clean stacking. |
| Image Quality | JPEG Fine (L) or RAW+JPEG | JPEG for speed; RAW for post-processing flexibility. |
| Image Stabilization | Off | Tripod-mounted shots do not benefit from IS; it can introduce micro-shifts. |
| Auto Power Off | Disabled | Prevent the camera from sleeping mid-sequence. |

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `curl` times out | Ensure both devices are on the same network. Check the camera's Wi-Fi status screen. |
| CCAPI returns 403 | Some cameras require pairing. Check the camera screen for a pairing prompt and accept it. |
| Live View not starting | Make sure the camera is in a shooting mode (not playback). Switch to P/Av/M mode. |
| Focus control unresponsive | Ensure the lens is set to **AF** (auto-focus), not MF. CCAPI focus commands require AF mode. |
