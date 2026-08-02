# Chat bridge

CoreDSC treats each direction independently.

## Minecraft → Discord

```yaml
minecraft-to-discord:
  channel-id: '123456789012345678'
  format: '**%displayname%**: %message%'
```

Player webhooks can display a player-specific username and avatar. `fallback-to-bot` controls whether a failed webhook delivery may use the normal bot path where safe.

## Discord → Minecraft

```yaml
discord-to-minecraft:
  channel-id: '123456789012345678'
  allow-linked-users: true
  allow-unlinked-users: false
  ignore-bots: true
  ignore-webhooks: true
  block-everyone-mentions: true
```

Unlinked Discord accounts are denied by default; enable them only when that is an intentional server policy. Blocked roles override allowed roles. Attachments and reply context have independent limits and formats.

## Loop prevention

Do not disable `ignore-bots` or `ignore-webhooks` unless you have tested the entire message path. A webhook or second bridge can otherwise feed CoreDSC's own message back into Minecraft.

## Tests

```text
/coredsc doctor test chat
/coredsc queue status
```
