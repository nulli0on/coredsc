package com.hubertstudios.coredsc.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Optional service-provider contract for minigame- or arena-owned ratings. */
public interface CompetitiveRatingProvider {
    record Rating(
            UUID minecraftUuid,
            String minecraftName,
            int rating,
            int wins,
            int losses,
            int matches
    ) { }

    String providerId();

    CompletableFuture<Optional<Rating>> rating(UUID minecraftUuid);

    CompletableFuture<List<Rating>> leaderboard(int limit);
}
