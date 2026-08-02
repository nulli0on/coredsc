---
sidebar_position: 4
---
# `modules/status-channels.yml`

Renames Discord channels with live server values and a clean-shutdown offline name.

See [Status channels](/features/events-status) for setup and usage.

## Before enabling

- Grant Manage Channels.
- Use a long update interval because Discord heavily rate-limits renames.
- Test online and clean-shutdown names.

## Crash behavior

The plugin cannot update Discord after a forced process kill, host crash or network loss. Use an external monitor when crash-aware offline status is required.

## Default configuration

```yaml title="plugins/CoreDSC/modules/status-channels.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: status-channels
# Set enabled to false to keep this module completely inactive.
enabled: true

# Discord limits channel renames heavily. CoreDSC enforces a minimum of 300 seconds.
update-interval-seconds: 600
unique-player-refresh-seconds: 3600

# Maximum time to wait for offline renames during a clean shutdown.
shutdown-wait-seconds: 3

# Used when a channel does not define its own offline-name.
default-offline-name: 'Server is offline'

channels:
- id: ''
  type: voice
  # "name" is still accepted as a backwards-compatible alias for online-name.
  online-name: 'Players Online: %online_players%/%max_players%'
  offline-name: 'Server is offline'

# Available placeholders in both names:
# %online_players%, %max_players%, %tps%, %server_status%, %server_version%,
# %uptime%, %ram_used%, %ram_max%, %world_count%, %unique_players%
#
# The offline name is published during a clean Paper server shutdown while Discord is
# still connected. No plugin can update Discord after a forced process kill,
# machine crash, power loss, or network loss.
```

[Download this default file](/default-configs/modules/status-channels.yml).
