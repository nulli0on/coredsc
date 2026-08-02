---
sidebar_position: 3
---
# `modules/delivery-queue.yml`

Stores selected Discord deliveries and retries temporary failures with bounded backoff.

See [Delivery queue](/features/network-queue) for setup and usage.

## Before enabling

- Keep a finite pending limit.
- Monitor failed entries after permission or channel changes.
- Repair the destination before using the retry command.

## Retry behavior

`max-attempts` separates temporary retries from permanently failed deliveries. `maximum-backoff-seconds` prevents unbounded retry delays, while `maximum-pending` prevents unbounded database growth.

## Default configuration

```yaml title="plugins/CoreDSC/modules/delivery-queue.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: delivery-queue
# Set enabled to false to keep this module completely inactive.
enabled: true
flush-interval-seconds: 15
batch-size: 20
max-attempts: 12
maximum-pending: 5000
maximum-backoff-seconds: 3600
```

[Download this default file](/default-configs/modules/delivery-queue.yml).
