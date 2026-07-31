from coredsc_api import command


@command(
    name="pyhello",
    description="Example command provided by a CoreDSC Python script",
    platforms=("MINECRAFT", "DISCORD"),
    cooldown_seconds=3,
)
def hello(ctx):
    name = ctx.player.name or ctx.discord_user.name or "there"
    ctx.reply(f"Hello {name}! This response came from Python.")
