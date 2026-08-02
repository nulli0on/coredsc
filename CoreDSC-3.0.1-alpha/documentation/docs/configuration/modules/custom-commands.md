---
sidebar_position: 16
---
# `modules/custom-commands.yml`

Defines Minecraft and Discord commands in YAML with options, cooldowns and bounded actions.

See [Custom commands](/features/automation) for setup and usage.

## Before enabling

- Keep console actions disabled initially.
- Use unique lowercase command names.
- Test permissions, linked-only rules and Discord roles with normal accounts.

## Available actions

Each command can target Minecraft, Discord or both. Actions are executed only from the supported fixed action set. Console command prefixes must be narrow and must not include untrusted option text.

## Default configuration

```yaml title="plugins/CoreDSC/modules/custom-commands.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: custom-commands
# Set enabled to false to keep this module completely inactive.
enabled: false
server-address: play.example.com
allow-console-actions: false
console-command-allow-prefixes:
- 'whitelist add '
- 'whitelist remove '
- 'kick '
commands:
- enabled: true
  name: server
  description: Show Minecraft server information
  platforms:
  - MINECRAFT
  - DISCORD
  minecraft-permission: ''
  ephemeral: false
  guild-only: false
  linked-only: false
  cooldown-seconds: 5
  allowed-role-ids: []
  response: |-
    **%server_name%**
    Players: %online_players%/%max_players%
    Version: %server_version%
    Uptime: %uptime%
    Address: %server_address%
  actions: []
  options: []
- enabled: true
  name: rules
  description: Show the server rules
  platforms:
  - MINECRAFT
  - DISCORD
  minecraft-permission: ''
  ephemeral: true
  guild-only: false
  linked-only: false
  cooldown-seconds: 3
  allowed-role-ids: []
  response: Read the rules at https://example.com/rules
  actions: []
  options: []
- enabled: false
  name: whitelistme
  description: Add the linked player to the whitelist
  platforms:
  - DISCORD
  ephemeral: true
  guild-only: true
  linked-only: true
  cooldown-seconds: 60
  allowed-role-ids:
  - '0'
  response: Whitelist action completed for %minecraft_name%.
  actions:
  - type: RUN_CONSOLE_COMMAND
    command: whitelist add %minecraft_name%
  options: []
```

[Download this default file](/default-configs/modules/custom-commands.yml).
