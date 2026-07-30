package com.hubertstudios.coredsc.api;

import org.bukkit.OfflinePlayer;

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
            OfflinePlayer placeholderContext
    ) { }

    void report(ModerationAction action);

    default void report(
            String action,
            String executor,
            String target,
            String reason,
            String rawCommand,
            OfflinePlayer placeholderContext
    ) {
        report(new ModerationAction(action, executor, target, "", reason, "",
                "adapter", "", rawCommand, true, placeholderContext));
    }
}
