(ns hc.version
  "Single source of truth for the application release version.

  Build manifests (`pixi.toml`, `package.json`) and user-facing run/release
  notes must be kept in step with this value.  Runtime health, diagnostics,
  backup metadata, and MCP client identity read it from here.")

(def version "0.0.1")
