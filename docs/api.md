# REST API Reference

The REST API is documented via **SpringDoc OpenAPI / Swagger UI**. When any
backend service is running, open the interactive documentation at:

| Service | Swagger UI |
|---|---|
| **camera-service** | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) |
| **analysis-service** | [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html) |

The raw OpenAPI spec (JSON) is available at `/v3/api-docs`.

---

## camera-service Endpoints (port 8080)

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

### WebSocket — Real-time Progress

| Endpoint | Protocol | Description |
|---|---|---|
| `/ws` | STOMP over SockJS | WebSocket connection endpoint |
| `/topic/jobs/{id}/progress` | Subscribe | Real-time job progress events |
| `/topic/jobs/{id}/logs` | Subscribe | Server-side log streaming |

---

## analysis-service Endpoints (port 8081)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/analysis/sharpness` | Analyse frame sharpness |
| `POST` | `/api/analysis/movement` | Detect movement between frames |

---

> **Tip:** For full request/response schemas, use the Swagger UI links above.
> The interactive docs are auto-generated from the code and always up to date.
