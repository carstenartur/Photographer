# REST API Reference

The REST API is documented via **SpringDoc OpenAPI / Swagger UI**. When the application is running, open the interactive documentation at:

| | |
|---|---|
| **Swagger UI** | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) |

The raw OpenAPI spec (JSON) is available at `/v3/api-docs`.

---

## Endpoints (port 8080)

### Camera Profiles

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/cameras` | List all camera profiles |
| `POST` | `/api/cameras` | Create a new camera profile |
| `GET` | `/api/cameras/{id}` | Get a camera profile by ID |
| `PUT` | `/api/cameras/{id}` | Update a camera profile |
| `DELETE` | `/api/cameras/{id}` | Delete a camera profile |
| `GET` | `/api/cameras/{id}/status` | Get camera connection status |
| `GET` | `/api/cameras/{id}/liveview` | Get a Live View frame |
| `POST` | `/api/cameras/{id}/capture` | Trigger a capture |
| `POST` | `/api/cameras/{id}/focus` | Adjust focus position |
| `GET` | `/api/cameras/search?q=...` | Full-text search camera profiles |

### Stacking Jobs

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/jobs` | List all stacking jobs |
| `POST` | `/api/jobs` | Create a new stacking job |
| `GET` | `/api/jobs/{id}` | Get a stacking job by ID |
| `POST` | `/api/jobs/{id}/start` | Start a stacking job (async capture) |
| `POST` | `/api/jobs/{id}/pause` | Pause a running job |
| `POST` | `/api/jobs/{id}/cancel` | Cancel a job |
| `GET` | `/api/jobs/search?q=...` | Full-text search jobs |
| `POST` | `/api/jobs/reindex` | Trigger Hibernate Search reindex |

### Astro Sessions

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/astro/sessions` | Create and asynchronously start an astro capture/stacking session |
| `GET` | `/api/astro/sessions` | List astro sessions and persisted stacking provenance |
| `GET` | `/api/astro/sessions/{id}` | Get current status, stacking mode and bounded stacking progress |
| `POST` | `/api/astro/sessions/{id}/abort` | Cooperatively abort capture, focus scan or stacking |
| `DELETE` | `/api/astro/sessions/{id}` | Delete one terminal session and its output files |
| `GET` | `/api/astro/sessions/{id}/result` | Get a completed session record with result path and summary |

`captureConfig.stackingConfig` is optional. A create request that omits it preserves the historical Mean behavior:

```json
{
  "captureConfig": {
    "frameCount": 8,
    "exposureTimeMs": 5000,
    "iso": 1600,
    "delayBetweenFramesMs": 1000,
    "imageFormat": "PNG",
    "saveRaw": false,
    "saveJpeg": true,
    "captureDarkFrame": false
  },
  "focusConfig": {
    "strategy": "MANUAL_FIXED",
    "scanSteps": 5,
    "scanStepSize": 10
  }
}
```

The persisted session response always contains the resolved configuration under `captureConfig.stackingConfig`; for an omitted request value it is normalized to:

```json
{
  "captureConfig": {
    "stackingConfig": {
      "mode": "MEAN",
      "sigmaThreshold": 2.5,
      "maxIterations": 3,
      "minimumSamples": 4
    }
  }
}
```

Supported modes are `MEAN`, `MEDIAN` and `SIGMA_CLIPPED_MEAN`. Sigma parameters are persisted even for non-sigma modes so the complete submitted provenance can round-trip without hidden defaults. The service executes exactly the persisted configuration.

While status is `STACKING`, `stackingProgressPercent` reports cooperative row progress in the range `0..100`. Active-process polling receives the latest in-memory percentage. Durable checkpoints are stored in 5-percentage-point increments, with exact progress written on failure/abort and 100 on successful completion. This avoids a database write per image row while preserving useful last-known progress after process loss or restart. Historical database rows and clients that omit the new configuration continue to resolve to `MEAN`.

### Analysis

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/analysis/sharpness` | Analyse frame sharpness |
| `POST` | `/api/analysis/movement` | Detect movement between frames |
| `GET`  | `/api/analysis/plugins` | List all registered analysis plugins |
| `POST` | `/api/analysis/plugins/{name}/analyze` | Run a named plugin on an uploaded image |

### WebSocket — Real-time Progress

| Endpoint | Protocol | Description |
|---|---|---|
| `/ws` | STOMP over SockJS | WebSocket connection endpoint |
| `/topic/jobs/{id}/progress` | Subscribe | Real-time job progress events |
| `/topic/jobs/{id}/logs` | Subscribe | Server-side log streaming |

---

> **Tip:** For full request/response schemas, use the Swagger UI link above.
> The interactive docs are auto-generated from the code and always up to date.
