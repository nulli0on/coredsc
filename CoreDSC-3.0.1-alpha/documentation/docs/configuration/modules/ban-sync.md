---
sidebar_position: 9
---
# `modules/ban-sync.yml`

Synchronizes bans for linked Minecraft and Discord identities.

See [Ban synchronization](/features/synchronization) for setup and usage.

## Before enabling

- Grant **Ban Members** and enable the Discord moderation intent.
- Test with non-staff accounts.
- Confirm the configured guild and bot hierarchy.

## Sync direction

`guild-id` may inherit the global guild. `poll-interval-ticks` controls reconciliation. The reason strings identify CoreDSC-created actions; they are not a substitute for ownership tracking.

## Default configuration

```yaml title="plugins/CoreDSC/modules/ban-sync.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# Initial linked-account ban synchronization. Review permissions before enabling.
enabled: false
guild-id: '' # falls back to discord.guild-id
poll-interval-ticks: 1200
minecraft-ban-reason: 'Discord ban synchronized by CoreDSC'
discord-ban-reason: 'Minecraft ban synchronized by CoreDSC'
```

[Download this default file](/default-configs/modules/ban-sync.yml).
