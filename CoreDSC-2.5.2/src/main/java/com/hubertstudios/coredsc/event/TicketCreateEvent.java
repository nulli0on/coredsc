package com.hubertstudios.coredsc.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class TicketCreateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final long ticketId;
    private final UUID minecraftUuid;
    private final String discordUserId;
    private final String reason;

    public TicketCreateEvent(long ticketId, UUID minecraftUuid, String discordUserId, String reason) {
        this.ticketId = ticketId;
        this.minecraftUuid = minecraftUuid;
        this.discordUserId = discordUserId;
        this.reason = reason;
    }

    public long ticketId() { return ticketId; }
    public UUID minecraftUuid() { return minecraftUuid; }
    public String discordUserId() { return discordUserId; }
    public String reason() { return reason; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
