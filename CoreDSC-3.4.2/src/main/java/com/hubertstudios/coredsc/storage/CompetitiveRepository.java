package com.hubertstudios.coredsc.storage;

import com.hubertstudios.coredsc.competitive.EloCalculator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Async SQLite persistence for built-in competitive ratings. */
public final class CompetitiveRepository {
    public record Rating(
            UUID minecraftUuid,
            String minecraftName,
            int rating,
            int wins,
            int losses,
            int kills,
            int deaths,
            int matches,
            long updatedAt
    ) { }

    public record MatchResult(
            Rating winner,
            Rating loser,
            int winnerDelta,
            int loserDelta
    ) { }

    private final SQLiteStorage storage;

    public CompetitiveRepository(SQLiteStorage storage) {
        this.storage = storage;
    }

    public CompletableFuture<MatchResult> recordWin(
            UUID winnerUuid,
            String winnerName,
            UUID loserUuid,
            String loserName,
            int initialRating,
            int normalK,
            int provisionalK,
            int provisionalMatches,
            int minimumRating,
            boolean combatKill,
            long now
    ) {
        if (winnerUuid == null || loserUuid == null || winnerUuid.equals(loserUuid)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Winner and loser must be two different Minecraft accounts"));
        }
        return storage.transaction(connection -> {
            Rating winner = findOrInitial(connection, winnerUuid, winnerName, initialRating, now);
            Rating loser = findOrInitial(connection, loserUuid, loserName, initialRating, now);
            int winnerK = winner.matches() < provisionalMatches ? provisionalK : normalK;
            int loserK = loser.matches() < provisionalMatches ? provisionalK : normalK;
            EloCalculator.Update update = EloCalculator.calculate(
                    winner.rating(), loser.rating(), winnerK, loserK, minimumRating);

            Rating updatedWinner = new Rating(
                    winnerUuid,
                    cleanName(winnerName, winner.minecraftName()),
                    update.winnerAfter(),
                    winner.wins() + 1,
                    winner.losses(),
                    winner.kills() + (combatKill ? 1 : 0),
                    winner.deaths(),
                    winner.matches() + 1,
                    now);
            Rating updatedLoser = new Rating(
                    loserUuid,
                    cleanName(loserName, loser.minecraftName()),
                    update.loserAfter(),
                    loser.wins(),
                    loser.losses() + 1,
                    loser.kills(),
                    loser.deaths() + (combatKill ? 1 : 0),
                    loser.matches() + 1,
                    now);
            upsert(connection, updatedWinner);
            upsert(connection, updatedLoser);
            return new MatchResult(updatedWinner, updatedLoser,
                    update.winnerDelta(), update.loserDelta());
        });
    }

    public CompletableFuture<Optional<Rating>> find(UUID uuid) {
        if (uuid == null) return CompletableFuture.completedFuture(Optional.empty());
        return storage.execute(connection -> Optional.ofNullable(find(connection, uuid)));
    }

    public CompletableFuture<Optional<Rating>> findByName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isBlank()) return CompletableFuture.completedFuture(Optional.empty());
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM competitive_ratings WHERE minecraft_name = ? COLLATE NOCASE LIMIT 1")) {
                statement.setString(1, clean);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(read(result)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<Rating>> top(int limit, int minimumMatches) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeMatches = Math.max(0, minimumMatches);
        return storage.execute(connection -> {
            List<Rating> ratings = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM competitive_ratings WHERE matches >= ? "
                            + "ORDER BY rating DESC, wins DESC, losses ASC, updated_at ASC LIMIT ?")) {
                statement.setInt(1, safeMatches);
                statement.setInt(2, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) ratings.add(read(result));
                }
            }
            return List.copyOf(ratings);
        });
    }

    public CompletableFuture<Optional<String>> leaderboardMessage(String channelId) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT message_id FROM competitive_leaderboard_messages WHERE channel_id = ?")) {
                statement.setString(1, channelId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Void> saveLeaderboardMessage(String channelId, String messageId, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO competitive_leaderboard_messages(channel_id,message_id,updated_at) VALUES(?,?,?) "
                            + "ON CONFLICT(channel_id) DO UPDATE SET message_id=excluded.message_id, "
                            + "updated_at=excluded.updated_at")) {
                statement.setString(1, channelId);
                statement.setString(2, messageId);
                statement.setLong(3, now);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deleteLeaderboardMessage(String channelId) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM competitive_leaderboard_messages WHERE channel_id = ?")) {
                statement.setString(1, channelId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static Rating findOrInitial(
            Connection connection,
            UUID uuid,
            String name,
            int initialRating,
            long now
    ) throws Exception {
        Rating current = find(connection, uuid);
        return current == null
                ? new Rating(uuid, cleanName(name, uuid.toString()), initialRating,
                0, 0, 0, 0, 0, now)
                : current;
    }

    private static Rating find(Connection connection, UUID uuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM competitive_ratings WHERE minecraft_uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    private static void upsert(Connection connection, Rating rating) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO competitive_ratings(minecraft_uuid,minecraft_name,rating,wins,losses,kills,deaths,matches,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(minecraft_uuid) DO UPDATE SET "
                        + "minecraft_name=excluded.minecraft_name,rating=excluded.rating,wins=excluded.wins,"
                        + "losses=excluded.losses,kills=excluded.kills,deaths=excluded.deaths,"
                        + "matches=excluded.matches,updated_at=excluded.updated_at")) {
            statement.setString(1, rating.minecraftUuid().toString());
            statement.setString(2, rating.minecraftName());
            statement.setInt(3, rating.rating());
            statement.setInt(4, rating.wins());
            statement.setInt(5, rating.losses());
            statement.setInt(6, rating.kills());
            statement.setInt(7, rating.deaths());
            statement.setInt(8, rating.matches());
            statement.setLong(9, rating.updatedAt());
            statement.executeUpdate();
        }
    }

    private static Rating read(ResultSet result) throws Exception {
        return new Rating(
                UUID.fromString(result.getString("minecraft_uuid")),
                result.getString("minecraft_name"),
                result.getInt("rating"),
                result.getInt("wins"),
                result.getInt("losses"),
                result.getInt("kills"),
                result.getInt("deaths"),
                result.getInt("matches"),
                result.getLong("updated_at"));
    }

    private static String cleanName(String preferred, String fallback) {
        String value = preferred == null ? "" : preferred.trim();
        if (value.isBlank()) value = fallback == null ? "" : fallback.trim();
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
