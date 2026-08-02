# Discord bot setup

## Create the application

1. Open the Discord Developer Portal.
2. Create an application.
3. Open **Bot** and create the bot user.
4. Reset and copy the token once.
5. Store the token as described in [Initial setup](./initial-setup.mdx).

## Gateway intents

Enable only what your selected modules need:

| Intent | Needed by |
|---|---|
| Message Content | Discord → Minecraft chat, ticket/report messages, remote console |
| Server Members | role sync, nickname sync, booster rewards |
| Voice States | Discord proximity rooms |
| Moderation | ban synchronization |

## Invite scopes

The invite needs:

- `bot`
- `applications.commands`

## Common Discord permissions

| Permission | Feature |
|---|---|
| View Channels / Send Messages | chat, events, queue delivery |
| Embed Links | event embeds |
| Manage Webhooks | player webhooks |
| Manage Roles | link role, LuckPerms sync, booster or workflow role actions |
| Manage Nicknames | nickname synchronization |
| Ban Members | Discord-side ban synchronization |
| Manage Channels | status and voice-room management where configured |
| Move Members | proximity voice rooms |

The bot role must be above every role or member it is expected to modify.

## Copy IDs

Enable Discord Developer Mode, then copy the guild, channel and role IDs from their context menus. A name such as `#chat` is not a stable identifier.
