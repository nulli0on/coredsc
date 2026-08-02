# Network and delivery queue

## Delivery queue

The queue stores selected Discord deliveries when a temporary failure prevents immediate sending.

```yaml
flush-interval-seconds: 15
batch-size: 20
max-attempts: 12
maximum-pending: 5000
maximum-backoff-seconds: 3600
```

Inspect it with:

```text
/coredsc queue status
/coredsc queue retry
/coredsc queue clear
```

Fix the destination or permission problem before retrying failed deliveries.

## Network module

The network module supports local or Redis-backed event transport for selected multi-server behavior. Redis credentials are secrets. Restrict network access, use authentication and do not expose Redis directly to the internet.
