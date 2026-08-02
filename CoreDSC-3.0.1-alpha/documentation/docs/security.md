# Security

## Tokens and credentials

Prefer environment variables. Never log, publish or paste bot tokens, webhook URLs, database passwords or Redis credentials. Reset a token immediately after exposure.

## Discord role hierarchy

The bot can only modify roles and members below its highest role. Grant only the permissions required by enabled modules.

## Remote console

Remote console is disabled by default. A private channel, role restriction, allowlist, rate limits, audit records and deny patterns are mandatory controls. `FULL` mode still presents substantial risk.

CoreDSC local-administration commands (`reload`, `setup`, `migrate` and `webeditor`) are blocked from the Discord remote-console bridge even when wrapped in another command.

## WebEditor

WebEditor is disabled by default, loopback-only and session-based. Start it only from the local server console; RCON is rejected. Keep the capability URL private and stop the session after saving. The URL is printed once to the server console and may remain in Paper or hosting-panel logs until those logs rotate. Use an SSH tunnel for remote administration. `secrets.yml` and arbitrary filesystem paths are not available through the editor. Public binding is intentionally unsupported. Structured edits use revision checks, contextual validation, backups and an all-files rollback transaction.

Channel dropdowns use the already connected bot and reveal channel metadata only. They do not send the bot token or an OAuth refresh token to the browser.

## Linking

Use expiring single-use codes. Enable additional membership, account-age, role or IP limits where abuse is likely. Do not run first-link rewards without persistent claim protection.

## Webhooks and message input

Keep bot/webhook loop prevention and mass-mention blocking enabled. Treat Discord message text, attachment names and workflow variables as untrusted input.

Lore personas require HTTPS image/avatar URLs and reuse only webhooks owned by the connected bot. Grant **Manage Webhooks** only in channels that need persona delivery.

## Python and workflows

Python scripts run with the permissions you give them; they are not sandboxed. Leave console actions off unless you have reviewed a small, explicit allowlist.

## Backups

Back up YAML and the SQLite database before upgrades. Keep migration snapshots until the new release completes a clean restart and functional checks.
