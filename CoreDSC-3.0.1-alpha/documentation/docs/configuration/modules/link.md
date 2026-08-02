---
sidebar_position: 2
---
# `modules/link.yml`

Controls link-code generation, security checks and optional required-link enforcement.

See [Account linking](/features/linking) for setup and usage.

## Before enabling

- Confirm `/link` and the Discord `/link` command work before enforcing linking.
- Keep an operator bypass permission.
- Choose an expiry and code length appropriate for your community.

## Link codes

Security checks can require guild membership, account age, a Discord role or an IP-based daily limit. Required linking includes a grace period, reminders and a command allowlist so players can still complete the link flow.

## Default configuration

```yaml title="plugins/CoreDSC/modules/link.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: link
# Set enabled to false to keep this module completely inactive.
enabled: true
code-expiry-seconds: 300
browser-url: ''
code-length: 8
security:
  require-discord-server-membership: false
  minimum-discord-account-age-days: 0
  required-discord-role-id: ''
  maximum-links-per-ip-per-day: 0
required:
  enabled: false
  grace-period-seconds: 300
  reminder-interval-seconds: 60
  bypass-permission: coredsc.link.bypass
  message: '&cYou must link your Discord account. Use &e/link&c. Grace remaining: &e%seconds%s'
  allowed-commands:
    - link
```

[Download this default file](/default-configs/modules/link.yml).
