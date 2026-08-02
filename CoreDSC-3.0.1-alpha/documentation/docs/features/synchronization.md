# Roles, nicknames and bans

These modules depend on a valid account link.

## LuckPerms synchronization

Each mapping specifies a LuckPerms group, Discord role and direction:

```yaml
mappings:
- enabled: true
  group: vip
  role-id: '123456789012345678'
  direction: minecraft-to-discord
```

Review every removal switch before enabling two-way synchronization. The wrong authority or role hierarchy can remove access unexpectedly.

## Nickname synchronization

CoreDSC can set a linked Discord member's nickname from the Minecraft name and restore the previous nickname when configured. The bot requires **Manage Nicknames** and must be above the member.

## Ban synchronization

Ban synchronization is conservative and uses linked identities. Configure the guild and reasons, grant **Ban Members**, and test with non-staff accounts. CoreDSC should not claim ownership of bans it did not create.

## Booster rewards

Booster rewards reconcile linked Discord boosters on a persistent period. The configured role and reward commands must be valid before the module is enabled.
