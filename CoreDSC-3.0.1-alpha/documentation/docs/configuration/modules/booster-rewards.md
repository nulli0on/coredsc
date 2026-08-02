---
sidebar_position: 10
---
# `modules/booster-rewards.yml`

Runs persistent periodic rewards for linked Discord server boosters.

See [Booster rewards](/features/synchronization) for setup and usage.

## Before enabling

- Enable the Server Members intent.
- Set the actual booster role ID.
- Use commands that are safe to repeat only after the configured reward period.

## Reward schedule

The module stores reward periods so a restart does not immediately repeat a previous reward. `reconcile-interval-ticks` controls how often linked accounts are checked.

## Default configuration

```yaml title="plugins/CoreDSC/modules/booster-rewards.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# Persistent rewards for linked Discord server boosters.
enabled: false
guild-id: '' # falls back to discord.guild-id
booster-role-id: ''
reward-period-days: 30
reconcile-interval-ticks: 72000
commands:
  - 'eco give %player% 1000'
```

[Download this default file](/default-configs/modules/booster-rewards.yml).
