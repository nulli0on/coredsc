# Privacy and bStats

CoreDSC uses the official bStats Bukkit client with plugin ID `32949`.

## Aggregate data

Custom charts may report fixed categories and bounded counts such as:

- CoreDSC version and platform information;
- enabled and active modules;
- storage backend;
- broad linked-account or guild-size ranges;
- successful aggregate activity for chat, linking, delivery, support items, workflows, synchronization and WebEditor session/save use.

## Data not sent

CoreDSC custom charts do not send:

- player names, UUIDs, IP addresses or individual activity;
- Discord guild, channel, role, webhook or user IDs;
- server names, addresses or MOTDs;
- message, command, ticket, report, application or script content;
- tokens, URLs, credentials or arbitrary configuration values.

## Disable metrics

```yaml title="plugins/CoreDSC/telemetry.yml"
bstats:
  enabled: false
  feature-activity: false
```

The server-wide bStats setting also applies. An old CoreDSC bStats opt-out is preserved during migration from `license.yml`.
