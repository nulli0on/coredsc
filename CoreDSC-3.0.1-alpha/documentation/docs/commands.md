# Commands

## Core command

| Command | Purpose | Permission |
|---|---|---|
| `/coredsc status` | Core, storage, Discord and module state | `coredsc.status` |
| `/coredsc doctor` | Diagnostics | `coredsc.doctor` |
| `/coredsc doctor test <target>` | Safe target test | `coredsc.doctor` |
| `/coredsc setup ...` | Guided configuration helpers | `coredsc.doctor` |
| `/coredsc queue status` | Queue counts | `coredsc.queue` |
| `/coredsc queue retry` | Retry failed deliveries | `coredsc.queue` |
| `/coredsc queue clear` | Remove permanently failed deliveries | `coredsc.queue` |
| `/coredsc bot <action>` | Manage Python worker | `coredsc.bot` |
| `/coredsc emit <event> [key=value]` | Publish a Python event | `coredsc.emit` |
| `/coredsc telemetry` | Show bStats state | `coredsc.status` |
| `/coredsc migrate DiscordSRV [preview]` | Import supported DiscordSRV data | `coredsc.migrate` |
| `/coredsc webeditor <status\|start [minutes]\|stop>` | Manage a temporary local WebEditor session; server console only | `coredsc.webeditor` |
| `/coredsc reload` | Reload with rollback protection | `coredsc.reload` |

## Account commands

```text
/link
/unlink
```

Discord application commands include `/link`, `/unlink` and `/account` when the link module is ready.

## Optional module commands

```text
/ticket ...
/report ...
/case ...
/appeal ...
/apply ...
/application ...
/lore <profile> <message>
```

These commands are available only while their module is enabled and ready. Some roots and subcommand names are configurable in the module YAML.

Optional Discord application commands include `/balance`, `/inventory`, `/market`, `/elo` and `/leaderboard`. Their names are configurable and are registered only while the owning module is enabled.
