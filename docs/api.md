# REST API Reference

## Version contract

The canonical public REST contract is **v1** under `/api/v1/**`. The React client,
examples and new automation must use this prefix. Deployments may override the complete
base with `VITE_API_URL`; an override therefore includes the version prefix.

The previous unversioned `/api/**` routes remain temporary compatibility aliases. Each
alias and its `/api/v1/**` counterpart are mapped to the same controller method, DTOs,
validation, authorization, error handler and binary response path. Business logic is not
copied. The `legacy-compat` OpenAPI group marks these operations as deprecated.

Path versioning was selected instead of vendor media types because this is primarily a
local/self-hosted HTTP API with browser clients, ordinary download URLs and shell
automation. A visible path boundary is easier to configure, cache, link and inspect.
Vendor media types remain available for endpoint-specific representation negotiation,
but they are not the API-version selector.

### Compatibility and removal policy

Within v1, additive optional response fields, new optional request fields, new endpoints
and additional enum values with documented fallback behavior are compatible changes.
Removing or renaming fields/endpoints, changing field meaning or type, tightening a
previously accepted request, changing authorization semantics, changing the documented
error shape, or changing a binary media contract incompatibly requires a new major API
version.

The `/api/**` aliases remain supported for at least one complete minor application release
after the bundled React client has shipped exclusively on `/api/v1/**`. Removal also
requires all of the following:

1. a repository-wide audit finds no production or test client still generating legacy URLs;
2. release notes announce the removal and provide the mechanical prefix migration;
3. alias-equivalence tests are deliberately replaced by a documented major-version decision;
4. no alias is removed in a patch release.

There is no speculative v2 contract in this repository.

## OpenAPI

The REST API is documented via **SpringDoc OpenAPI / Swagger UI**. When the application
is running, use:

| Resource | URL | Purpose |
|---|---|---|
| Swagger UI | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | Interactive group selector |
| Canonical v1 JSON | `/v3/api-docs/rest-v1` | Supported current route set |
| Legacy compatibility JSON | `/v3/api-docs/legacy-compat` | Deprecated aliases during migration |
| Combined JSON | `/v3/api-docs` | Framework-generated aggregate view |

Executable contract tests maintain an explicit inventory of every REST controller and
require identical canonical and compatibility mappings. Adding a controller without a
versioning decision therefore fails normal repository verification.

## Errors, downloads and streams

The global RFC 9457-style `ProblemDetail` payload produced by the application is part of
the v1 contract. Its stable machine fields, HTTP status and sanitization rules may only
change compatibly within v1. Internal exception text, credentials, camera URLs, filesystem
paths and provider payloads are not public contract fields.

Image, ZIP, CSV/JSON export and generated-video endpoints use the same `/api/v1/**`
version boundary as JSON endpoints. Their status codes, media types, content-disposition
semantics and error responses are therefore versioned with REST. The compatibility alias
serves the identical bytes from the same handler during the migration window.

## Endpoints (port 8080)

The tables below list canonical paths. Replacing `/api/v1` with `/api` addresses the
temporary compatibility alias.

### Camera Profiles

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/cameras` | List all camera profiles |
| `POST` | `/api/v1/cameras` | Create a new camera profile |
| `GET` | `/api/v1/cameras/{id}` | Get a camera profile by ID |
| `PUT` | `/api/v1/cameras/{id}` | Update a camera profile |
| `DELETE` | `/api/v1/cameras/{id}` | Delete a camera profile |
| `GET` | `/api/v1/cameras/{id}/status` | Get camera connection status |
| `GET` | `/api/v1/cameras/{id}/liveview` | Get a Live View frame |
| `POST` | `/api/v1/cameras/{id}/capture` | Trigger a capture |
| `POST` | `/api/v1/cameras/{id}/focus` | Adjust focus position |
| `GET` | `/api/v1/cameras/search?q=...` | Full-text search camera profiles |

### Stacking Jobs

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/jobs` | List all stacking jobs |
| `POST` | `/api/v1/jobs` | Create a new stacking job |
| `GET` | `/api/v1/jobs/{id}` | Get a stacking job by ID |
| `POST` | `/api/v1/jobs/{id}/start` | Start a stacking job (async capture) |
| `POST` | `/api/v1/jobs/{id}/pause` | Pause a running job |
| `POST` | `/api/v1/jobs/{id}/cancel` | Cancel a job |
| `GET` | `/api/v1/jobs/search?q=...` | Full-text search jobs |
| `POST` | `/api/v1/jobs/reindex` | Trigger Hibernate Search reindex |
| `GET` | `/api/v1/jobs/{id}/export` | Download all frames as ZIP |
| `GET` | `/api/v1/jobs/{id}/frames/{frameId}/image` | Download one frame image |

