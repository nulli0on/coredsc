package com.hubertstudios.coredsc.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Stable public integration surface for CoreDSC add-ons. */
public interface CoreDSCApi {
    record LinkedAccountView(UUID minecraftUuid, String minecraftName, String discordUserId, long linkedAt) { }
    record CreateResult(boolean success, long id, String message) { }
    record CompetitiveRatingView(
            UUID minecraftUuid,
            String minecraftName,
            int rating,
            int wins,
            int losses,
            int matches
    ) { }

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

    /** Triggers a configured cinematic NPC webhook profile. */
    default CompletableFuture<Boolean> triggerLoreEvent(
            String profileId,
            String message,
            String actor
    ) {
        return CompletableFuture.completedFuture(false);
    }

    /** Records a built-in competitive win/loss result for an external arena plugin. */
    default CompletableFuture<CompetitiveRatingView> recordCompetitiveResult(
            UUID winner,
            String winnerName,
            UUID loser,
            String loserName
    ) {
        return CompletableFuture.failedFuture(
                new IllegalStateException("Competitive module unavailable"));
    }

    /** Returns the active competitive provider's ordered leaderboard. */
    default CompletableFuture<java.util.List<CompetitiveRatingView>> competitiveLeaderboard(int limit) {
        return CompletableFuture.completedFuture(java.util.List.of());
    }

    boolean isDiscordReady();

    String serverId();
}
