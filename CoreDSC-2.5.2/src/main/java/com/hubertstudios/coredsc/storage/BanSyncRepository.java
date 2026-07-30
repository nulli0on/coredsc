package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Tracks only bans owned by CoreDSC, preventing accidental removal of external bans. */
public final class BanSyncRepository {
    public record State(
            String minecraftUuid,
            String minecraftName,
            String discordUserId,
            boolean minecraftManaged,
            boolean discordManaged,
            String reason,
            long updatedAt
    ) { }

    private final SQLiteStorage storage;

    public BanSyncRepository(SQLiteStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<Optional<State>> findByMinecraftUuid(String minecraftUuid) {
        return find("minecraft_uuid", minecraftUuid);
    }

    public CompletableFuture<Optional<State>> findByDiscordUserId(String discordUserId) {
        return find("discord_user_id", discordUserId);
    }

    private CompletableFuture<Optional<State>> find(String column, String value) {
        return storage.execute(connection -> {
            String sql = "SELECT minecraft_uuid,minecraft_name,discord_user_id,minecraft_managed,discord_managed,reason,updated_at " +
                    "FROM ban_sync_state WHERE " + column + "=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Void> upsert(State state) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ban_sync_state (minecraft_uuid,minecraft_name,discord_user_id,minecraft_managed,discord_managed,reason,updated_at) " +
                            "VALUES (?,?,?,?,?,?,?) ON CONFLICT(minecraft_uuid) DO UPDATE SET " +
                            "minecraft_name=excluded.minecraft_name,discord_user_id=excluded.discord_user_id," +
                            "minecraft_managed=excluded.minecraft_managed,discord_managed=excluded.discord_managed," +
                            "reason=excluded.reason,updated_at=excluded.updated_at")) {
                statement.setString(1, state.minecraftUuid());
                statement.setString(2, state.minecraftName());
                statement.setString(3, state.discordUserId());
                statement.setInt(4, state.minecraftManaged() ? 1 : 0);
                statement.setInt(5, state.discordManaged() ? 1 : 0);
                statement.setString(6, state.reason());
                statement.setLong(7, state.updatedAt());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> delete(String minecraftUuid) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM ban_sync_state WHERE minecraft_uuid=?")) {
                statement.setString(1, minecraftUuid);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static State read(ResultSet resultSet) throws Exception {
        return new State(
                resultSet.getString("minecraft_uuid"),
                resultSet.getString("minecraft_name"),
                resultSet.getString("discord_user_id"),
                resultSet.getInt("minecraft_managed") != 0,
                resultSet.getInt("discord_managed") != 0,
                resultSet.getString("reason"),
                resultSet.getLong("updated_at")
        );
    }
}
