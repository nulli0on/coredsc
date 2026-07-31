package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

                                                                        
public final class LinkEnforcementRepository {
    public record State(long firstSeenAt, long lastReminderAt) { }

    private final SQLiteStorage storage;

    public LinkEnforcementRepository(SQLiteStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<State> getOrCreate(String minecraftUuid, long now) {
        return storage.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO link_enforcement (minecraft_uuid,first_seen_at,last_reminder_at) VALUES (?,?,0)")) {
                insert.setString(1, minecraftUuid);
                insert.setLong(2, now);
                insert.executeUpdate();
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT first_seen_at,last_reminder_at FROM link_enforcement WHERE minecraft_uuid=?")) {
                select.setString(1, minecraftUuid);
                try (ResultSet resultSet = select.executeQuery()) {
                    if (!resultSet.next()) throw new IllegalStateException("Missing link enforcement state");
                    return new State(resultSet.getLong(1), resultSet.getLong(2));
                }
            }
        });
    }

    public CompletableFuture<Void> markReminder(String minecraftUuid, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE link_enforcement SET last_reminder_at=? WHERE minecraft_uuid=?")) {
                statement.setLong(1, now);
                statement.setString(2, minecraftUuid);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> clear(String minecraftUuid) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM link_enforcement WHERE minecraft_uuid=?")) {
                statement.setString(1, minecraftUuid);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
