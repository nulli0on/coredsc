---
sidebar_position: 11
---
# `modules/console.yml`

Configures the classified Smart Console feed and optional remote execution.

```yaml title="plugins/CoreDSC/modules/console.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"
enabled: false
guild-id: ''
channel-id: ''

feed:
  enabled: true
  batch-interval-ticks: 40
  maximum-lines-per-batch: 40
  maximum-queued-lines: 500
  levels: [INFO, WARNING, SEVERE]
  include-patterns: []
  exclude-patterns:
    - '\[Console\]'
  redact-patterns:
    - '(?i)(token|password|secret|authorization)(\s*[:=]\s*)\S+'
    - '(?i)[A-Za-z0-9_-]{24,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{20,}'

smart-console:
  include-informational: false
  include-stacktrace-lines: 8
  deduplicate-window-seconds: 120
  notification-cooldown-seconds: 300
  developer-role-ids: []
  alert-keywords:
    - '(?i)outofmemoryerror|unable to create native thread'
    - '(?i)watchdog|server thread.*(?:hung|stalled)|a single server tick took'
    - '(?i)(?:server|proxy).*(?:crashed|stopping due to|emergency stop)'
  spam-patterns:
    - '(?i)^\[INFO\].*(?:joined|left) the game$'
    - '(?i)^\[INFO\].*moved too quickly'
    - '(?i)^\[INFO\].*can.t keep up'

remote:
  mode: 'OFF'
  prefix: '!console '
  role-ids: []
  cooldown-seconds: 3
  maximum-command-length: 256
  maximum-commands-per-minute: 20
  audit-retention-days: 90
  confirm-full-access: false
  allowlisted-commands: [list, say, whitelist]
  deny-patterns:
    - '(?i)^\s*(minecraft:)?(stop|restart|reload)\b'
    - '(?i)^\s*(op|deop|promote|demote)\b'
    - '(?i)^\s*(plugman|plugins?)\b'
    - '(?i)(?:^|\s)/?(?:[a-z0-9_.-]+:)?coredsc\s+(reload|setup|migrate|webeditor)\b'
    - '(?i)(token|password|secret)'
```

## Operational guidance

- Map `channel-id` to a private staff channel in WebEditor.
- Keep `include-informational: false`; add a narrow alert rule when one informational signal matters.
- Use stable numeric role IDs for notifications and remote access.
- Test every regular expression before deployment. Invalid expressions reject validation/reload with the exact path.
- Start remote execution at `OFF`, then use `ALLOWLIST` with the smallest command-root set.

Exact default: [`/default-configs/modules/console.yml`](/default-configs/modules/console.yml)
