---
sidebar_position: 15
---
# `modules/applications.yml`

Collects question-based applications and sends them to Discord for staff review.

See [Applications](/features/community) for setup and usage.

## Before enabling

- Configure the parent Discord channel and staff roles.
- Test the full question and decision flow before adding acceptance actions.
- Keep console actions disabled unless every permitted command prefix is deliberate.

## Review actions

`parent-channel-id` selects the thread parent. `questions` defines stable IDs, prompts and length limits. `accept-actions` and `reject-actions` run only after a staff decision. Whitelist, LuckPerms and Discord-role actions should be enabled independently so a failure is easy to identify.

## Default configuration

```yaml title="plugins/CoreDSC/modules/applications.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: applications
# Set enabled to false to keep this module completely inactive.
enabled: false
parent-channel-id: ''
private-thread: true
require-linked-account: true
staff-role-ids: []
allow-console-actions: false
console-command-allow-prefixes:
- whitelist add
questions:
- id: age
  question: How old are you?
  required: true
  min-length: 1
  max-length: 30
- id: motivation
  question: Why do you want to join the server?
  required: true
  min-length: 20
  max-length: 800
- id: experience
  question: Tell us about your Minecraft experience.
  required: true
  min-length: 10
  max-length: 800
accept-actions:
  whitelist: true
  luckperms-group: ''
  discord-role-id: ''
  console-commands: []
reject-actions:
  console-commands: []
```

[Download this default file](/default-configs/modules/applications.yml).