### Astro Sessions

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/astro/sessions` | Create and asynchronously start an astro capture/stacking session |
| `GET` | `/api/v1/astro/sessions` | List astro sessions and persisted stacking provenance |
| `GET` | `/api/v1/astro/sessions/{id}` | Get current status, stacking mode and bounded stacking progress |
| `POST` | `/api/v1/astro/sessions/{id}/abort` | Cooperatively abort capture, focus scan or stacking |
| `DELETE` | `/api/v1/astro/sessions/{id}` | Delete one terminal session and its output files |
| `GET` | `/api/v1/astro/sessions/{id}/result` | Get a completed session record with result path and summary |

`captureConfig.stackingConfig` is optional. A create request that omits it preserves the
historical Mean behavior:

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

The persisted session response always contains the resolved configuration under
`captureConfig.stackingConfig`; for an omitted request value it is normalized to:

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

Supported modes are `MEAN`, `MEDIAN` and `SIGMA_CLIPPED_MEAN`. Sigma parameters are
persisted even for non-sigma modes so the complete submitted provenance can round-trip
without hidden defaults. The service executes exactly the persisted configuration.

While status is `STACKING`, `stackingProgressPercent` reports cooperative row progress in
the range `0..100`. Active-process polling receives the latest in-memory percentage.
Durable checkpoints are stored in 5-percentage-point increments, with exact progress
written on failure/abort and 100 on successful completion. This avoids a database write
per image row while preserving useful last-known progress after process loss or restart.
Historical database rows and clients that omit the new configuration continue to resolve
to `MEAN`.

### Analysis

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/analysis/sharpness` | Analyse frame sharpness |
| `POST` | `/api/v1/analysis/movement` | Detect movement between frames |
| `GET` | `/api/v1/analysis/plugins` | List all registered analysis plugins |
| `POST` | `/api/v1/analysis/plugins/{name}/analyze` | Run a named plugin on an uploaded image |
| `POST` | `/api/v1/analysis/merge-stack` | Upload and merge a frame stack |
| `POST` | `/api/v1/analysis/merge-stored-stack/{jobId}` | Develop and merge the original files from a stored capture job |
| `GET` | `/api/v1/analysis/merge-result/{jobId}` | Download a cached merged result |

The stored-stack endpoint returns a PNG from the original files on the server. The response headers `X-Photographer-Stack-Source` and `X-Photographer-Stack-Frame-Count` report the development path (`RAW`, `RASTER` or `EMBEDDED_PREVIEW`) and the number of files used. Embedded previews let a CR3 stack remain usable when native development is unavailable or exceeds the configured budget.

RAW analysis and stored-stack processing share the `focusstack.processing.decode.max-workers` and `focusstack.processing.decode.max-bytes` settings. By default they use the JVM's available processors and three quarters of its maximum heap up to 1 GiB. The limits apply to admitted processing work and its estimated working buffers.

## WebSocket and event schema v1

Real-time transport is versioned independently from REST so changing the REST prefix does
not break established browser connections:

| Endpoint | Protocol | v1 contract |
|---|---|---|
| `/ws` | STOMP over SockJS | Stable v1 connection endpoint |
| `/ws/plain` | Plain WebSocket | Stable v1 browser bridge |
| `/topic/jobs/{id}/progress` | Subscribe | v1 job-progress payload |
| `/topic/jobs/{id}/logs` | Subscribe | v1 sanitized log payload |

During browser sign-in the client sends HTTP Basic credentials to
`POST /api/v1/browser/socket-session` at the configured REST base with
`X-Requested-With: XMLHttpRequest`. A successful response returns HTTP 204
and establishes an HttpOnly session cookie. The same-origin `/ws/plain`
handshake recognizes this grant. REST calls still authenticate independently.
A native client can send Basic authentication on its upgrade, including when it
supplies a different Origin. A second host configured through `VITE_WS_URL`
receives no login password. For browser streaming, proxy the socket through the
same origin as the authenticated frontend. To revoke the browser grant and
close its streams, use the same headers on `DELETE /api/v1/browser/socket-session`.

The existing endpoints and topic payloads are schema version 1. Additive optional fields
are compatible. A breaking envelope or field-semantics change requires a new endpoint or
topic namespace such as `/ws/v2` or `/topic/v2/**`; it must not silently replace v1.

---

> **Tip:** For full request/response schemas, use the `rest-v1` Swagger UI group.
> The interactive documentation is generated from the same controller methods that serve
> both canonical and compatibility paths.
