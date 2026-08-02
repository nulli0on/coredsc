---
sidebar_position: 1
---
# `modules/chat-sync.yml`

Controls Minecraft → Discord chat, Discord → Minecraft chat, webhooks, attachments, replies and canned responses.

See [Chat synchronization](/features/chat) for setup and usage.

## Before enabling

- Grant View Channel and Send Messages.
- Enable Message Content for Discord → Minecraft.
- Grant Manage Webhooks only when player webhooks are enabled.
- Keep bot and webhook loop filters enabled.

## Message flow

The two directions are independent. A blank channel ID disables only that direction. Linked and unlinked Discord users have separate policy switches. Blocked roles override allowed roles. Webhook fallback exists for recoverable webhook failures, but CoreDSC avoids blind resends where duplicate delivery is possible.

## Default configuration

```yaml title="plugins/CoreDSC/modules/chat-sync.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: chat-sync
# Bidirectional Minecraft <-> Discord chat.
enabled: true

minecraft-to-discord:
  channel-id: ''
  # Used when webhook.enabled is false or a webhook fallback is required.
  format: '**%displayname%**: %message%'
  webhook:
    # Discord renders the configured username in bold and uses the avatar URL.
    # This produces messages such as: [player face] **Steve**: Hi
    enabled: false
    auto-create: true
    fallback-to-bot: true
    name: CoreDSC Chat
    username-format: '%displayname%'
    # Leave blank to use the webhook's normal avatar and avoid an avatar provider.
    avatar-url: 'https://mc-heads.net/avatar/%uuid%/128'
    message-format: '%message%'

# Reverse chat: Discord messages are shown to online Minecraft players.
discord-to-minecraft:
  channel-id: ''

  # These two independent switches implement linked/unlinked chat policy.
  # Unlinked Discord accounts are denied by default.
  allow-linked-users: true
  allow-unlinked-users: false

  # Backwards-compatible generic format. The two policy-specific formats take
  # priority and fall back to this value when absent in an older configuration.
  format: '&9[Discord] &f%user%: %message%%attachments%%reply%'
  linked-format: '&9[Discord] &b%minecraft_player% &8(%discord_user%)&f: %message%%attachments%%reply%'
  unlinked-format: '&9[Discord] &f%discord_user%: %message%%attachments%%reply%'

  ignore-bots: true
  ignore-webhooks: true
  block-everyone-mentions: true

  # Optional Discord role policy. An empty allowed list accepts every role.
  # A blocked role always wins over an allowed role.
  allowed-role-ids: []
  blocked-role-ids: []

  attachments:
    enabled: true
    maximum: 3
    format: ' &7[%name%: %url%]'
  replies:
    enabled: true
    format: ' &8(reply to %reply_user%: %reply_message%)'
  denied-notice:
    enabled: false
    message: "Your message was not forwarded to Minecraft by this server's chat policy."

# Reverse-chat placeholders:
# %discord_user%, %discord_name%, %discord_id%, %top_role%,
# %minecraft_player%, %minecraft_uuid%, %linked%, %message%, %attachments%, %reply%
# Minecraft-to-Discord placeholders:
# %player%, %displayname%, %uuid%, %world%, %server%, %message%

# Ordered automatic Discord replies. match: EXACT, PREFIX, CONTAINS or REGEX.
canned-responses: []
# Example:
#  - id: ip
#    trigger: '!ip'
#    match: EXACT
#    response: 'play.example.net'
#    cooldown-seconds: 30
#    stop-forwarding: true
```

[Download this default file](/default-configs/modules/chat-sync.yml).
