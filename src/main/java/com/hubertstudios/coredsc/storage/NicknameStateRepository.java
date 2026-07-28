package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Stores the nickname that existed before CoreDSC changed it. */
public final class NicknameStateRepository {
    public record NicknameState(String discordUserId, String originalNickname, String syncedNickname, long updatedAt) { }

    private final SQLiteStorage storage;

    public NicknameStateRepository(SQLiteStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<Optional<NicknameState>> find(String discordUserId) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT discord_user_id,original_nickname,synced_nickname,updated_at FROM nickname_states WHERE discord_user_id=?")) {
                statement.setString(1, discordUserId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) return Optional.empty();
                    return Optional.of(new NicknameState(
                            resultSet.getString("discord_user_id"),
                            resultSet.getString("original_nickname"),
                            resultSet.getString("synced_nickname"),
                            resultSet.getLong("updated_at")));
                }
            }
        });
    }

    public CompletableFuture<Void> saveOriginalIfAbsent(
            String discordUserId,
            String originalNickname,
            String syncedNickname,
            long now
    ) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO nickname_states (discord_user_id,original_nickname,synced_nickname,updated_at) VALUES (?,?,?,?)")) {
                statement.setString(1, discordUserId);
                statement.setString(2, originalNickname == null ? "" : originalNickname);
                statement.setString(3, syncedNickname == null ? "" : syncedNickname);
                statement.setLong(4, now);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> updateSynced(String discordUserId, String syncedNickname, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE nickname_states SET synced_nickname=?,updated_at=? WHERE discord_user_id=?")) {
                statement.setString(1, syncedNickname == null ? "" : syncedNickname);
                statement.setLong(2, now);
                statement.setString(3, discordUserId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> delete(String discordUserId) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM nickname_states WHERE discord_user_id=?")) {
                statement.setString(1, discordUserId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<NicknameState>> remove(String discordUserId) {
        return storage.transaction(connection -> {
            NicknameState state = null;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT discord_user_id,original_nickname,synced_nickname,updated_at FROM nickname_states WHERE discord_user_id=?")) {
                select.setString(1, discordUserId);
                try (ResultSet resultSet = select.executeQuery()) {
                    if (resultSet.next()) {
                        state = new NicknameState(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getLong(4));
                    }
                }
            }
            if (state != null) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM nickname_states WHERE discord_user_id=?")) {
                    delete.setString(1, discordUserId);
                    delete.executeUpdate();
                }
            }
            return Optional.ofNullable(state);
        });
    }
}
