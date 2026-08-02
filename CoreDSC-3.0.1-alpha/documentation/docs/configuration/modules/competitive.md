# `modules/competitive.yml`

Controls built-in PvP ELO or a provider-backed rating integration and its persistent Discord leaderboard.

```yaml
config-version: 5
generated-by-version: "3.0.1-alpha"
enabled: false

rating:
  source: BUILT_IN
  initial: 1000
  k-factor: 32
  provisional-k-factor: 48
  provisional-matches: 10
  minimum: 100
  track-player-deaths: true
  notify-rating-changes: false

discord:
  channel-id: ''
  elo-command: elo
  leaderboard-command: leaderboard
  responses-ephemeral: false

leaderboard:
  enabled: true
  title: '%server_name% Competitive Rankings'
  size: 10
  update-interval-seconds: 300
  minimum-matches: 1
```

## Rating sources

- `BUILT_IN` owns ratings in CoreDSC's SQLite database. Player deaths count only when a different player is the killer. Arena plugins can record authoritative results through `CoreDSCApi.recordCompetitiveResult(...)` and disable `track-player-deaths` to avoid double counting.
- `SERVICE` requires a `CompetitiveRatingProvider`; CoreDSC does not write provider-owned ratings.

`notify-rating-changes` sends winner/loser ELO feedback only after persistence succeeds. The death listener retains only UUID/name/time values; feedback is resolved by UUID and delivered on each player's Folia entity scheduler. Offline or retired players are skipped safely.

When the live leaderboard is enabled, map `discord.channel-id` in WebEditor. The update interval is `60-86400` seconds, size is `3-25`, and Discord command names must be distinct.

Exact default: [`/default-configs/modules/competitive.yml`](/default-configs/modules/competitive.yml)
