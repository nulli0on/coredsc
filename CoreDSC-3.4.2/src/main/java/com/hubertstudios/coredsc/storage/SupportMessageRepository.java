package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Persistent two-way ticket/report messages and offline Minecraft delivery. */
public final class SupportMessageRepository {
    public record SupportMessage(
            long id,
            String itemType,
            long itemId,
            String senderPlatform,
            String senderId,
            String senderName,
            String message,
            long createdAt,
            boolean minecraftDelivered,
            boolean discordDelivered
    ) { }

    private final SQLiteStorage storage;

    public SupportMessageRepository(SQLiteStorage storage) {
        this.storage = storage;
    }

    public CompletableFuture<Long> add(
            String itemType,
            long itemId,
            String senderPlatform,
            String senderId,
            String senderName,
            String message,
            long createdAt,
            boolean minecraftDelivered,
            boolean discordDelivered
    ) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO support_messages (item_type, item_id, sender_platform, sender_id, " +
                            "sender_name, message, created_at, minecraft_delivered, discord_delivered) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, itemType);
                statement.setLong(2, itemId);
                statement.setString(3, senderPlatform);
                statement.setString(4, senderId == null ? "" : senderId);
                statement.setString(5, senderName == null ? "" : senderName);
                statement.setString(6, message);
                statement.setLong(7, createdAt);
                statement.setInt(8, minecraftDelivered ? 1 : 0);
                statement.setInt(9, discordDelivered ? 1 : 0);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("SQLite did not return a support message ID");
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    public CompletableFuture<List<SupportMessage>> pendingForMinecraft(
            String minecraftUuid,
            String itemType,
            int limit
    ) {
        return storage.execute(connection -> {
            List<SupportMessage> values = new ArrayList<>();
            String sql = "SELECT sm.* FROM support_messages sm " +
                    "LEFT JOIN tickets t ON sm.item_type = 'TICKET' AND t.id = sm.item_id " +
                    "LEFT JOIN reports r ON sm.item_type = 'REPORT' AND r.id = sm.item_id " +
                    "LEFT JOIN applications a ON sm.item_type = 'APPLICATION' AND a.id = sm.item_id " +
                    "LEFT JOIN appeals ap ON sm.item_type = 'APPEAL' AND ap.id = sm.item_id " +
                    "WHERE sm.minecraft_delivered = 0 AND sm.sender_platform IN ('DISCORD','SYSTEM') " +
                    "AND sm.item_type = ? " +
                    "AND (t.minecraft_uuid = ? OR r.reporter_uuid = ? OR a.minecraft_uuid = ? OR ap.minecraft_uuid = ?) " +
                    "ORDER BY sm.created_at ASC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, itemType);
                statement.setString(2, minecraftUuid);
                statement.setString(3, minecraftUuid);
                statement.setString(4, minecraftUuid);
                statement.setString(5, minecraftUuid);
                statement.setInt(6, Math.max(1, limit));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        values.add(read(result));
                    }
                }
            }
            return values;
        });
    }

    public CompletableFuture<List<SupportMessage>> transcript(String itemType, long itemId, int limit) {
        return storage.execute(connection -> {
            List<SupportMessage> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM support_messages WHERE item_type = ? AND item_id = ? " +
                            "ORDER BY created_at ASC LIMIT ?")) {
                statement.setString(1, itemType);
                statement.setLong(2, itemId);
                statement.setInt(3, Math.max(1, limit));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        values.add(read(result));
                    }
                }
            }
            return values;
        });
    }

    public CompletableFuture<Void> markMinecraftDelivered(List<Long> ids) {
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return storage.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE support_messages SET minecraft_delivered = 1 WHERE id = ?")) {
                for (Long id : ids) {
                    statement.setLong(1, id);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    public CompletableFuture<Void> markDiscordDelivered(long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE support_messages SET discord_delivered = 1 WHERE id = ?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static SupportMessage read(ResultSet result) throws Exception {
        return new SupportMessage(
                result.getLong("id"), result.getString("item_type"), result.getLong("item_id"),
                result.getString("sender_platform"), result.getString("sender_id"),
                result.getString("sender_name"), result.getString("message"),
                result.getLong("created_at"), result.getInt("minecraft_delivered") != 0,
                result.getInt("discord_delivered") != 0
        );
    }
}
