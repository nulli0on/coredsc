# Smart Console

Smart Console is an incident feed, not a mirror of every log line. Its optional remote-command bridge is a separate, disabled-by-default capability.

## Incident pipeline

1. Java log records are filtered by level and administrator include/exclude patterns.
2. Secrets, Discord tokens and WebEditor capability URLs are redacted before queueing.
3. Known spam is dropped unless the same line matches a critical alert rule.
4. Informational messages are suppressed by default. Warnings, severe exceptions and alert matches become incidents.
5. A bounded queue protects memory during failure storms; overflow becomes its own diagnostic incident.
6. Equivalent records are normalized and collapsed inside the configured deduplication window.
7. Discord receives severity-colored embeds containing a compact trace and a recommended action.

Built-in remediation recognizes memory/thread exhaustion, watchdog stalls, rate limits, missing Discord permissions, locked SQLite databases, missing classes and port conflicts. The full local server log remains authoritative; the Discord embed intentionally includes only a bounded trace.

## Developer notifications

`alert-keywords` are regular expressions. A match is classified as critical, bypasses informational suppression, and can mention the exact IDs under `developer-role-ids`. Mentions are explicitly allowlisted; log text itself cannot generate a role, user, `@everyone` or `@here` ping. Each normalized incident has its own notification cooldown.

## Remote commands

- `OFF` — no Discord command execution.
- `ALLOWLIST` — only configured command roots.
- `FULL` — all commands except deny rules, and only after `confirm-full-access: true`.

All enabled modes still enforce exact Discord roles, one in-flight command, per-user cooldowns, a global per-minute limit, maximum length, multiline rejection and a persistent SQLite audit. CoreDSC local administration (`reload`, `setup`, `migrate` and `webeditor`) is hard-blocked independently of the configured deny list.

Use a private channel and prefer `ALLOWLIST`. `FULL` gives Discord staff a high-impact server capability and should be exceptional.

Configuration: [`modules/console.yml`](../configuration/modules/console.md)
