---
sidebar_position: 21
---
# `modules/placeholderapi.yml`

Exposes or resolves supported placeholders when PlaceholderAPI is installed.

See [PlaceholderAPI integration](/placeholders) for setup and usage.

## Before enabling

- Install PlaceholderAPI before enabling the module.
- Verify each placeholder in the context where it is used.
- Test third-party placeholders in the exact feature where they will be used; some expansions expect to run on the server thread.

## Dependency behavior

CoreDSC starts normally without PlaceholderAPI when this module is disabled.

## Default configuration

```yaml title="plugins/CoreDSC/modules/placeholderapi.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: placeholderapi
# Set enabled to false to keep this module completely inactive.
enabled: true
```

[Download this default file](/default-configs/modules/placeholderapi.yml).
