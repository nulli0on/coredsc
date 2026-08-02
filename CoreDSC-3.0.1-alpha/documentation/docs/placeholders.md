# Placeholders

Placeholder availability depends on the module and message context.

## Common server values

```text
%server_name%  %server_version%  %server_status%
%online_players%  %max_players%  %unique_players%
%tps%  %uptime%  %ram_used%  %ram_max%  %world_count%
```

## Minecraft chat

```text
%player%  %displayname%  %uuid%  %world%  %server%  %message%
```

## Discord → Minecraft chat

```text
%discord_user%  %discord_name%  %discord_id%  %top_role%
%minecraft_player%  %minecraft_uuid%  %linked%
%message%  %attachments%  %reply%
```

## Linking and support modules

```text
%code%  %minecraft_name%  %discord_user_id%
%ticket_id%  %report_id%  %reason%  %message%
%target_name%  %target_uuid%  %reporter_name%
```

PlaceholderAPI output is available only when the integration module and PlaceholderAPI are both present. Do not assume a placeholder can be resolved in every asynchronous Discord callback; verify the specific feature.
