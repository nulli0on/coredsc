---
sidebar_position: 17
---
# `modules/workflows.yml`

Runs fixed actions in response to configured triggers and conditions.

See [Workflows](/features/automation) for setup and usage.

## Before enabling

- Start with one trigger and one harmless action.
- Keep console actions disabled until required.
- Avoid combining unrelated administrative actions in one workflow.

## Triggers and actions

Workflows are predictable only when each action has clear failure behavior. External Discord, permissions and server-command actions can fail independently.

## Default configuration

```yaml title="plugins/CoreDSC/modules/workflows.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: workflows
# Set enabled to false to keep this module completely inactive.
enabled: false
allow-console-actions: false
console-command-allow-prefixes:
- 'say '
- 'whitelist add '
definitions:
- enabled: false
  id: linked-welcome
  trigger: ACCOUNT_LINKED
  conditions: {}
  actions:
  - type: SEND_DISCORD_MESSAGE
    channel-id: ''
    message: Welcome linked player **%minecraft_name%**!
  - type: SEND_PLAYER_MESSAGE
    message: '&aYour Discord account is linked.'
- enabled: false
  id: first-join-linked-role
  trigger: PLAYER_FIRST_JOIN
  conditions:
    first-join: 'true'
  actions:
  - type: ADD_DISCORD_ROLE
    role-id: '0'
- enabled: false
  id: scheduled-announcement
  trigger: SCHEDULE
  interval-seconds: 3600
  conditions: {}
  actions:
  - type: SEND_DISCORD_MESSAGE
    channel-id: ''
    message: The server currently has %online_players% players online.
```

[Download this default file](/default-configs/modules/workflows.yml).
