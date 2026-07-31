package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

                                                                 
public final class ReportRepository {
    public enum ReserveStatus { RESERVED, USER_LIMIT, GLOBAL_LIMIT, COOLDOWN, DUPLICATE }
    public record ReserveResult(ReserveStatus status, long reportId, long remainingMillis) { }
    public record Report(
            long id, String reporterUuid, String reporterName, String reporterDiscordId,
            String targetUuid, String targetName, String targetDiscordId,
            String reason, String message, String status, String priority, String channelId,
            String claimedBy, long createdAt, long closedAt, String closedBy
    ) { }

    private final SQLiteStorage storage;
    public ReportRepository(SQLiteStorage storage) { this.storage = storage; }

    public CompletableFuture<ReserveResult> reserve(
            String reporterUuid, String reporterName, String reporterDiscordId,
            String targetUuid, String targetName, String targetDiscordId,
            String reason, String message, long now, long cooldownMillis,
            long duplicateWindowMillis, int maxOpenPerUser, int maxOpenGlobal
    ) {
        return storage.transaction(connection -> {
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM reports WHERE reporter_uuid = ? AND status IN ('CREATING','OPEN','CLAIMED')")) {
                count.setString(1, reporterUuid);
                try (ResultSet result = count.executeQuery()) {
                    if (result.next() && result.getInt(1) >= maxOpenPerUser) {
                        return new ReserveResult(ReserveStatus.USER_LIMIT, 0L, 0L);
                    }
                }
            }
            if (maxOpenGlobal > 0) {
                try (PreparedStatement count = connection.prepareStatement(
                        "SELECT COUNT(*) FROM reports WHERE status IN ('CREATING','OPEN','CLAIMED')")) {
                    try (ResultSet result = count.executeQuery()) {
                        if (result.next() && result.getInt(1) >= maxOpenGlobal) {
                            return new ReserveResult(ReserveStatus.GLOBAL_LIMIT, 0L, 0L);
                        }
                    }
                }
            }
            try (PreparedStatement latest = connection.prepareStatement(
                    "SELECT MAX(created_at) FROM reports WHERE reporter_uuid = ?")) {
                latest.setString(1, reporterUuid);
                try (ResultSet result = latest.executeQuery()) {
                    long last = result.next() ? result.getLong(1) : 0L;
                    long remaining = cooldownMillis - (now - last);
                    if (last > 0L && remaining > 0L) {
                        return new ReserveResult(ReserveStatus.COOLDOWN, 0L, remaining);
                    }
                }
            }
            if (duplicateWindowMillis > 0L) {
                try (PreparedStatement duplicate = connection.prepareStatement(
                        "SELECT 1 FROM reports WHERE reporter_uuid = ? AND target_uuid = ? " +
                                "AND lower(reason) = lower(?) AND created_at >= ? LIMIT 1")) {
                    duplicate.setString(1, reporterUuid);
                    duplicate.setString(2, targetUuid);
                    duplicate.setString(3, reason);
                    duplicate.setLong(4, now - duplicateWindowMillis);
                    try (ResultSet result = duplicate.executeQuery()) {
                        if (result.next()) {
                            return new ReserveResult(ReserveStatus.DUPLICATE, 0L, duplicateWindowMillis);
                        }
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO reports (reporter_uuid, reporter_name, reporter_discord_id, target_uuid, " +
                            "target_name, target_discord_id, reason, message, status, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CREATING', ?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, reporterUuid); insert.setString(2, reporterName);
                insert.setString(3, reporterDiscordId); insert.setString(4, targetUuid);
                insert.setString(5, targetName); insert.setString(6, targetDiscordId == null ? "" : targetDiscordId);
                insert.setString(7, reason); insert.setString(8, message); insert.setLong(9, now);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) throw new IllegalStateException("SQLite did not return a report ID");
                    return new ReserveResult(ReserveStatus.RESERVED, keys.getLong(1), 0L);
                }
            }
        });
    }

    public CompletableFuture<Void> activate(long id, String channelId) {
        return updateState("UPDATE reports SET status='OPEN', channel_id=? WHERE id=? AND status='CREATING'", channelId, id);
    }

    public CompletableFuture<Void> release(long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM reports WHERE id=? AND status='CREATING'")) {
                statement.setLong(1, id); statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<Report>> findById(long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM reports WHERE id=?")) {
                statement.setLong(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(read(result)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<Report>> findOpenByChannel(String channelId) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM reports WHERE channel_id=? AND status IN ('OPEN','CLAIMED') LIMIT 1")) {
                statement.setString(1, channelId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(read(result)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Integer> countForAdmin(String filter) {
        return storage.execute(connection -> {
            String where = adminWhere(filter);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM reports" + where)) {
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        });
    }

    public CompletableFuture<List<Report>> findPageForAdmin(String filter, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 54));
        int safeOffset = Math.max(0, offset);
        return storage.execute(connection -> {
            List<Report> values = new ArrayList<>();
            String where = adminWhere(filter);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM reports" + where + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
                statement.setInt(1, safeLimit);
                statement.setInt(2, safeOffset);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(read(result));
                }
            }
            return values;
        });
    }

    private static String adminWhere(String filter) {
        String normalized = filter == null ? "ALL" : filter.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "OPEN" -> " WHERE status IN ('OPEN','CLAIMED')";
            case "UNCLAIMED" -> " WHERE status='OPEN'";
            case "CLAIMED" -> " WHERE status='CLAIMED'";
            case "CLOSED" -> " WHERE status='CLOSED'";
            case "CREATING" -> " WHERE status='CREATING'";
            default -> "";
        };
    }

    public CompletableFuture<List<Report>> findOpenByReporter(String reporterUuid) {
        return storage.execute(connection -> {
            List<Report> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM reports WHERE reporter_uuid=? AND status IN ('OPEN','CLAIMED') ORDER BY id DESC")) {
                statement.setString(1, reporterUuid);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(read(result));
                }
            }
            return values;
        });
    }

    public CompletableFuture<Boolean> claim(long id, String claimedBy) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE reports SET status='CLAIMED', claimed_by=? WHERE id=? AND status='OPEN'")) {
                statement.setString(1, claimedBy); statement.setLong(2, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Boolean> setPriority(long id, String priority) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE reports SET priority=? WHERE id=? AND status IN ('OPEN','CLAIMED')")) {
                statement.setString(1, priority); statement.setLong(2, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Boolean> close(long id, String closedBy, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE reports SET status='CLOSED', closed_at=?, closed_by=? " +
                            "WHERE id=? AND status IN ('OPEN','CLAIMED')")) {
                statement.setLong(1, now); statement.setString(2, closedBy); statement.setLong(3, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private CompletableFuture<Void> updateState(String sql, String value, long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value); statement.setLong(2, id);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Report state changed concurrently");
            }
            return null;
        });
    }

    private static Report read(ResultSet r) throws Exception {
        return new Report(r.getLong("id"), r.getString("reporter_uuid"), r.getString("reporter_name"),
                r.getString("reporter_discord_id"), r.getString("target_uuid"), r.getString("target_name"),
                r.getString("target_discord_id"), r.getString("reason"), r.getString("message"),
                r.getString("status"), r.getString("priority"), r.getString("channel_id"),
                r.getString("claimed_by"), r.getLong("created_at"), r.getLong("closed_at"), r.getString("closed_by"));
    }
}
