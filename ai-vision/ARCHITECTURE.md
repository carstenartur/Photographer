# ai-vision Architecture

## Overview

The ai-vision module provides a thin abstraction over AI vision APIs.
It depends on `observer-core` (for `VisionResultData`, `BoundingBox`) and
`common-api`, but has no Spring dependency.

## Provider Interface

```
VisionProvider
├── classifyImage(byte[], VisionContext) → VisionResult
├── describeImage(byte[], VisionContext) → VisionResult
├── rankBestImage(List<byte[]>, VisionContext) → VisionResult
├── isAvailable() → boolean
└── capabilities() → ProviderCapabilities
```

## Implementations

| Class | Description |
|---|---|
| `GeminiVisionProvider` | Production: calls Google Gemini REST API |
| `MockVisionProvider` | Testing: returns pre-configured results in round-robin |

## Prompt Flow

```
VisionContext.mode → PromptTemplates.forMode() → JSON prompt
                                                      ↓
                                              GeminiVisionProvider
                                                      ↓
                                           buildRequestBody(prompt, base64image)
                                                      ↓
                                            Gemini REST API
                                                      ↓
                                          GeminiResponseParser.parse()
                                                      ↓
                                                VisionResult
```

## Extension Points

- **New providers**: Implement `VisionProvider` (e.g. `OpenAiVisionProvider`, `OllamaVisionProvider`).
- **New prompts**: Add constants to `PromptTemplates`.
- **Custom parsing**: Subclass or compose `GeminiResponseParser`.
