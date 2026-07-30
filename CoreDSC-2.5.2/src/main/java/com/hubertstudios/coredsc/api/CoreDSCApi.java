package com.hubertstudios.coredsc.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Stable public integration surface for CoreDSC add-ons. */
public interface CoreDSCApi {
    record LinkedAccountView(UUID minecraftUuid, String minecraftName, String discordUserId, long linkedAt) { }
    record CreateResult(boolean success, long id, String message) { }

    CompletableFuture<Optional<LinkedAccountView>> findLinkedAccount(UUID minecraftUuid);

    CompletableFuture<Optional<LinkedAccountView>> findLinkedAccount(String discordUserId);

    CompletableFuture<CreateResult> createTicket(UUID playerUuid, String reason, String message);

    CompletableFuture<CreateResult> createReport(
            UUID reporterUuid,
            UUID targetUuid,
            String reason,
            String message
    );

    CompletableFuture<Void> publishModerationAction(ModerationAuditService.ModerationAction action);

    /**
     * Publishes a JSON-safe custom event to matching Python handlers.
     * The future resolves to true only when the Python module accepted the event.
     */
    default CompletableFuture<Boolean> publishPythonEvent(String eventName, Map<String, ?> data) {
        return CompletableFuture.completedFuture(false);
    }

    boolean isDiscordReady();

    String serverId();
}
