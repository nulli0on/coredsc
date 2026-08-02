# `modules/web-editor.yml`

Controls the opt-in, loopback-only WebEditor. Enabling the module arms the feature; it does not keep an HTTP listener open. A local-console command creates each temporary session.

```yaml
config-version: 5
generated-by-version: "3.0.1-alpha"

enabled: false
bind-address: "127.0.0.1"
port: 8765

session:
  default-minutes: 15
  maximum-minutes: 30
  maximum-failed-auth-attempts-per-minute: 30

editor:
  maximum-file-bytes: 1048576
```

## Validation rules

- `bind-address` must be `127.0.0.1`, `::1` or `localhost`.
- `port` must be between `1024` and `65535`.
- `maximum-minutes` is limited to `60`; `default-minutes` cannot exceed it.
- `maximum-failed-auth-attempts-per-minute` must be between `5` and `120`.
- `maximum-file-bytes` must be between `65536` and `2097152`.

For remote administration, use an SSH tunnel. A public listener, reverse-proxy mode and browser access to `secrets.yml` are intentionally unsupported.

Exact default: [`/default-configs/modules/web-editor.yml`](/default-configs/modules/web-editor.yml)
