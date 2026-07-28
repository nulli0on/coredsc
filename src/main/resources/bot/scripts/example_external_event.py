"""Example event published by another plugin, an event adapter, or /coredsc emit."""
from coredsc_api import event


@event("bossfight_started")
def bossfight_started(ctx):
    boss = ctx.event.data.get("boss", "Unknown boss")
    ctx.log(f"Boss fight event received for {boss}")
    # Replace the channel ID before enabling the next line.
    # ctx.discord.send("123456789012345678", f"Boss fight started: **{boss}**", durable=True)
