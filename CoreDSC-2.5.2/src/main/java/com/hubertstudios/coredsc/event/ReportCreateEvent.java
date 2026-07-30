package com.hubertstudios.coredsc.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class ReportCreateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final long reportId;
    private final UUID reporterUuid;
    private final UUID targetUuid;
    private final String reason;

    public ReportCreateEvent(long reportId, UUID reporterUuid, UUID targetUuid, String reason) {
        this.reportId = reportId;
        this.reporterUuid = reporterUuid;
        this.targetUuid = targetUuid;
        this.reason = reason;
    }

    public long reportId() { return reportId; }
    public UUID reporterUuid() { return reporterUuid; }
    public UUID targetUuid() { return targetUuid; }
    public String reason() { return reason; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
