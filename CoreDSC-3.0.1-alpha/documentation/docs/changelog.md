# Changelog

## 3.0.1-alpha

- Added a Paper/Folia scheduler family with explicit global, entity, location and asynchronous ownership boundaries, plus a Spigot compatibility fallback.
- Added UUID-based player re-entry and primitive-only death-event snapshots.
- Added a bounded single-owner SQLite funnel with verified WAL/NORMAL settings, load shedding, queue health diagnostics and ordered checkpoint shutdown.
- Declared Folia support and moved player inventory, location, permission, placeholder and messaging access onto entity-owned tasks.
- Rebuilt WebEditor as a production-oriented Control Center with module switches, live Discord channel dropdowns and a visual lifecycle-embed builder.
- Added draggable embed colors, live thumbnail/image previews, event placeholders and save/apply separation.
- Added authenticated guild/channel discovery from the connected Discord bot; channel IDs no longer need to be copied manually.
- Added contextual multi-file validation, optimistic revision conflicts, timestamped backups, atomic transaction replacement and rollback.
- Excluded `secrets.yml`, scripts, databases and arbitrary paths from WebEditor.
- Added the optional Vault Economy & Market Terminal with linked-account privacy, ephemeral replies, entity-safe inventory capture and a public shop-provider interface.
- Added Lore Sync with configurable NPC personas, reused managed webhooks, safe bot fallback, HTTPS media validation, cooldowns and a public trigger API.
- Added built-in transactional PvP ELO, a custom-rating provider interface and a persisted, in-place Discord leaderboard.
- Replaced the console log dump with bounded Smart Console classification, spam suppression, incident deduplication, red severity embeds and keyword-gated developer role notifications.
- Hard-blocked local CoreDSC administration commands from the Discord remote-console bridge.
- Added built-in redaction for WebEditor capability URLs in the Discord console feed.
- Preserved file permissions and forced temporary configuration writes to disk before replacement.
- Added configuration schema 5 and SQLite schema 11 migrations.
- Added JUnit coverage for ELO symmetry, upset weighting, provisional K-factor behavior, rating-floor clamping and policy validation.
- Fixed the bundled Python README filename mismatch that could stop fresh configuration initialization.

## 2.5.2

- Added unknown/deprecated configuration-key warnings with conservative typo suggestions.
- Added a compact startup action summary for unresolved problems.
- Added per-module last-success, successful-action count and last-failure diagnostics.
- Routed CoreDSC-owned scheduling through one Paper scheduler adapter.
- Improved reload rollback state handling.

## 2.5.1

- Persisted prior Discord command scopes for stale-command cleanup after restart.
- Added transactional SQLite migration handling and pre-migration snapshots.
- Stopped migrations on ambiguous duplicate account-link data instead of deleting records.
- Preserved legacy bStats opt-outs.
- Tightened command cleanup and listener-registration state.

## 2.5.0

- Added configuration schema migration and backups.
- Removed silent guild-to-global command fallback.
- Redesigned Doctor state reporting.
- Separated telemetry from obsolete license configuration.
- Changed the optional Python worker to manual startup by default.

Read the release archive's `CHANGELOG.md`, `UPGRADE-NOTES.md` and audit reports for detailed engineering notes.
