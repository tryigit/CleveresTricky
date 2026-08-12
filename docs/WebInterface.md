# Web Interface

## Purpose

The Web Interface provides one mobile control surface for core runtime state, identity, application rules, keyboxes, encrypted backup, logs, and validated configuration editing.

## Runtime file ownership

The packaged WebUI deliberately uses a fixed runtime layout. New features extend an existing owner instead of adding another JavaScript or CSS layer.

1. `index.html` owns static markup and base static CSS.
2. `bridge.js` owns the KernelSU/APatch native bridge, bounded transfer helpers, external intents, and minimal bootstrap.
3. `policy.js` owns policy and state API integration plus policy specific dynamic controls and pages.
4. `ux.js` owns general presentation, localization, guide rendering, community link behavior, and UX compatibility work.

There are no standalone runtime CSS files and no feature specific runtime JS bundles. The retired UX compatibility loader was consolidated into `ux.js`. Tests remain outside `module/template/webroot`; test, patch, overlay, experiment, or temporary JS and CSS files must not be shipped as runtime assets. The root `AGENTS.md` file is the authoritative development contract for this layout.

## Mobile behavior

On mobile screens the main tab menu is fixed to the bottom edge with safe area spacing so content and controls remain easier to reach. Desktop layouts keep the normal top tab presentation.

The layout uses touch sized controls, responsive panels, compact status summaries, password visibility controls, progress states, and clear result notifications. Tabs support keyboard focus and accessibility state. Long operations use bounded request timeouts and prevent duplicate button actions while work is active.

The Dashboard reports core protection as always active. Identity Spoof Engine is placed with the other identity controls instead of using a large dashboard panel. Changes that require reboot are identified separately from live runtime controls. Failed writes restore the visible toggle state instead of leaving the screen out of sync with the service.

The application selector reads Package Manager through the service and uses the module manager package API as a bounded fallback when the service query is temporarily unavailable.

## Native access protection

KernelSU or APatch loads the packaged `webroot` directly. The page uses the module manager native command API and never opens a local TCP port. A small Rust bridge moves bounded requests through root only queue directories to the existing service router.

Request identifiers use operating system randomness. Queue files are regular files with root only modes, published atomically, claimed before execution, removed after use, and expired when stale. The bridge accepts only fixed API paths, methods, parameter shapes, upload fields, response sizes, timeouts, and safe export names. The page uses a restrictive content security policy.

## Input handling

Every endpoint accepts a fixed method and bounded request form. File names, paths, JSON fields, package rules, templates, identifiers, keybox input, source settings, and backup data are validated again on the service side.

Unsafe paths, symbolic links, oversized input, duplicate archive entries, unknown settings, and malformed values are rejected. A visible success response is returned only after the service completes the requested write or operation.

## Recommended use

Open the interface from the module WebUI button in KernelSU or APatch. Fresh installations begin with Global Mode enabled and optional identity spoofing off. Configure key material and application scope first, then use the Identity section only when identity substitution is needed. Use Logs after each material change and restart an application that may cache old results.

[Return to the project overview](../README.md)