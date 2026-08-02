---
sidebar_position: 19
---
# `modules/network.yml`

Provides local or Redis-backed event transport for selected multi-server behavior.

See [Network](/features/network-queue) for setup and usage.

## Before enabling

- Keep Redis private, authenticated and firewalled.
- Use a distinct channel/namespace for the network.
- Do not place passwords in public support logs.

## Local and Redis modes

Local mode stays inside one server process. Redis mode connects multiple CoreDSC servers and requires a working Redis instance.

## Default configuration

```yaml title="plugins/CoreDSC/modules/network.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: network
# Set enabled to false to keep this module completely inactive.
enabled: false
mode: local
network-id: default
server-id: survival-1
shared-links:
  enabled: true
  ttl-seconds: 0
redis:
  host: 127.0.0.1
  port: 6379
  tls: false
  username: ''
  password-env: COREDSC_REDIS_PASSWORD
  database: 0
  timeout-millis: 5000
```

[Download this default file](/default-configs/modules/network.yml).
