package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Persistent Discord boost state and the latest rewarded period. */
public final class BoosterStateRepository {
    public record BoosterState(
            String discordUserId,
            String minecraftUuid,
            boolean active,
            long boostedAt,
            long lastRewardPeriod,
            long updatedAt
    ) { }

    private final SQLiteStorage storage;

    public BoosterStateRepository(SQLiteStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<Optional<BoosterState>> find(String discordUserId) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT discord_user_id,minecraft_uuid,active,boosted_at,last_reward_period,updated_at " +
                            "FROM booster_states WHERE discord_user_id=?")) {
                statement.setString(1, discordUserId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return Optional.empty();
                    return Optional.of(read(resultSet));
                }
            }
        });
    }

    public CompletableFuture<Void> upsert(
            String discordUserId,
            String minecraftUuid,
            boolean active,
            long boostedAt,
            long lastRewardPeriod,
            long now
    ) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO booster_states (discord_user_id,minecraft_uuid,active,boosted_at,last_reward_period,updated_at) " +
                            "VALUES (?,?,?,?,?,?) ON CONFLICT(discord_user_id) DO UPDATE SET " +
                            "minecraft_uuid=excluded.minecraft_uuid,active=excluded.active,boosted_at=excluded.boosted_at," +
                            "last_reward_period=excluded.last_reward_period,updated_at=excluded.updated_at")) {
                statement.setString(1, discordUserId);
                statement.setString(2, minecraftUuid == null ? "" : minecraftUuid);
                statement.setInt(3, active ? 1 : 0);
                statement.setLong(4, boostedAt);
                statement.setLong(5, lastRewardPeriod);
                statement.setLong(6, now);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static BoosterState read(ResultSet resultSet) throws Exception {
        return new BoosterState(
                resultSet.getString("discord_user_id"),
                resultSet.getString("minecraft_uuid"),
                resultSet.getInt("active") != 0,
                resultSet.getLong("boosted_at"),
                resultSet.getLong("last_reward_period"),
                resultSet.getLong("updated_at")
        );
    }
}
