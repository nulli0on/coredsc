---
sidebar_position: 13
---
# `modules/reports.yml`

Creates player reports with limits, optional chat context, Discord threads and a staff GUI.

See [Player reports](/features/community) for setup and usage.

## Before enabling

- Configure the parent channel and staff roles.
- Decide whether storing chat history is appropriate.
- Review cooldowns and open-report limits.
- Test the GUI with the configured inventory size.

## Stored report data

Report content is persisted. The opening message supports report, reporter, target and context placeholders. Chat-history capture should be bounded and documented in the server privacy policy.

## Default configuration

```yaml title="plugins/CoreDSC/modules/reports.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: reports
# Set enabled to false to keep this module completely inactive.
enabled: false
parent-channel-id: ''
private-thread: true
require-linked-account: true
target-must-be-online: true
allow-self-report: false
cooldown-seconds: 120
duplicate-window-seconds: 60
max-open-per-user: 5
max-open-global: 500
reason-max-length: 100
message-max-length: 1000
include-context: true
staff-role-ids: []
commands:
  root: report
  aliases:
  - reports
  keep-default-report-command: true
  description: Report a Minecraft player
  discord-description: View and manage Minecraft reports
  permissions:
    user: coredsc.report
    admin: coredsc.report.admin
  subcommands:
    create: create
    status: status
    reply: reply
    close: close
    admin: admin
    claim: claim
    priority: priority
  usage:
    create: /%command% [%create%] <player> <reason> [message]
    reply: /%command% %reply% <id> <message>
    close: /%command% %close% <id>
messages:
  help:
  - '&e/%command% [%create%] <player> <reason> [message]'
  - '&e/%command% %status%'
  - '&e/%command% %reply% <id> <message>'
  - '&e/%command% %close% <id>'
  players-only: '&cOnly players can use reports.'
  no-permission: '&cYou do not have permission.'
  no-admin-permission: '&cYou cannot manage reports.'
  command-disabled: '&cThis command is disabled. Use /%command%.'
  gui-disabled: '&cThe report GUI is disabled.'
  gui-load-failed: '&cCould not load reports.'
  target-must-be-online: '&cThat player must be online.'
  unknown-player: '&cUnknown player.'
  invalid-id: '&cInvalid report ID.'
  creation-failed: '&cReport creation failed.'
  created: '&a%message%'
  creation-rejected: '&c%message%'
  report-not-found: '&cReport not found.'
  claimed: '&aReport claimed.'
  claim-failed: '&cCould not claim report.'
  closed: '&aReport closed.'
  close-failed: '&cCould not close report.'
chat-history:
  enabled: false
  lines: 5
thread-name: report-%id%-%target_name%
opening-message: |-
  **Report #%id%**
  Reporter: `%reporter_name%` (<@%reporter_discord_id%>)
  Target: `%target_name%` (`%target_uuid%`)
  Reason: **%reason%**

  %message%

  %context%
  %chat_history%
```

[Download this default file](/default-configs/modules/reports.yml).
