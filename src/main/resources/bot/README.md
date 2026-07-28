# CoreDSC Python Extensions

Python 3.8 or newer is required. The worker uses only the Python standard library.

## Minimal command

```python
from coredsc_api import command

@command("hello", "Say hello", platforms=("MINECRAFT", "DISCORD"))
def hello(ctx):
    ctx.reply(f"Hello from {ctx.platform}!")
```

## Minimal custom event

```python
from coredsc_api import event

@event("bossfight_started")
def bossfight_started(ctx):
    boss = ctx.event.data.get("boss", "Unknown boss")
    ctx.discord.send(
        "123456789012345678",
        f"Boss fight started: **{boss}**",
        durable=True,
    )
```

Publish it from console or another plugin command action:

```text
coredsc emit bossfight_started boss=Infernal_Golem
```

## Other-plugin events

Use `integrations.bukkit-events.registrations` in `config.yml` to map a selected Bukkit event class and selected property paths to a Python event. Incorrect or unavailable integrations are logged and skipped.

The public Java API also exposes `publishPythonEvent` through Bukkit's `ServicesManager`.

## Context

Common fields:

```text
ctx.platform
ctx.server
ctx.player
ctx.discord_user
ctx.link
ctx.args
ctx.options
ctx.event.name
ctx.event.source
ctx.event.data
ctx.plugins
```

## Actions

```python
ctx.reply("message")
ctx.log("message", level="INFO")
ctx.discord.send(channel_id, message, durable=False, dedupe_key="")
ctx.discord.add_role(role_id, user_id="")
ctx.discord.remove_role(role_id, user_id="")
ctx.minecraft.broadcast(message)
ctx.minecraft.send(message, player_uuid="")
ctx.minecraft.console(command_line)
ctx.ticket.create(reason, message, player_uuid="")
ctx.report.create(target, reason, message="", reporter_uuid="")
```

Console actions are disabled by default and must match an explicit prefix allowlist.

## Important security boundary

Scripts are administrator-trusted code. The separate process and validated action protocol are not a hostile-code sandbox. Review scripts before installing them and do not enable broad console access.

Full examples and integration routes are documented in the wiki.
