# `modules/lore-sync.yml`

Defines cinematic Discord NPC personas triggered by `/lore` or the Java API.

```yaml
config-version: 5
generated-by-version: "3.0.1-alpha"
enabled: false
default-channel-id: ''

webhooks:
  auto-create: true
  fallback-to-bot: true
  name-prefix: 'CoreDSC NPC'

security:
  permission: coredsc.lore.trigger
  cooldown-seconds: 2
  maximum-message-length: 1500
  allow-console: true

profiles:
  - id: herald
    enabled: true
    channel-id: ''
    display-name: The Herald
    avatar-url: ''
    color: '#9B59B6'
    title: 'A proclamation echoes across %server_name%'
    description: '%message%'
    thumbnail-url: ''
    image-url: ''
    footer: 'Triggered by %actor%'
```

At least one enabled profile is required. Each profile needs its own channel ID or a valid `default-channel-id`. Profile IDs match `[a-z0-9_-]{1,40}`, colors use six-digit hex, and all nonblank image/avatar URLs must use HTTPS.

The bot needs **View Channel**, **Send Messages** and **Embed Links**. Managed webhook personas additionally need **Manage Webhooks**; otherwise enable bot fallback or create the expected webhook manually.

Exact default: [`/default-configs/modules/lore-sync.yml`](/default-configs/modules/lore-sync.yml)
