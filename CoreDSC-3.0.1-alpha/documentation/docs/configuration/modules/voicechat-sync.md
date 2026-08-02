---
sidebar_position: 22
---
# `modules/voicechat-sync.yml`

Creates temporary Discord proximity rooms and moves linked members according to Minecraft positions.

See [Voice rooms](/features/voice) for setup and usage.

## Before enabling

- Enable Voice States.
- Grant Manage Channels and Move Members.
- Configure a category and lobby.
- Keep both audio directions disabled in 3.0.1-alpha.

## Room movement

CoreDSC 3.0.1-alpha manages Discord rooms and member movement. Audio bridging to Simple Voice Chat is not included, so audio options set to `true` are refused.

## Default configuration

```yaml title="plugins/CoreDSC/modules/voicechat-sync.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# Position-based Discord proximity rooms.
# CoreDSC 3.0.1-alpha supports Discord-only room creation and member movement.
# Audio bridging to Simple Voice Chat is not included in 3.0.1-alpha.
enabled: false

discord:
  # 0 uses discord.guild-id from config.yml.
  guild-id: 0
  # Temporary rooms are created inside this category.
  category-id: ''
  # Linked players join this channel to enter the proximity system.
  lobby-channel-id: ''

proximity:
  horizontal-distance: 48.0
  vertical-distance: 24.0
  # Players already sharing a room may move this many extra blocks before a split.
  falloff: 6.0
  minimum-players: 2
  update-ticks: 10

rooms:
  maximum-active-rooms: 12
  name-prefix: coredsc-proximity
  channels-visible: true
  # Uses discord.link-role-id as a guest role when it is configured.
  allow-linked-guests: true
  # When false, only the link role and configured guest roles can join manually.
  allow-unlinked-guests: false
  guest-role-ids: []
  # Creates a short-lived solo room when a Simple Voice Chat microphone becomes active.
  create-solo-on-speech: false
  speech-activity-seconds: 4
  close-grace-seconds: 20
  cleanup-managed-channels-on-startup: true

relay-bots:
  # Reserved for a future DAVE-enabled audio implementation. Ignored in 3.0.1-alpha.
  # Never store bot tokens directly in this file.
  token-environment-variables: []

audio:
  # Keep both directions false in 3.0.1-alpha.
  # and reported by the startup log and /coredsc doctor.
  minecraft-to-discord:
    enabled: false
    gain: 1.0
  discord-to-minecraft:
    enabled: false
    gain: 1.0
  max-buffered-frames: 100
  max-mixed-sources-per-frame: 32

security:
  require-linked-accounts: true
  # Allows unlinked nearby Minecraft users to participate through Simple Voice Chat.
  include-unlinked-minecraft-players: false
  # A linked player already in the room must deafen Discord before their Minecraft mic is relayed.
  require-discord-deafened-for-minecraft-relay: true
  opt-out-permission: coredsc.voice.optout
```

[Download this default file](/default-configs/modules/voicechat-sync.yml).
