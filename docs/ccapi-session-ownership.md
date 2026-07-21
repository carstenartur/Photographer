# CCAPI session ownership

## Why a lease exists

Canon documents that only one opposing device can control a camera reliably at one time. Backend reuse alone does not enforce this constraint because backend caching is keyed by profile, while multiple profiles may resolve to the same physical CCAPI origin.

The application therefore uses a separate logical ownership layer:

```text
workflow ownership -> camera session lease -> backend resolution/cache -> CCAPI transport
```

Lease ownership must be acquired before a state-changing workflow resolves or contacts its backend. Backend eviction, Digest state and transport cleanup remain independent concerns.

## Private identity

A Canon CCAPI camera is keyed by a normalized origin:

```text
scheme://normalized-host:effective-port
```

Normalization:

- HTTP/HTTPS scheme and DNS host are lowercased;
- an implicit camera location uses the existing application default port `8080`;
- explicit HTTP and HTTPS locations use effective ports `80` and `443` when absent;
- bracketed IPv6 literals are canonicalized;
- path, query and fragment do not affect ownership;
- user-info, credentials, scoped IPv6 and malformed hosts are rejected.

The canonical origin is private. Public diagnostics contain only a short one-way identifier. Profile ID, URL, host, serial, credentials and logical owner token must not appear in conflict responses or identity-labelled metrics.

## Lease semantics

- first owner acquires atomically;
- the same logical owner may acquire reentrant handles;
- all handles share one redacted correlation ID;
- closing a handle is idempotent;
- ownership remains until the final handle closes;
- a different owner receives `CAMERA_SESSION_CONFLICT` without contacting the camera;
- a hard deadline does not permit stealing a lease with active handles;
- requested lifetime and reentrant handle count are bounded;
- application shutdown clears process-local ownership after workflow cancellation;
- aggregate metrics contain no camera or owner labels.

## HTTP conflict

A public conflict uses HTTP `409` and may include only:

```json
{
  "code": "CAMERA_SESSION_CONFLICT",
  "ownerCorrelationId": "<redacted>",
  "workflowType": "<safe type>",
  "retryable": true,
  "retryAfterSeconds": 30
}
```

The retry duration is advisory and bounded. It never grants permission to interrupt or steal an active operation.

## Delivery phases

### Phase 1 — core manager

Implemented by PR #240:

- normalized private CCAPI identity;
- atomic in-memory manager;
- reentrancy and final-handle release;
- typed sanitized conflict;
- identity-free metrics;
- concurrent and normalization tests.

### Phase 2 — workflow integration

Each state-changing workflow must acquire before backend resolution and release from one outer `try/finally` or try-with-resources boundary:

1. focus stacking, including manual, native and AUTO fallback;
2. HDR timelapse;
3. face orbit;
4. astro and other camera-owning sessions;
5. direct state-changing camera endpoints.

Read-only discovery may remain lease-free only when the camera contract and race tests justify it.

### Phase 3 — lifecycle verification

Verify cancellation, startup failure, executor rejection, timeout, resume/retry, two-profile aliasing, Docker packaging and application shutdown. The suite must finish with zero active leases.

## Non-goals

- no distributed lock until multi-instance deployment is explicitly enabled;
- no lock embedded in the backend cache;
- no identity derived from a display name;
- no USB/PTP exclusivity until a stable device identity is available;
- no active-operation interruption when a deadline passes.
