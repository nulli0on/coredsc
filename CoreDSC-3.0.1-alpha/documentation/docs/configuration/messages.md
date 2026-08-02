# `messages.yml`

Shared command messages live here:

```yaml
prefix: "&8[&bCoreDSC&8] "
no-permission: "%prefix%&cYou do not have permission."
player-only: "%prefix%&cOnly players can use this command."
reload-success: "%prefix%&aCoreDSC configuration reloaded."
reload-failed: "%prefix%&cReload failed; the previous valid configuration is still active."
```

Module-specific wording remains in the module file. For example, ticket command messages are stored in `modules/tickets.yml`.

Legacy `&` color codes are translated for Minecraft output. Discord messages use Discord Markdown and should not be written as Minecraft color strings.

[Download the exact default file](/default-configs/messages.yml).
