# ai-vision

AI vision abstraction layer providing a unified `VisionProvider` interface with
Gemini and mock implementations.

## Purpose

This module decouples the Wildlife Observer from specific AI provider APIs.
All vision analysis goes through the `VisionProvider` interface, making it easy
to swap providers or add new ones.

## Components

| Type | Description |
|---|---|
| `VisionProvider` | Core interface for image classification, description, and ranking |
| `VisionResult` | Unified result record returned by all providers |
| `VisionContext` | Analysis context (mode, prompt, language, token limit) |
| `ProviderCapabilities` | Advertised capabilities of a provider |
| `ProviderConfig` | API key, endpoint, model, timeouts |
| `prompts.PromptTemplates` | Built-in JSON-output prompts for wildlife and macro modes |
| `mock.MockVisionProvider` | Test/development provider with pre-configured results |
| `gemini.GeminiVisionProvider` | Google Gemini API implementation (uses Java HttpClient) |
| `gemini.GeminiResponseParser` | Parses Gemini JSON responses into `VisionResult` |

## Configuration

Set the `GEMINI_API_KEY` environment variable, or pass the key via `ProviderConfig`.

```java
ProviderConfig config = new ProviderConfig("your-api-key",
    "https://generativelanguage.googleapis.com/v1beta/models",
    "gemini-2.5-flash", 30, 3, 60, 4 * 1024 * 1024L);
VisionProvider provider = new GeminiVisionProvider(config);
```

## Build

```bash
mvn -B clean verify -pl common-api,observer-core,ai-vision -am -Djava.awt.headless=true
```
