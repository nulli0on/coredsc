---
sidebar_position: 14
---
# `modules/cases.yml`

Stores moderation cases and opens linked appeal threads in Discord.

See [Cases and appeals](/features/community) for setup and usage.

## Before enabling

- Configure staff roles and the appeal parent channel.
- Decide who may view case details.
- Back up the database because cases contain moderation history.

## Case lifecycle

Appeal message limits are enforced before thread creation. Private threads still depend on Discord permission configuration; verify access with a normal user account.

## Default configuration

```yaml title="plugins/CoreDSC/modules/cases.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: cases
# Set enabled to false to keep this module completely inactive.
enabled: false
staff-role-ids: []
appeals:
  parent-channel-id: ''
  private-thread: true
  message-min-length: 10
  message-max-length: 1500
```

[Download this default file](/default-configs/modules/cases.yml).
