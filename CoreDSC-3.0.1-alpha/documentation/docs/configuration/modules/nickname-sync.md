---
sidebar_position: 8
---
# `modules/nickname-sync.yml`

Sets linked Discord nicknames from Minecraft identity and can restore prior nicknames.

See [Nickname synchronization](/features/synchronization) for setup and usage.

## Before enabling

- Enable the Server Members intent.
- Grant Manage Nicknames.
- Place the bot above affected members.
- Test length and formatting with real names.

## Nickname restoration

Discord nickname limits still apply after formatting. Restoration depends on CoreDSC having stored the previous nickname before changing it.

## Default configuration

```yaml title="plugins/CoreDSC/modules/nickname-sync.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# Synchronizes linked players' Minecraft names to Discord nicknames.
enabled: false
guild-id: '' # falls back to discord.guild-id
format: '%player%'
restore-on-unlink: true
```

[Download this default file](/default-configs/modules/nickname-sync.yml).
