package com.hubertstudios.coredsc.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class AccountLinkedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID minecraftUuid;
    private final String minecraftName;
    private final String discordUserId;

    public AccountLinkedEvent(UUID minecraftUuid, String minecraftName, String discordUserId) {
        this.minecraftUuid = minecraftUuid;
        this.minecraftName = minecraftName;
        this.discordUserId = discordUserId;
    }

    public UUID minecraftUuid() { return minecraftUuid; }
    public String minecraftName() { return minecraftName; }
    public String discordUserId() { return discordUserId; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
