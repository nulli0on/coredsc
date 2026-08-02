# Module files

Every module has its own YAML file. `enabled: false` keeps the module inactive.

| File | Purpose | Default |
|---|---|---|
| `chat-sync.yml` | Two-way chat and webhooks | Enabled |
| `delivery-queue.yml` | Persistent Discord retries | Enabled |
| `link.yml` | Account-link codes and required linking | Enabled |
| `status-channels.yml` | Discord channel names with server values | Enabled |
| `link-rewards.yml` | One-time commands after first link | Disabled |
| `luckperms-sync.yml` | LuckPerms group ↔ Discord role mappings | Disabled |
| `nickname-sync.yml` | Minecraft name → Discord nickname | Disabled |
| `ban-sync.yml` | Linked-account ban synchronization | Disabled |
| `booster-rewards.yml` | Scheduled booster rewards | Disabled |
| `server-events.yml` | Startup, join, death and other event messages | Disabled |
| `console.yml` | Console feed and restricted remote commands | Disabled |
| `tickets.yml` | Linked support tickets | Disabled |
| `reports.yml` | Player reports and staff thread/GUI | Disabled |
| `cases.yml` | Moderation cases and appeals | Disabled |
| `applications.yml` | Question-based applications | Disabled |
| `custom-commands.yml` | YAML-defined Minecraft/Discord commands | Disabled |
| `workflows.yml` | Trigger-condition-action automation | Disabled |
| `moderation-bridge.yml` | Observe moderation commands and publish audit messages | Disabled |
| `network.yml` | Local or Redis cross-server event bus | Disabled |
| `authme.yml` | Linked-account AuthMe reset flow | Disabled |
| `placeholderapi.yml` | PlaceholderAPI integration | Disabled |
| `voicechat-sync.yml` | Discord proximity rooms | Disabled |
| `economy-market.yml` | Vault balance, inventory and market terminal | Disabled |
| `lore-sync.yml` | Cinematic NPC webhook profiles | Disabled |
| `competitive.yml` | Built-in/provider ELO and live rankings | Disabled |
| `web-editor.yml` | Visual, loopback-only configuration control plane | Disabled |

The exact files are available in [`/default-configs/modules/`](/default-configs/modules/chat-sync.yml).
