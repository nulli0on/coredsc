---
sidebar_position: 20
---
# `modules/authme.yml`

Allows a linked Discord account to request a controlled temporary AuthMe password reset.

See [AuthMe integration](/features/linking) for setup and usage.

## Before enabling

- AuthMe must be installed and detected.
- Keep the player-offline requirement enabled unless there is a specific reason not to.
- Treat reset actions as security-sensitive and retain the audit log.

## Reset flow

The cooldown limits repeated resets. Temporary passwords are generated to the configured length. CoreDSC should never expose existing AuthMe passwords.

## Default configuration

```yaml title="plugins/CoreDSC/modules/authme.yml"
config-version: 5
generated-by-version: "3.0.1-alpha"

# CoreDSC module: authme
# Set enabled to false to keep this module completely inactive.
enabled: false
reset-cooldown-hours: 24
only-when-player-offline: true
temporary-password-length: 12
audit-log: true
```

[Download this default file](/default-configs/modules/authme.yml).
