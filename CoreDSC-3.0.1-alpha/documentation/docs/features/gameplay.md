# Economy, lore and competitive play

CoreDSC's gameplay modules are independent. Enable only the ones that fit the server; none are required by chat, linking or moderation.

## Economy & Market Terminal

The economy terminal discovers the active Vault economy provider without making Vault a hard startup dependency. Discord users can run configurable commands (defaults: `/balance`, `/inventory`, `/market`).

- Balance and inventory default to the user's linked Minecraft account.
- Replies default to ephemeral so personal wealth and inventory are not exposed publicly.
- Inventory contents are captured on the player's entity scheduler and are available only while that player is online.
- Item lore is hidden unless explicitly enabled.
- Named-player staff lookup is disabled separately and requires Discord **Manage Server** when enabled.
- Market listings can come from YAML or an `EconomyMarketProvider` registered by a shop adapter.

The terminal is read-only. A listing's `purchase-hint` directs players to the server's existing shop flow; CoreDSC does not bypass that shop's authorization or transaction logic.

## Lore & SMP Storyline Sync

Lore Sync turns configured personas into reusable Discord webhooks. An operator can run:

```text
/lore herald The northern gate has fallen. Rally at the citadel!
```

Each profile controls channel, display name, avatar, color, title, description, thumbnail, image and footer. `%message%`, `%actor%`, `%profile%` and `%server_name%` are available. Webhooks are reused instead of created for every event, mass mentions are neutralized, URL fields require HTTPS, and cooldown/length/permission controls are enforced.

If webhook creation is unavailable, `fallback-to-bot` can publish the same cinematic embed through the bot. Grant **Manage Webhooks** only when managed personas are desired.

Other plugins can trigger the same pipeline through `CoreDSCApi.triggerLoreEvent(...)`.

## Competitive ELO & live leaderboard

`BUILT_IN` mode records valid player-versus-player deaths with a transactionally updated SQLite ELO table. It supports separate provisional and established K-factors, a rating floor, wins/losses, kills/deaths and match counts.

`SERVICE` mode delegates ratings to an arena or minigame plugin implementing `CompetitiveRatingProvider`. This lets the game-owning plugin remain authoritative while CoreDSC handles Discord presentation.

The dedicated leaderboard message is persisted by channel ID and edited in place. If an administrator deletes it, CoreDSC detects Discord's unknown-message response and creates one replacement. Updates are coalesced so a burst of matches never creates concurrent leaderboard writes. The embed uses medal ranks and proportional bar graphics, and `/elo` plus `/leaderboard` provide on-demand views.

Configuration:

- [`economy-market.yml`](../configuration/modules/economy-market.md)
- [`lore-sync.yml`](../configuration/modules/lore-sync.md)
- [`competitive.yml`](../configuration/modules/competitive.md)
