package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Asynchronous access to the one-to-one Minecraft/Discord account map. */
public final class LinkedAccountRepository {

    public record LinkedAccount(
            String minecraftUuid,
            String discordUserId,
            String minecraftName,
            long linkedAt
    ) { }

    private final SQLiteStorage storage;

    public LinkedAccountRepository(SQLiteStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<Void> updateMinecraftName(String minecraftUuid, String minecraftName) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE linked_accounts SET minecraft_name=? WHERE minecraft_uuid=?")) {
                statement.setString(1, minecraftName == null ? "" : minecraftName);
                statement.setString(2, minecraftUuid);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<LinkedAccount>> findByMinecraftUuid(String minecraftUuid) {
        return storage.execute(connection -> {
            String sql = "SELECT minecraft_uuid, discord_user_id, minecraft_name, linked_at " +
                    "FROM linked_accounts WHERE minecraft_uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, minecraftUuid);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<LinkedAccount>> findAll() {
        return storage.execute(connection -> {
            List<LinkedAccount> accounts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT minecraft_uuid, discord_user_id, minecraft_name, linked_at FROM linked_accounts ORDER BY linked_at")) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        accounts.add(read(resultSet));
                    }
                }
            }
            return List.copyOf(accounts);
        });
    }

    public CompletableFuture<Optional<LinkedAccount>> findByDiscordUserId(String discordUserId) {
        return storage.execute(connection -> {
            String sql = "SELECT minecraft_uuid, discord_user_id, minecraft_name, linked_at " +
                    "FROM linked_accounts WHERE discord_user_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, discordUserId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<LinkedAccount>> removeByMinecraftUuid(String minecraftUuid) {
        return storage.transaction(connection -> {
            LinkedAccount account = selectByMinecraft(connection, minecraftUuid);
            if (account == null) {
                return Optional.empty();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM linked_accounts WHERE minecraft_uuid = ?")) {
                statement.setString(1, minecraftUuid);
                statement.executeUpdate();
            }
            return Optional.of(account);
        });
    }

    public CompletableFuture<Optional<LinkedAccount>> removeByDiscordUserId(String discordUserId) {
        return storage.transaction(connection -> {
            LinkedAccount account = selectByDiscord(connection, discordUserId);
            if (account == null) {
                return Optional.empty();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM linked_accounts WHERE discord_user_id = ?")) {
                statement.setString(1, discordUserId);
                statement.executeUpdate();
            }
            return Optional.of(account);
        });
    }

    static LinkedAccount selectByMinecraft(java.sql.Connection connection, String minecraftUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT minecraft_uuid, discord_user_id, minecraft_name, linked_at " +
                        "FROM linked_accounts WHERE minecraft_uuid = ?")) {
            statement.setString(1, minecraftUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? read(resultSet) : null;
            }
        }
    }

    static LinkedAccount selectByDiscord(java.sql.Connection connection, String discordUserId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT minecraft_uuid, discord_user_id, minecraft_name, linked_at " +
                        "FROM linked_accounts WHERE discord_user_id = ?")) {
            statement.setString(1, discordUserId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? read(resultSet) : null;
            }
        }
    }

    private static LinkedAccount read(ResultSet resultSet) throws Exception {
        return new LinkedAccount(
                resultSet.getString("minecraft_uuid"),
                resultSet.getString("discord_user_id"),
                resultSet.getString("minecraft_name"),
                resultSet.getLong("linked_at")
        );
    }
}
