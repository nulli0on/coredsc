package com.hubertstudios.coredsc.api;

import org.bukkit.OfflinePlayer;

import java.util.UUID;

/** Public adapter service for confirmed or observed moderation actions. */
public interface ModerationAuditService {
    record ModerationAction(
            String action,
            String executor,
            String target,
            String targetUuid,
            String reason,
            String duration,
            String source,
            String externalId,
            String rawCommand,
            boolean confirmed,
            UUID placeholderPlayerId
    ) {
        /** Compatibility constructor which immediately detaches the Bukkit object. */
        public ModerationAction(
                String action,
                String executor,
                String target,
                String targetUuid,
                String reason,
                String duration,
                String source,
                String externalId,
                String rawCommand,
                boolean confirmed,
                OfflinePlayer placeholderContext
        ) {
            this(action, executor, target, targetUuid, reason, duration, source, externalId,
                    rawCommand, confirmed,
                    placeholderContext == null ? null : placeholderContext.getUniqueId());
        }
    }

    void report(ModerationAction action);

    default void report(
            String action,
            String executor,
            String target,
            String reason,
            String rawCommand,
            UUID placeholderPlayerId
    ) {
        report(new ModerationAction(action, executor, target, "", reason, "",
                "adapter", "", rawCommand, true, placeholderPlayerId));
    }

    /** @deprecated Pass a UUID so the API never retains a Bukkit player handle. */
    @Deprecated(forRemoval = false)
    default void report(
            String action,
            String executor,
            String target,
            String reason,
            String rawCommand,
            OfflinePlayer placeholderContext
    ) {
        report(action, executor, target, reason, rawCommand,
                placeholderContext == null ? null : placeholderContext.getUniqueId());
    }
}
