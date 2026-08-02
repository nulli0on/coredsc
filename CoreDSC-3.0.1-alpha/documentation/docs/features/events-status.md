# Events and status channels

## Server events

`server-events.yml` can publish startup, shutdown, join, quit, first join, world change, death, advancement, kick, link, ticket and report events.

Rate limits and batching are built in:

```yaml
max-messages-per-minute: 60
batching:
  enabled: true
  interval-ticks: 100
```

Use the delivery queue for events that should survive a temporary Discord outage.

Startup, shutdown, join, quit and death can each use an independent visual embed profile. The WebEditor previews title, description, footer, six-digit color, thumbnail URL and image URL as they are edited. URL templates can contain event placeholders such as `%player%`; nonblank URLs must use HTTPS.

## Status channels

Status channels rename Discord channels at a controlled interval:

```yaml
channels:
- id: '123456789012345678'
  type: voice
  online-name: 'Players Online: %online_players%/%max_players%'
  offline-name: 'Server is offline'
```

Discord rate-limits channel renames. CoreDSC enforces a minimum interval. The offline name is published only during a clean shutdown.
