---
sidebar_position: 7
---
# `modules/luckperms-sync.yml`

Maps LuckPerms groups and Discord roles in one or both directions.

See [LuckPerms synchronization](/features/synchronization) for setup and usage.

## Before enabling

- Install LuckPerms.
- Enable the Server Members intent and grant Manage Roles.
- Place the bot role above every managed role.
- Review all removal switches before enabling.

## Source of truth

`initial-authority` controls which side wins during the first reconciliation. Each mapping has an independent direction. Two-way removal can remove legitimate access if mappings or role hierarchy are wrong.

## Default configuration

```yaml title="plugins/CoreDSC/modules/luckperms-sync.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: luckperms-sync
# Set enabled to false to keep this module completely inactive.
enabled: false
guild-id: 0
initial-authority: minecraft
remove-discord-role-when-group-missing: true
remove-luckperms-group-when-role-missing: true
remove-discord-roles-on-unlink: true
remove-luckperms-groups-on-unlink: false
reconcile-online-seconds: 600
mappings:
- enabled: false
  group: vip
  role-id: '0'
  direction: minecraft-to-discord
```

[Download this default file](/default-configs/modules/luckperms-sync.yml).
