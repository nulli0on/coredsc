package com.hubertstudios.coredsc.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class TicketCloseEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final long ticketId;
    private final String closedBy;

    public TicketCloseEvent(long ticketId, String closedBy) {
        this.ticketId = ticketId;
        this.closedBy = closedBy;
    }

    public long ticketId() { return ticketId; }
    public String closedBy() { return closedBy; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
