# plugin-api Architecture

## Overview

`plugin-api` defines the public contract between Focus Stack and external runtime-loaded plugins. It contains interfaces and value records only: no implementation logic, Spring dependency or provider implementation dependency.

## Package structure

```text
com.photographer.focusstack.plugin
├── AnalysisPlugin.java          # SPI: custom image analysis
├── AnalysisContext.java         # Input parameter map
├── AnalysisPluginResult.java    # Output score and metadata
├── CameraBackendPlugin.java     # SPI: camera backend
├── VisionProviderPlugin.java    # SPI: AI provider
└── PluginMetadata.java          # Plugin id/name/version/author/license
```

`CameraBackendPlugin` extends the neutral camera contract from `common-api`. `VisionProviderPlugin` extends the neutral `VisionProvider` contract, also owned by `common-api`.

## Plugin loading

`FocusStackPluginManager` in `app` uses `ServiceLoader` and a controlled `URLClassLoader`. External JARs register implementations through `META-INF/services/`.

```text
plugins/
└── my-plugin-1.0.jar
    ├── com/example/MyAnalysisPlugin.class
    └── META-INF/services/
        └── com.photographer.focusstack.plugin.AnalysisPlugin
```

## Dependencies

```text
plugin-api ──► common-api
```

Forbidden edges:

```text
plugin-api -X-> ai-vision
plugin-api -X-> observer-core
```

An external provider plugin therefore compiles against the SPI and neutral contracts without pulling Gemini/mock implementations or observer workflow internals.

## Design decisions

- **ServiceLoader over PF4J**: the custom loader avoids the previously identified PF4J/Zip Slip risk and keeps the surface small.
- **No Spring**: plugin contracts remain portable outside the application context.
- **Neutral contracts**: implementation modules must never become dependencies of the public SPI.
- **Compatibility first**: contract packages and FQNs remain stable unless a versioned migration is explicitly approved.

## Guard

```bash
python3 scripts/test_validate_vision_module_boundaries.py
python3 scripts/validate_vision_module_boundaries.py .
```
