# ai-vision

Provider implementations and provider-local support code for the neutral vision contracts owned by `common-api`.

## Purpose

This module contains Gemini, mock and composition-oriented vision-provider implementations. It does not own the public provider port and does not depend on observer-domain types.

The neutral contracts remain under the unchanged package `com.photographer.focusstack.ai` but are compiled from `common-api`:

- `VisionProvider`;
- `VisionResult`;
- `VisionContext`;
- `ProviderCapabilities`;
- `VisionFailureKind`.

This preserves the dependency direction:

```text
common-api contracts
        ↓
ai-vision implementations
        ↓
app composition
```

Observer workflows continue to own `VisionProviderPort` and `VisionResultData` in `observer-core`. The `app` module adapts/composes those domain ports with concrete providers.

## Components

| Type | Description |
|---|---|
| `ProviderConfig` | API key, endpoint, model, timeout, retry and input limits. |
| `TwoStageVisionProvider` | Optional two-stage composition that preserves typed pre-filter failures. |
| `prompts.PromptTemplates` | Built-in JSON-output prompts for wildlife and macro modes. |
| `mock.MockVisionProvider` | Deterministic test/development provider. |
| `gemini.GeminiVisionProvider` | Google Gemini API implementation with bounded request/retry behavior. |
| `gemini.GeminiResponseParser` | Strictly parses Gemini JSON responses into the shared `VisionResult`. |
| `permission.AIGateway` | Local privacy/permission gate that fails before provider contact. |

## Configuration

Set `GEMINI_API_KEY`, or pass the key through `ProviderConfig`:

```java
ProviderConfig config = new ProviderConfig(
    "your-api-key",
    "https://generativelanguage.googleapis.com/v1beta/models",
    "gemini-2.5-flash",
    30,
    3,
    60,
    4 * 1024 * 1024L);
VisionProvider provider = new GeminiVisionProvider(config);
```

`timeoutSeconds` bounds every individual HTTP request. `maxRetries` is additionally clamped by the Gemini implementation to at most five retries, so misconfiguration cannot create an unbounded loop.

## Failure semantics

A successful negative classification remains:

```text
successful=true, primaryLabel=none, failureKind=NONE
```

A provider or policy failure is always:

```text
successful=false, primaryLabel=unclassified, confidence=0
```

It carries a stable `VisionFailureKind`, retryability and an optional bounded retry hint. It never carries provider raw response data or object detections. Important categories include:

- `NOT_CONFIGURED` — credentials/configuration missing;
- `POLICY_DENIED` — local privacy or permission gate blocked the call;
- `AUTHENTICATION` — provider rejected credentials/permission;
- `INVALID_REQUEST` — bad input or provider 4xx contract rejection;
- `TIMEOUT` — bounded deadline exceeded;
- `RATE_LIMITED` — HTTP 429, optionally with a clamped retry hint;
- `TEMPORARY_UNAVAILABLE` — I/O or provider 5xx outage;
- `MALFORMED_RESPONSE` — HTTP success whose JSON does not match the required result schema;
- `INTERRUPTED` — caller interruption, preserved and never retried;
- `CIRCUIT_OPEN` — reserved for the next resilience layer.

Malformed provider JSON is not converted to a successful `none`. This prevents unavailable or corrupt AI output from being stored as a genuine negative observation.

## Gemini retry contract

Gemini retries only typed transient failures:

- `RATE_LIMITED`;
- `TIMEOUT`;
- `TEMPORARY_UNAVAILABLE`.

Authentication, invalid request, malformed response, policy denial and interruption are not retried. Without a provider hint, delays use bounded exponential backoff beginning at 100 ms and capped at 2 seconds. Numeric `Retry-After` is capped at 60 seconds. Every retry uses the same request deadline.

Logs contain task type, status and failure category, not API keys, Authorization/query credentials, prompts, images or response bodies. Safe public messages are category-level descriptions and do not include exception or provider payload text.

## Composition behavior

`TwoStageVisionProvider` invokes stage two only after a successful positive pre-filter. A failed pre-filter remains `unclassified` with its original category/retry hint; a successful low-confidence or `none` result remains a genuine negative pre-filter outcome.

`AIGateway` reports `POLICY_DENIED` and never contacts the delegate when local access/privacy policy rejects a call. This is intentionally distinct from provider-side `AUTHENTICATION`.

## Current resilience boundary

This module now owns typed provider outcomes and bounded Gemini retries. Issue #285 still owns:

- a circuit breaker with recovery/half-open behavior;
- call-site classification as mandatory, optional enrichment or gate;
- persisted observer/job degradation status;
- user-facing diagnostics and aggregate metrics;
- bounded postpone/requeue behavior where a workflow explicitly permits it.

No call site may substitute a guessed species, quality label or successful negative classification when a provider failed.

## Dependencies

- `common-api` — neutral provider contracts and shared value types;
- Jackson Databind — provider response parsing;
- SLF4J — logging facade.

`ai-vision` must not depend on `observer-core`. The executable boundary check is:

```bash
python3 scripts/test_validate_vision_module_boundaries.py
python3 scripts/validate_vision_module_boundaries.py .
```

## Build

```bash
mvn -B clean verify -pl ai-vision -am -Djava.awt.headless=true
```

## Canonical documentation

Cross-module provider/SPI/domain ownership is maintained in [Modules and public surfaces](../docs/developer/modules.md). Provider-local configuration and resilience semantics remain in this README.
