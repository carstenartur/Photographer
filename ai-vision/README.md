# ai-vision

Provider implementations and provider-local support code for the neutral vision contracts owned by `common-api`.

## Purpose

This module contains Gemini, mock and composition-oriented vision-provider implementations. It does not own the public provider port and does not depend on observer-domain types.

The neutral contracts remain under the unchanged package `com.photographer.focusstack.ai` but are compiled from `common-api`:

- `VisionProvider`;
- `VisionResult`;
- `VisionContext`;
- `ProviderCapabilities`.

This preserves source and binary names while restoring the dependency direction:

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
| `ProviderConfig` | API key, endpoint, model and timeout configuration. |
| `TwoStageVisionProvider` | Optional two-stage provider composition. |
| `prompts.PromptTemplates` | Built-in JSON-output prompts for wildlife and macro modes. |
| `mock.MockVisionProvider` | Deterministic test/development provider. |
| `gemini.GeminiVisionProvider` | Google Gemini API implementation using Java `HttpClient`. |
| `gemini.GeminiResponseParser` | Parses Gemini JSON responses into the shared `VisionResult`. |
| `permission.AIGateway` | Permission and policy gate for provider calls. |

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

Do not commit keys or log provider credentials/private payloads. Provider-outage semantics are tracked separately in issue #285.

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
