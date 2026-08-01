package com.hubertstudios.coredsc.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class DiscordReadyEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String botUserId;

    public DiscordReadyEvent(String botUserId) {
        this.botUserId = botUserId;
    }

    public String botUserId() { return botUserId; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
