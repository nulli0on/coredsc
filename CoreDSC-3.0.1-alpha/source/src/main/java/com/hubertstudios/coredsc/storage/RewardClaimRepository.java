package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;









public final class RewardClaimRepository {
    public record Claim(
            String claimKey,
            String rewardType,
            String minecraftUuid,
            String discordUserId,
            int nextStep,
            int inflightStep,
            int totalSteps,
            String status,
            long createdAt,
            long updatedAt,
            String lastError
    ) { }

    private final SQLiteStorage storage;

    public RewardClaimRepository(SQLiteStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<Claim> reserve(
            String claimKey,
            String rewardType,
            String minecraftUuid,
            String discordUserId,
            int totalSteps,
            long now
    ) {
        return storage.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO reward_claims " +
                            "(claim_key,reward_type,minecraft_uuid,discord_user_id,next_step,inflight_step,total_steps,status,created_at,updated_at,last_error) " +
                            "VALUES (?,?,?,?,0,-1,?,'PENDING',?,?, '')")) {
                insert.setString(1, claimKey);
                insert.setString(2, rewardType);
                insert.setString(3, minecraftUuid);
                insert.setString(4, discordUserId);
                insert.setInt(5, Math.max(0, totalSteps));
                insert.setLong(6, now);
                insert.setLong(7, now);
                insert.executeUpdate();
            }
            Claim claim = select(connection, claimKey);
            if (claim == null) {
                throw new IllegalStateException("Reward claim could not be reserved");
            }
            if (!claim.minecraftUuid().equals(minecraftUuid)
                    || !claim.discordUserId().equals(discordUserId)
                    || !claim.rewardType().equals(rewardType)
                    || claim.totalSteps() != Math.max(0, totalSteps)) {
                throw new IllegalStateException("Reward claim key collision: " + claimKey);
            }
            return claim;
        });
    }

    public CompletableFuture<Optional<Claim>> find(String claimKey) {
        return storage.execute(connection -> Optional.ofNullable(select(connection, claimKey)));
    }

    
    public CompletableFuture<List<Claim>> findResumable(String rewardType, int limit) {
        return storage.execute(connection -> {
            List<Claim> claims = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT claim_key,reward_type,minecraft_uuid,discord_user_id,next_step,inflight_step,total_steps,status,created_at,updated_at,last_error " +
                            "FROM reward_claims WHERE reward_type=? AND status='PENDING' AND inflight_step=-1 " +
                            "ORDER BY created_at LIMIT ?")) {
                statement.setString(1, rewardType);
                statement.setInt(2, Math.max(1, Math.min(limit, 1000)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) claims.add(read(resultSet));
                }
            }
            return List.copyOf(claims);
        });
    }

    
    public CompletableFuture<long[]> reviewCounts() {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " +
                            "SUM(CASE WHEN status='MANUAL_REVIEW' THEN 1 ELSE 0 END) AS manual_count, " +
                            "SUM(CASE WHEN status='EXECUTING' THEN 1 ELSE 0 END) AS executing_count " +
                            "FROM reward_claims")) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return new long[] {0L, 0L};
                    return new long[] {resultSet.getLong("manual_count"), resultSet.getLong("executing_count")};
                }
            }
        });
    }

    
    public CompletableFuture<Boolean> beginStep(String claimKey, int expectedStep, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE reward_claims SET status='EXECUTING', inflight_step=?, updated_at=?, last_error='' " +
                            "WHERE claim_key=? AND status='PENDING' AND inflight_step=-1 AND next_step=?")) {
                statement.setInt(1, expectedStep);
                statement.setLong(2, now);
                statement.setString(3, claimKey);
                statement.setInt(4, expectedStep);
                return statement.executeUpdate() == 1;
            }
        });
    }

    
    public CompletableFuture<Boolean> completeStep(String claimKey, int expectedStep, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE reward_claims SET next_step=?, inflight_step=-1, " +
                            "status=CASE WHEN ?>=total_steps THEN 'COMPLETED' ELSE 'PENDING' END, " +
                            "updated_at=?, last_error='' " +
                            "WHERE claim_key=? AND status='EXECUTING' AND inflight_step=? AND next_step=?")) {
                int next = expectedStep + 1;
                statement.setInt(1, next);
                statement.setInt(2, next);
                statement.setLong(3, now);
                statement.setString(4, claimKey);
                statement.setInt(5, expectedStep);
                statement.setInt(6, expectedStep);
                return statement.executeUpdate() == 1;
            }
        });
    }

    
    public CompletableFuture<Void> manualReview(String claimKey, String error, long now) {
        String safe = sanitize(error);
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE reward_claims SET status='MANUAL_REVIEW', updated_at=?, last_error=? " +
                            "WHERE claim_key=? AND status<>'COMPLETED'")) {
                statement.setLong(1, now);
                statement.setString(2, safe);
                statement.setString(3, claimKey);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static Claim select(java.sql.Connection connection, String claimKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT claim_key,reward_type,minecraft_uuid,discord_user_id,next_step,inflight_step,total_steps,status,created_at,updated_at,last_error " +
                        "FROM reward_claims WHERE claim_key=?")) {
            statement.setString(1, claimKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? read(resultSet) : null;
            }
        }
    }

    private static Claim read(ResultSet resultSet) throws Exception {
        return new Claim(
                resultSet.getString("claim_key"),
                resultSet.getString("reward_type"),
                resultSet.getString("minecraft_uuid"),
                resultSet.getString("discord_user_id"),
                resultSet.getInt("next_step"),
                resultSet.getInt("inflight_step"),
                resultSet.getInt("total_steps"),
                resultSet.getString("status"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"),
                resultSet.getString("last_error")
        );
    }

    private static String sanitize(String error) {
        String safe = error == null ? "" : error.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ');
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
    }
}
