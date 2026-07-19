# CCAPI transport and secret boundary

Canon CCAPI transport is an application implementation detail. It is not part of the reusable camera, plugin, PTP, capture or CR3 APIs.

## Package and module ownership

`app` is the only Maven module allowed to depend directly on Apache HttpClient 5.

The production Apache integration is limited to:

- `CcapiRestTemplateFactory`: creates the host-scoped client, credential provider, connection pool and sanitized error boundary;
- `CcapiTransport`: owns and closes the client and overwrites its transport-owned password character array.

The remaining camera backends receive `RestTemplate` or vendor-neutral camera interfaces. Controllers, entities, repositories, plugin contracts, `common-api`, `camera-ptp`, capture modules and public CR3 artifacts must not import Apache authentication, client or connection-manager types.

`CcapiAuthenticationProperties` is the external Spring binding object. `FocusStackApplication` only enables that binding. `CameraBackendFactory` may use the secret-free credential revision state for cache identity. Only `CcapiRestTemplateFactory` may copy password characters into a transport credential.

The executable rule is `scripts/validate_ccapi_transport_boundary.py`. It runs before the Maven reactor in ordinary CI and has independent negative tests.

## Protected assets

The following values are protected even when they are short-lived or technically not plaintext passwords:

- CCAPI user names and passwords;
- `Authorization` and `WWW-Authenticate` header values;
- Digest realm, nonce, opaque, cnonce, nonce count and response digest;
- Wi-Fi SSIDs, authentication settings and Wi-Fi passwords returned by network-setting APIs;
- request or response bodies that may contain any of those values;
- camera URLs or diagnostic data when they embed credentials or tokens.

Protected assets must not enter `toString()`, JSON serialization, public Problem Details, metrics tags, log arguments, test-report failure messages, issue attachments or generated diagnostic bundles.

Stable public errors contain only a safe camera profile id, a fixed error code/category, retryability and an optional numeric downstream status.

## Logging and metrics

Application logging must describe outcomes rather than protocol material. HTTP wire/header logging must remain disabled in production.

CCAPI metrics use the bounded `camera.ccapi.outcomes` counter with one fixed `outcome` tag. Profile ids, host names, methods, paths, credentials and Digest parameters are forbidden as metric dimensions.

## Network threat boundary

Digest authentication prevents direct password transmission but does not make plain HTTP confidential. Live-view frames, downloaded images, metadata, paths and Digest exchange material remain observable or modifiable on an untrusted network.

Use a camera access-point connection or another isolated, trusted network. Prefer HTTPS only when the connected camera advertises and correctly supports it; certificate handling must not be weakened globally to make a camera connection succeed.

## Diagnostic policy

Synthetic tests use deterministic non-secret values. Real-camera diagnostics are optional, redacted and must never retain authorization headers, Wi-Fi settings or camera account values.

The uploaded Canon Android sample is behavioral evidence only. Its practices of logging generated authorization headers and complete Wi-Fi-setting JSON are explicitly outside this production boundary.
