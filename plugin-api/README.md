# plugin-api

Stable SPI (Service Provider Interface) module for Focus Stack plugins.
Defines the extension points that external plugin JARs implement to add
camera backends, analysis algorithms, or AI vision providers at runtime.

## Extension Points

| Interface | Extends | Purpose |
|---|---|---|
| `AnalysisPlugin` | — | Custom image analysis algorithms |
| `CameraBackendPlugin` | `CameraBackend` | Additional camera backends (e.g. Sony, Nikon) |
| `VisionProviderPlugin` | `VisionProvider` | Alternative AI vision providers (e.g. OpenAI) |

## Supporting Types

| Type | Role |
|---|---|
| `PluginMetadata` | Record describing a plugin (id, name, version, author, license) |
| `AnalysisContext` | Parameter map passed to analysis plugins |
| `AnalysisPluginResult` | Result record returned by analysis plugins |

## Usage

External plugins:
1. Implement one or more SPI interfaces from this module.
2. Register the implementation in `META-INF/services/<interface-fqn>`.
3. Package as a JAR and place it in the `plugins/` directory.
4. The `FocusStackPluginManager` discovers and loads plugins at startup.

Built-in components (CCAPI, mock, Gemini) do **not** use this module —
they are wired directly via Spring in the `app` module.

## Dependencies

- `common-api` — shared records and `CameraBackend` interface
- `ai-vision` — `VisionProvider` interface

## Design Principles

- **No Spring dependency** — plugins must not depend on Spring Framework.
- **Stable contracts** — SPI interfaces change only with major version bumps.
- **Minimal surface** — only types needed by plugin authors are exposed.
