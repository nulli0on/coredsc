# First chat bridge

Start with Minecraft → Discord only.

```yaml title="modules/chat-sync.yml"
enabled: true
minecraft-to-discord:
  channel-id: '123456789012345678'
  format: '**%displayname%**: %message%'
  webhook:
    enabled: false

discord-to-minecraft:
  channel-id: ''
```

Restart or reload CoreDSC, then send a normal player message in Minecraft.

When that works, enable the reverse direction:

```yaml
discord-to-minecraft:
  channel-id: '123456789012345678'
  allow-linked-users: true
  allow-unlinked-users: true
  ignore-bots: true
  ignore-webhooks: true
```

Keep `ignore-bots` and `ignore-webhooks` enabled. They prevent common bridge loops.

Run the channel test after each change:

```text
/coredsc doctor test chat
```
