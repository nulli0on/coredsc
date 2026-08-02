# `bot/config.yml`

Configuration for the optional external Python worker. CoreDSC does not require Python when this feature is disabled.

## Before enabling

- Install Python 3.8 or newer.
- Keep `auto-start: false` until a manual start succeeds.
- Review every permitted action and environment setting.
- Treat all installed scripts as trusted administrator code.

## Default configuration

```yaml title="plugins/CoreDSC/bot/config.yml"
# Optional Python developer feature. CoreDSC works normally when this is disabled.
config-version: 5
generated-by-version: "3.0.1-alpha"

enabled: false
# Keep the module loaded without spawning Python until /coredsc bot start.
auto-start: false
engine: EXTERNAL_CPYTHON
python-executable: auto
startup-timeout-seconds: 10
request-timeout-milliseconds: 2000
shutdown-timeout-seconds: 5
maximum-queue-size: 100
maximum-actions-per-execution: 10
maximum-restarts-per-hour: 3
security:
  inherit-sensitive-environment: false
  allow-console-commands: false
  console-command-allow-prefixes: []
  allow-discord-role-actions: true
  allow-ticket-actions: true
  allow-report-actions: true
limits:
  maximum-registered-commands: 50
  maximum-message-length: 1900
  maximum-executions-per-second: 20

# Optional adapters for events exposed by other Bukkit/Paper plugins.
# CoreDSC invokes only the public, no-argument property paths explicitly listed
# under fields. Missing plugins/classes are logged and skipped without breaking
# the Python module.
integrations:
  bukkit-events:
    enabled: true
    registrations: []

# Example for NuVotifier (verify the event class/property names against the
# exact plugin version installed on your server):
#    - id: vote
#      enabled: true
#      plugin: NuVotifier
#      event-class: com.vexsoftware.votifier.model.VotifierEvent
#      python-event: vote_received
#      priority: MONITOR
#      ignore-cancelled: true
#      include-metadata: true
#      fields:
#        username: vote.username
#        service: vote.serviceName
```

[Download this default file](/default-configs/bot-config.yml).
