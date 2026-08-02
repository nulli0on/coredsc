# `telemetry.yml`

```yaml
bstats:
  enabled: true
  feature-activity: true
```

`enabled` controls CoreDSC's bStats integration. The server-wide switch in `plugins/bStats/config.yml` is also respected.

`feature-activity` enables bounded aggregate activity charts. It does not transmit message content, commands, Discord IDs, player identities or arbitrary configuration values.

Use `/coredsc telemetry` to inspect the current state. bStats is intentionally not shown as a core health item in the default Doctor output.

[Download the exact default file](/default-configs/telemetry.yml).
