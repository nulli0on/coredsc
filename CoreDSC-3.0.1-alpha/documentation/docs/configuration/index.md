# Configuration

CoreDSC separates global settings from module settings.

| Path | Purpose |
|---|---|
| `config.yml` | Discord connection, guild, command scope and startup banner |
| `messages.yml` | Shared Minecraft command messages |
| `secrets.yml` | Optional local bot-token file |
| `telemetry.yml` | CoreDSC bStats switches |
| `modules/*.yml` | Feature-specific configuration |
| `bot/config.yml` | Optional external Python worker |
| `guis/report-gui.yml` | Report administration inventory |

Exact 3.0.1-alpha defaults are available under [`/default-configs/`](/default-configs/config.yml).

## Editing rules

- Stop Paper for major edits and version upgrades.
- Use quoted strings for Discord IDs where the file already does so.
- Keep `config-version` unchanged unless the plugin itself migrates it.
- Do not delete generated metadata to force a downgrade.
- Treat unknown-key warnings as likely typos until reviewed.
- Enable one module at a time.
