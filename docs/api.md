# REST API Reference

This document describes the HTTP endpoints exposed by **camera-service** and
**analysis-service**.

---

## camera-service (port 8080)

### Health

#### `GET /actuator/health`

Returns service health status.

**Response** `200 OK`
```json
{
  "status": "UP"
}
```

---

### Camera

#### `GET /api/camera/status`

Returns the current camera connection status and settings.

**Response** `200 OK`
```json
{
  "connected": true,
  "mock": false,
  "model": "Canon PowerShot G5 X Mark II",
  "battery": 87,
  "focusPosition": 450,
  "aperture": "f/5.6",
  "shutterSpeed": "1/125",
  "iso": 125
}
```

#### `POST /api/camera/connect`

Initiates connection to the camera via CCAPI.

**Request Body**
```json
{
  "ccapiUrl": "http://192.168.1.42:8080"
}
```

**Response** `200 OK`
```json
{
  "connected": true,
  "model": "Canon PowerShot G5 X Mark II"
}
```

---

### Live View

#### `GET /api/liveview/stream`

Returns an MJPEG stream proxied from the camera's Live View feed.

**Content-Type:** `multipart/x-mixed-replace; boundary=frame`

> Connect to this endpoint from an `<img>` tag or video component in the browser.

#### `POST /api/liveview/start`

Starts the Live View session on the camera.

**Response** `204 No Content`

#### `POST /api/liveview/stop`

Stops the Live View session.

**Response** `204 No Content`

---

### Focus Control

#### `POST /api/focus/move`

Moves the focus position by a relative step.

**Request Body**
```json
{
  "direction": "near",
  "step": 10
}
```

| Field | Type | Description |
|---|---|---|
| `direction` | `string` | `"near"` or `"far"` |
| `step` | `integer` | Step size (camera-specific units) |

**Response** `200 OK`
```json
{
  "focusPosition": 460
}
```

#### `GET /api/focus/position`

Returns the current absolute focus position.

**Response** `200 OK`
```json
{
  "focusPosition": 460
}
```

---

### Capture Sessions

#### `POST /api/sessions`

Creates a new capture session.

**Request Body**
```json
{
  "name": "Macro stack — flower",
  "nearFocus": 400,
  "farFocus": 800,
  "stepSize": 20
}
```

**Response** `201 Created`
```json
{
  "id": "a1b2c3d4",
  "name": "Macro stack — flower",
  "status": "CREATED",
  "createdAt": "2025-01-15T10:30:00Z"
}
```

#### `GET /api/sessions`

Lists all capture sessions.

**Response** `200 OK`
```json
[
  {
    "id": "a1b2c3d4",
    "name": "Macro stack — flower",
    "status": "COMPLETED",
    "frameCount": 24,
    "createdAt": "2025-01-15T10:30:00Z"
  }
]
```

#### `GET /api/sessions/{id}`

Returns details for a single session, including frames.

**Response** `200 OK`
```json
{
  "id": "a1b2c3d4",
  "name": "Macro stack — flower",
  "status": "COMPLETED",
  "nearFocus": 400,
  "farFocus": 800,
  "stepSize": 20,
  "frames": [
    {
      "index": 0,
      "focusPosition": 400,
      "sharpness": 1245.8,
      "movementDetected": false,
      "imagePath": "/storage/sessions/a1b2c3d4/frame-000.jpg"
    }
  ],
  "createdAt": "2025-01-15T10:30:00Z",
  "completedAt": "2025-01-15T10:32:14Z"
}
```

#### `POST /api/sessions/{id}/start`

Starts the focus-stack capture sequence for the given session.

**Response** `202 Accepted`
```json
{
  "id": "a1b2c3d4",
  "status": "RUNNING"
}
```

#### `POST /api/sessions/{id}/stop`

Stops a running capture sequence.

**Response** `200 OK`
```json
{
  "id": "a1b2c3d4",
  "status": "STOPPED"
}
```

---

### WebSocket — Capture Progress

#### `WS /ws/sessions/{id}/progress`

Streams real-time events during a capture sequence.

**Message format (server → client)**
```json
{
  "event": "FRAME_CAPTURED",
  "frameIndex": 5,
  "focusPosition": 500,
  "sharpness": 1312.4,
  "movementDetected": false
}
```

Event types: `FRAME_CAPTURED`, `SEQUENCE_COMPLETED`, `SEQUENCE_STOPPED`,
`MOVEMENT_DETECTED`, `ERROR`.

---

## analysis-service (port 8081)

### Health

#### `GET /actuator/health`

Returns service health status.

**Response** `200 OK`
```json
{
  "status": "UP"
}
```

---

### Frame Analysis

#### `POST /api/analysis/frame`

Analyses a single frame for sharpness and movement.

**Request** `multipart/form-data`

| Field | Type | Description |
|---|---|---|
| `image` | `file` | JPEG image to analyse |
| `sessionId` | `string` | Capture session ID |
| `frameIndex` | `integer` | Zero-based frame index |
| `previousImagePath` | `string` | (Optional) Path to the previous frame for movement detection |

**Response** `200 OK`
```json
{
  "sharpness": 1312.4,
  "tenengrad": 842.1,
  "movementDetected": false,
  "movementScore": 0.003
}
```

---

### Stack Merging

#### `POST /api/analysis/merge`

Merges a completed stack into a single all-in-focus image using headless Fiji.

**Request Body**
```json
{
  "sessionId": "a1b2c3d4",
  "framePaths": [
    "/storage/sessions/a1b2c3d4/frame-000.jpg",
    "/storage/sessions/a1b2c3d4/frame-001.jpg"
  ],
  "algorithm": "DEPTH_MAP"
}
```

| Field | Type | Description |
|---|---|---|
| `sessionId` | `string` | Capture session ID |
| `framePaths` | `string[]` | Ordered list of frame paths |
| `algorithm` | `string` | `"DEPTH_MAP"` or `"WEIGHTED_AVERAGE"` |

**Response** `202 Accepted`
```json
{
  "jobId": "m-98765",
  "status": "PROCESSING"
}
```

#### `GET /api/analysis/merge/{jobId}`

Polls the status of a merge job.

**Response** `200 OK`
```json
{
  "jobId": "m-98765",
  "status": "COMPLETED",
  "outputPath": "/storage/sessions/a1b2c3d4/merged.jpg"
}
```

---

## Error Responses

All endpoints return errors in a consistent format:

```json
{
  "error": "CAMERA_UNREACHABLE",
  "message": "Could not connect to camera at http://192.168.1.42:8080",
  "timestamp": "2025-01-15T10:35:00Z"
}
```

Common HTTP status codes:

| Code | Meaning |
|---|---|
| `400` | Bad request — invalid parameters |
| `404` | Resource not found |
| `409` | Conflict — e.g., session already running |
| `500` | Internal server error |
| `502` | Camera unreachable (CCAPI error) |
