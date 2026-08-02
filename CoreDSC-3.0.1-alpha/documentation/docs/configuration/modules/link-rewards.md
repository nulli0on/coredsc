---
sidebar_position: 6
---
# `modules/link-rewards.yml`

Runs configured console commands once after a player links for the first time.

See [Link rewards](/features/linking) for setup and usage.

## Before enabling

- Test account linking first.
- Use commands from installed plugins only.
- Avoid rewards that can be duplicated through another system.

## Reward claims

Reward claims are persisted. `%player%` is replaced with the linked Minecraft player name. Changes to the command list do not automatically reset completed claims.

## Default configuration

```yaml title="plugins/CoreDSC/modules/link-rewards.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# Grants commands once after a Minecraft account is linked for the first time.
enabled: false
commands:
  - 'give %player% diamond 1'
  - 'eco give %player% 500'
```

[Download this default file](/default-configs/modules/link-rewards.yml).
