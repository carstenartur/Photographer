# plugin-api Architecture

## Overview

The `plugin-api` module defines the public contract between Focus Stack and
external runtime-loaded plugins. It contains only interfaces, records, and
value types — no implementation logic, no Spring dependency.

## Package Structure

```
com.photographer.focusstack.plugin
├── AnalysisPlugin.java          # SPI: custom image analysis
├── AnalysisContext.java          # Input: parameter map for plugins
├── AnalysisPluginResult.java     # Output: score + metadata from plugins
├── CameraBackendPlugin.java      # SPI: camera backend (extends CameraBackend)
├── VisionProviderPlugin.java     # SPI: AI vision provider (extends VisionProvider)
└── PluginMetadata.java           # Metadata record (id, name, version, author, license)
```

## Plugin Loading

Plugins are loaded by `FocusStackPluginManager` (in the `app` module) using
`ServiceLoader` + `URLClassLoader`. External JARs in the `plugins/` directory
register their implementations via `META-INF/services/` files.

```
plugins/
└── my-plugin-1.0.jar
    ├── com/example/MyAnalysisPlugin.class
    └── META-INF/services/
        └── com.photographer.focusstack.plugin.AnalysisPlugin
```

## Dependencies

```
plugin-api ──► common-api   (CameraBackend interface)
           ──► ai-vision    (VisionProvider interface)
```

## Design Decisions

- **ServiceLoader over PF4J**: PF4J was removed due to a Zip Slip
  vulnerability (CVE). The custom loader provides equivalent functionality
  with a smaller attack surface.
- **No Spring**: Plugins must work without Spring context to keep the SPI
  portable and dependency-light.
