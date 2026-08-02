# WebEditor

CoreDSC 3.0 turns WebEditor into a local configuration control plane instead of a raw-YAML experiment. It solves the most error-prone setup tasks visually while keeping an advanced YAML view for settings that do not yet have a form.

## What the dashboard controls

- **Module switches** for every built-in feature. Each switch shows the module's live state and diagnostic detail.
- **One-click channel mapping** from the connected bot's real guild, text, voice and category cache. Discord IDs never need to be copied by hand.
- **Visual event embeds** for startup, shutdown, join, quit and death messages. Title, description, footer, color, thumbnail and image update a Discord-style preview immediately.
- **Advanced YAML** for every allowlisted configuration file.
- **Validate, save and apply** actions with clear validation errors and live module reload results.

Channel discovery uses the already authenticated CoreDSC bot plus the temporary local capability token. It does not request a second Discord OAuth grant, expose the bot token, or store Discord credentials in the browser.

## Safe save pipeline

Every structured save is one transaction, even when it changes several files:

1. The browser sends typed patches and the revision of each source file.
2. CoreDSC rejects unknown files and paths.
3. Revisions are compared to detect an SSH, panel or plugin edit made after the page loaded.
4. All candidate YAML documents are parsed and validated as one runtime configuration.
5. A timestamped backup is created.
6. Same-directory temporary files are flushed and moved into place.
7. If any write fails, every touched file is restored.
8. **Save & apply** performs the normal guarded module reload; a failed reload restores the previous runtime snapshot and module set.

This avoids the common partial-save failure where a module toggle is written but its required channel mapping is not.

## Security model

- Only `127.0.0.1`, `::1` or `localhost` can be configured. Public binding is rejected.
- Sessions can be created only from the local server console; player commands, command blocks and RCON are rejected.
- The URL carries a random 256-bit capability in its fragment. CoreDSC stores only its SHA-256 digest.
- API requests use the capability as a bearer credential and are rate-limited.
- Sessions expire automatically and only one is active per server.
- `secrets.yml`, databases, scripts and arbitrary filesystem paths are never exposed.
- Responses disable caching, framing and cross-origin resource use with restrictive HTTP headers.
- Discord messages never receive the session capability; Smart Console redacts capability URLs defensively.

Treat the printed URL as a temporary administrator password. Stop the session after use and remember that a hosting panel may retain console output until log rotation.

## Start a session

Run from the local server console:

```text
/coredsc setup enable web-editor
/coredsc reload
/coredsc webeditor start
```

An optional duration can be supplied:

```text
/coredsc webeditor start 10
```

Stop and invalidate it immediately with:

```text
/coredsc webeditor stop
```

## Remote hosts

Use an SSH tunnel; do not expose the listener through a panel proxy:

```bash
ssh -L 8765:127.0.0.1:8765 user@example-server
```

Keep the SSH connection open and browse to the complete loopback URL printed by CoreDSC. If the configured port differs, use that port on both sides.

Configuration: [`modules/web-editor.yml`](../configuration/modules/web-editor.md)
