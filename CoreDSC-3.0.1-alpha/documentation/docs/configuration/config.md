# `config.yml`

```yaml
language: en
debug: false
startup-banner:
  mode: FULL
  colors: true
discord:
  enabled: true
  token-source: ENV
  token-env-name: COREDSC_BOT_TOKEN
  guild-id: 0
  command-registration: guild
  link-role-id: ''
  unlink-remove-role: true
storage:
  sqlite:
    queue-capacity: 8192
```

## `startup-banner.mode`

- `FULL` — bordered startup banner
- `COMPACT` — short version/status line
- `OFF` — no banner

## `discord.token-source`

- `ENV` reads the environment variable named by `token-env-name`.
- `SECRETS.YML` reads `discord-token` from `secrets.yml`.

## `discord.guild-id`

Set the numeric Discord guild ID. If CoreDSC cannot find that guild, guild command registration stops. This prevents commands from being registered in the wrong scope.

## `discord.command-registration`

- `guild` is recommended while configuring and testing.
- `global` publishes global application commands and may take longer to propagate.

## Link role

`link-role-id` is optionally assigned after linking. When `unlink-remove-role` is true, CoreDSC removes the role during unlinking.

## `storage.sqlite.queue-capacity`

The SQLite worker accepts at most this many queued operations (`256-65536`, default `8192`). When full, CoreDSC rejects new database work instead of running or waiting on a Folia region thread. Treat rejection as a disk-latency or excessive-write warning first; increase the limit only when the host has memory headroom. Keep `plugins/CoreDSC/data.db` on a local writable disk, not a network filesystem.

[Download the exact default file](/default-configs/config.yml).
