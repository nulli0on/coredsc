package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

                                                          
public final class TicketRepository {
    public enum ReserveStatus {
        RESERVED,
        USER_LIMIT,
        GLOBAL_LIMIT,
        COOLDOWN
    }

    public record ReserveResult(ReserveStatus status, long ticketId, long remainingMillis) { }

    public record Ticket(
            long id,
            String minecraftUuid,
            String minecraftName,
            String discordUserId,
            String reason,
            String message,
            String status,
            String channelId,
            long createdAt,
            long closedAt,
            String closedBy,
            String claimedBy,
            String priority,
            long updatedAt
    ) { }

    private final SQLiteStorage storage;

    public TicketRepository(SQLiteStorage storage) {
        this.storage = storage;
    }

    public CompletableFuture<ReserveResult> reserve(
            String minecraftUuid,
            String minecraftName,
            String discordUserId,
            String reason,
            String message,
            long now,
            long cooldownMillis,
            int maxOpenPerUser,
            int maxOpenGlobal
    ) {
        return storage.transaction(connection -> {
            try (PreparedStatement cleanup = connection.prepareStatement(
                    "DELETE FROM tickets WHERE status = 'CREATING' AND created_at < ?")) {
                cleanup.setLong(1, now - 600_000L);
                cleanup.executeUpdate();
            }

            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM tickets WHERE discord_user_id = ? " +
                            "AND status IN ('CREATING', 'OPEN', 'CLAIMED')")) {
                count.setString(1, discordUserId);
                try (ResultSet result = count.executeQuery()) {
                    if (result.next() && result.getInt(1) >= maxOpenPerUser) {
                        return new ReserveResult(ReserveStatus.USER_LIMIT, 0L, 0L);
                    }
                }
            }

            if (maxOpenGlobal > 0) {
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM tickets WHERE status IN ('CREATING', 'OPEN', 'CLAIMED')")) {
                    try (ResultSet result = count.executeQuery()) {
                        if (result.next() && result.getInt(1) >= maxOpenGlobal) {
                            return new ReserveResult(ReserveStatus.GLOBAL_LIMIT, 0L, 0L);
                        }
                    }
                }
            }

            try (PreparedStatement latest = connection.prepareStatement(
                    "SELECT MAX(created_at) FROM tickets WHERE discord_user_id = ?")) {
                latest.setString(1, discordUserId);
                try (ResultSet result = latest.executeQuery()) {
                    long lastCreated = result.next() ? result.getLong(1) : 0L;
                    long remaining = cooldownMillis - (now - lastCreated);
                    if (lastCreated > 0L && remaining > 0L) {
                        return new ReserveResult(ReserveStatus.COOLDOWN, 0L, remaining);
                    }
                }
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO tickets (minecraft_uuid, minecraft_name, discord_user_id, " +
                            "reason, message, status, channel_id, created_at, closed_at, closed_by) " +
                            "VALUES (?, ?, ?, ?, ?, 'CREATING', '', ?, 0, '')",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, minecraftUuid);
                insert.setString(2, minecraftName == null ? "" : minecraftName);
                insert.setString(3, discordUserId);
                insert.setString(4, reason);
                insert.setString(5, message);
                insert.setLong(6, now);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("SQLite did not return a ticket ID");
                    }
                    return new ReserveResult(ReserveStatus.RESERVED, keys.getLong(1), 0L);
                }
            }
        });
    }

    public CompletableFuture<Void> activate(long ticketId, String channelId) {
        return storage.execute(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE tickets SET status = 'OPEN', channel_id = ?, updated_at = ? " +
                            "WHERE id = ? AND status = 'CREATING'")) {
                update.setString(1, channelId);
                update.setLong(2, System.currentTimeMillis());
                update.setLong(3, ticketId);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("Ticket reservation is no longer active");
                }
            }
            return null;
        });
    }

    public CompletableFuture<Void> release(long ticketId) {
        return storage.execute(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM tickets WHERE id = ? AND status = 'CREATING'")) {
                delete.setLong(1, ticketId);
                delete.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<Ticket>> findById(long ticketId) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM tickets WHERE id = ?")) {
                statement.setLong(1, ticketId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(read(result)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<Ticket>> findOpenByChannel(String channelId) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM tickets WHERE channel_id = ? AND status IN ('OPEN','CLAIMED') " +
                            "ORDER BY id DESC LIMIT 1")) {
                statement.setString(1, channelId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(read(result)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<Ticket>> findOpenByUser(String discordUserId) {
        return storage.execute(connection -> {
            List<Ticket> tickets = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM tickets WHERE discord_user_id = ? AND status IN ('OPEN','CLAIMED') " +
                            "ORDER BY id DESC")) {
                statement.setString(1, discordUserId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        tickets.add(read(result));
                    }
                }
            }
            return tickets;
        });
    }

    public CompletableFuture<Boolean> close(long ticketId, String closedBy, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE tickets SET status = 'CLOSED', closed_at = ?, closed_by = ? " +
                            "WHERE id = ? AND status IN ('OPEN','CLAIMED')")) {
                update.setLong(1, now);
                update.setString(2, closedBy == null ? "" : closedBy);
                update.setLong(3, ticketId);
                return update.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<List<Ticket>> findOpenByMinecraftUuid(String minecraftUuid) {
        return storage.execute(connection -> {
            List<Ticket> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM tickets WHERE minecraft_uuid = ? AND status IN ('OPEN','CLAIMED') ORDER BY id DESC")) {
                statement.setString(1, minecraftUuid);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(read(result));
                }
            }
            return values;
        });
    }

    public CompletableFuture<Boolean> claim(long ticketId, String claimedBy, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE tickets SET status='CLAIMED', claimed_by=?, updated_at=? WHERE id=? AND status='OPEN'")) {
                update.setString(1, claimedBy); update.setLong(2, now); update.setLong(3, ticketId);
                return update.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Boolean> setPriority(long ticketId, String priority, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE tickets SET priority=?, updated_at=? WHERE id=? AND status IN ('OPEN','CLAIMED')")) {
                update.setString(1, priority); update.setLong(2, now); update.setLong(3, ticketId);
                return update.executeUpdate() == 1;
            }
        });
    }

    private static Ticket read(ResultSet result) throws Exception {
        return new Ticket(
                result.getLong("id"),
                result.getString("minecraft_uuid"),
                result.getString("minecraft_name"),
                result.getString("discord_user_id"),
                result.getString("reason"),
                result.getString("message"),
                result.getString("status"),
                result.getString("channel_id"),
                result.getLong("created_at"),
                result.getLong("closed_at"),
                result.getString("closed_by"),
                result.getString("claimed_by"),
                result.getString("priority"),
                result.getLong("updated_at")
        );
    }
}
