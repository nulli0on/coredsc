# `modules/economy-market.yml`

Provides Vault-backed balance and inventory lookups plus a Discord market browser. Install Vault and one Vault-compatible economy plugin before enabling it.

```yaml
config-version: 5
generated-by-version: "3.0.1-alpha"
enabled: false

privacy:
  require-linked-account: true
  responses-ephemeral: true
  expose-item-lore: false
  maximum-inventory-lines: 35
  allow-staff-player-lookup: false

commands:
  balance: balance
  inventory: inventory
  market: market
  cooldown-seconds: 3

market:
  source: CONFIG
  page-size: 8
  title: '%server_name% Market'
  listings:
    - id: starter-food
      name: Starter Food Bundle
      description: 16 cooked beef for new adventures.
      price: 125.0
      currency: coins
      icon-url: ''
      purchase-hint: 'Use /shop in game'
```

## Sources

- `CONFIG` reads `market.listings`.
- `SERVICE` requires one Bukkit service implementing `EconomyMarketProvider`.

Command names must be unique and match `[a-z0-9_-]{1,32}`. Inventory snapshots require the selected player to be online. Leave linked accounts, ephemeral responses, hidden lore and disabled staff lookup at their secure defaults unless the server has a clear reason to change them.

Exact default: [`/default-configs/modules/economy-market.yml`](/default-configs/modules/economy-market.yml)
