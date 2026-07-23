# plugin-api

Stable SPI (Service Provider Interface) module for Focus Stack plugins. It defines extension points that external plugin JARs implement to add camera backends, analysis algorithms or AI vision providers at runtime.

## Extension points

| Interface | Extends | Purpose |
|---|---|---|
| `AnalysisPlugin` | — | Custom image-analysis algorithms. |
| `CameraBackendPlugin` | `CameraBackend` | Additional camera backends. |
| `VisionProviderPlugin` | `VisionProvider` | Alternative AI vision providers. |

`VisionProvider` and its context/result/capability records are neutral contracts owned by `common-api`. This SPI deliberately does not depend on the concrete `ai-vision` provider module.

## Supporting types

| Type | Role |
|---|---|
| `PluginMetadata` | Plugin id, name, version, author and license. |
| `AnalysisContext` | Parameter map passed to analysis plugins. |
| `AnalysisPluginResult` | Result returned by analysis plugins. |

## Usage

External plugins:

1. implement one or more SPI interfaces from this module;
2. register the implementation in `META-INF/services/<interface-fqn>`;
3. package the implementation as a JAR and place it in the `plugins/` directory;
4. let `FocusStackPluginManager` discover and load it at startup.

Built-in components such as CCAPI, mock and Gemini do not need this SPI; the `app` module composes them directly.

## Dependencies

- `common-api` — neutral camera, analysis and vision contracts;
- `slf4j-api` — logging facade.

Forbidden dependencies are enforced by `scripts/validate_vision_module_boundaries.py`:

- no `plugin-api -> ai-vision`;
- no `plugin-api -> observer-core`.

## Design principles

- **No Spring dependency** — plugin contracts remain framework-neutral.
- **No provider implementation dependency** — the SPI must be usable without Gemini/mock code.
- **Stable contracts** — incompatible SPI changes require explicit compatibility handling.
- **Minimal surface** — only types required by plugin authors are exposed.

## Verification

```bash
python3 scripts/validate_vision_module_boundaries.py .
mvn -B clean verify -pl plugin-api -am -Djava.awt.headless=true
```

## Canonical documentation

Cross-module ownership and the public extension-surface classification are maintained in [Modules and public surfaces](../docs/developer/modules.md). This README documents the SPI-specific contract only.
