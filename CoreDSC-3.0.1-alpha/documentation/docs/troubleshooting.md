# Troubleshooting

## Bot is offline

- Confirm `discord.enabled: true`.
- Verify the token source and variable name.
- Restart the Java process after changing environment variables.
- Check outbound HTTPS/WebSocket access.
- Read the first Discord startup exception.

## Bot is online but the guild is unavailable

- Verify the numeric `discord.guild-id`.
- Confirm the bot is a member of that guild.
- Wait for JDA readiness and check `/coredsc doctor`.
- Do not switch to global commands as a workaround for a wrong guild ID.

## Slash commands are missing

- Use `command-registration: guild` during setup.
- Invite the bot with `applications.commands`.
- Confirm command registration reports `READY`.
- If the guild changed, check whether old command cleanup failed.

## Chat does not work

```text
/coredsc doctor test chat
/coredsc queue status
```

Check channel ID, permissions, enabled direction, bot/webhook filters and other chat plugins.

## Module is `FAILED`

Run `/coredsc doctor` and inspect the module's last failure. Common causes are invalid IDs, missing optional dependencies, permission hierarchy, invalid YAML and disabled Discord intents.

## Configuration warning

CoreDSC preserves unknown keys and suggests likely typos. Correct the key manually; do not delete the entire file to suppress the warning.

## Queue keeps growing

Pending growth usually indicates a Discord outage or rate limit. Failed growth usually indicates a deleted channel or missing permission. Repair the destination before retrying.

## Python will not start

- Run `python3 --version`.
- Set an absolute executable path if auto-detection fails.
- Check `/coredsc bot status`.
- Verify worker/runtime files and script imports.
- Remember that normal CoreDSC operation does not require Python.

## Collecting a report

Include the first complete exception, `/coredsc status`, `/coredsc doctor`, versions and redacted relevant YAML. Never include tokens, passwords, webhook URLs, Redis credentials or private ticket/report content.
