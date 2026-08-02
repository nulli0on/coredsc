---
sidebar_position: 12
---
# `modules/tickets.yml`

Creates linked support tickets in Discord threads with configurable commands, limits and staff controls.

See [Tickets](/features/community) for setup and usage.

## Before enabling

- Configure the parent channel and staff roles.
- Test private-thread access with a normal player.
- Set cooldown and capacity limits before public use.

## Storage and limits

Command roots, aliases, subcommands and messages can be changed. Ticket records and replies are persisted; include the database in backups.

## Default configuration

```yaml title="plugins/CoreDSC/modules/tickets.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: tickets
# Set enabled to false to keep this module completely inactive.
enabled: false
parent-channel-id: ''
private-thread: true
cooldown-seconds: 300
max-open-per-user: 1
max-open-global: 100
reason-max-length: 100
message-max-length: 1000
user-can-close-own: true
staff-role-ids: []
commands:
  root: ticket
  aliases:
  - support
  keep-default-ticket-command: true
  description: Create and manage support tickets
  discord-description: Create and manage support tickets
  permission: coredsc.ticket
  subcommands:
    create: create
    status: status
    reply: reply
    close: close
    claim: claim
    priority: priority
  usage:
    create: /%command% %create% <reason> <message>
    reply: /%command% %reply% <id> <message>
    close: /%command% %close% <id>
messages:
  players-only: '&cOnly players can use tickets.'
  no-permission: '&cYou do not have permission.'
  command-disabled: '&cThis command is disabled. Use /%command%.'
  creation-failed: '&cTicket creation failed.'
  invalid-id: '&cInvalid ticket ID.'
  help:
  - '&b/%command% %create% <reason> <message>'
  - '&b/%command% %status%'
  - '&b/%command% %reply% <id> <message>'
  - '&b/%command% %close% <id>'
thread-name: ticket-%id%-%minecraft_name%
opening-message: |-
  **Ticket #%id%**
  Minecraft: `%minecraft_name%` (`%minecraft_uuid%`)
  Discord: <@%discord_user_id%>
  Reason: **%reason%**

  %message%
```

[Download this default file](/default-configs/modules/tickets.yml).
