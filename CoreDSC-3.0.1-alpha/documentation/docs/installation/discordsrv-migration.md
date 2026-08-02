# Migrate from DiscordSRV

CoreDSC does not modify or disable DiscordSRV automatically.

## Preview

```text
/coredsc migrate DiscordSRV preview
```

Review the proposed configuration and linked-account changes. Existing CoreDSC values take priority, and conflicting linked accounts are skipped.

## Apply

After backing up both plugin folders:

```text
/coredsc migrate DiscordSRV
```

Restart Paper, then verify chat, linking and role mappings before removing DiscordSRV.

## Cutover order

1. Stop both plugins from sending to the same channel.
2. Verify CoreDSC in a separate test channel.
3. Migrate data.
4. Disable DiscordSRV.
5. Restart and test CoreDSC alone.
6. Keep the DiscordSRV folder and backup until the cutover is proven.

The importer covers supported settings and linked-account data. Review anything it marks as unsupported or ambiguous before removing DiscordSRV.
