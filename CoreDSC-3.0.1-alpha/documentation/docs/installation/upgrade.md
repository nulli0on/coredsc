# Upgrade CoreDSC

## Before replacing the JAR

1. Stop Paper cleanly.
2. Back up `plugins/CoreDSC/`, including the SQLite database.
3. Read the release notes and migration notes.
4. Replace the JAR.
5. Start Paper and watch the configuration-migration output.

CoreDSC 3.0.1-alpha uses schema version `5` in maintained YAML files. Missing defaults are added through a migration process that creates timestamped backups and writes files atomically.

The upgrade creates `modules/web-editor.yml`, `modules/economy-market.yml`, `modules/lore-sync.yml` and `modules/competitive.yml` when they are missing. Every new module remains disabled, so an upgrade does not expose an HTTP listener, register gameplay commands or start leaderboard tasks without an administrator opting in.

The WebEditor is now a visual Control Center rather than only a YAML text area. Its security model is unchanged: it binds to loopback, starts only from the server console, uses an expiring bearer capability and never exposes `secrets.yml`. Remote access should use an SSH tunnel.

CoreDSC emits Java 17-compatible bytecode, but Minecraft 1.21 server distributions normally require Java 21. Use the Java version required by the server distribution.

## Migration rules

- Unknown future schema versions are blocked rather than downgraded.
- Unknown keys are preserved and reported.
- A failed reload restores the previous active configuration and module set when possible.
- Visual WebEditor saves validate all affected files and replace them as one backup-backed transaction.
- An old `license.yml` is no longer active configuration. A valid old bStats preference is imported into `telemetry.yml`; a previous opt-out is not re-enabled.

## After the upgrade

Run:

```text
/coredsc doctor
/coredsc status
```

Then test chat, linking and every enabled synchronization module. On Folia, test joins, quits, deaths, PlaceholderAPI output, Discord-to-game broadcasts, inventory lookup and voice reconciliation with players in different regions. Keep the backup until the server has completed at least one clean restart.

This is an alpha release. Complete the fresh-install, prior-version upgrade, live Discord and Paper/Folia smoke matrices on a staging server before production rollout.
