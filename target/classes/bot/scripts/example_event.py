from coredsc_api import event


@event("account_linked")
def linked(ctx):
    # This only writes to the server console. Replace it with your own actions.
    ctx.log(f"Linked account event received for {ctx.event.minecraft_name}")
