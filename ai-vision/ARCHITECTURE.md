# ai-vision Architecture

## Overview

`ai-vision` contains concrete AI provider implementations and provider-local support code. The neutral `VisionProvider`, `VisionContext`, `VisionResult` and `ProviderCapabilities` contracts are owned by `common-api` under their existing Java package names.

The module depends on `common-api`, Jackson, ONNX Runtime CPU and SLF4J. It has no Spring or `observer-core` dependency.

```text
common-api contracts
        ↓
ai-vision provider implementations
        ↓
app composition/adapters
```

## Provider contract

```text
VisionProvider
├── classifyImage(byte[], VisionContext) → VisionResult
├── describeImage(byte[], VisionContext) → VisionResult
├── detectObjects(byte[], VisionContext) → VisionResult
├── rankBestImage(List<byte[]>, VisionContext) → VisionResult
├── isAvailable() → boolean
└── capabilities() → ProviderCapabilities
```

The contract is compiled from `common-api`; this module implements it.

## Implementations

| Class | Description |
|---|---|
| `GeminiVisionProvider` | Production provider using the Gemini REST API. |
| `LocalBirdVisionProvider` | Generic bird detection through a single reusable on-device CPU session. |
| `MockVisionProvider` | Deterministic testing/development provider. |
| `TwoStageVisionProvider` | Provider composition for staged analysis. |

## Prompt flow

```text
VisionContext.mode → PromptTemplates.forMode() → JSON prompt
                                              ↓
                                      GeminiVisionProvider
                                              ↓
                                   build request / HTTP call
                                              ↓
                                  GeminiResponseParser.parse()
                                              ↓
                                         VisionResult
```

## Observer integration

`observer-core` owns the domain-specific `VisionProviderPort` and `VisionResultData`. The `app` module adapts those observer contracts to a selected `VisionProvider`; provider implementations do not depend on observer-domain code.

## Extension points

- **Built-in providers**: implement `VisionProvider` in this module.
- **External providers**: implement `VisionProviderPlugin` from `plugin-api`.
- **New prompts**: extend `PromptTemplates` with tests.
- **Custom parsing**: compose provider-local parsers rather than leaking provider responses into neutral contracts.

## Guard

```bash
python3 scripts/test_validate_vision_module_boundaries.py
python3 scripts/validate_vision_module_boundaries.py .
```

The validator rejects `ai-vision -> observer-core` and misplaced/duplicated neutral contracts.
