package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;


public final class OutboxRepository {
    public record OutboxMessage(
            long id, String dedupeKey, String channelId, String message, int priority,
            String status, int attempts, long nextAttemptAt, long createdAt, String lastError
    ) { }

    private final SQLiteStorage storage;
    public OutboxRepository(SQLiteStorage storage) { this.storage = storage; }

    public CompletableFuture<Long> enqueue(
            String dedupeKey, String channelId, String message, int priority, long now, int maximumPending
    ) {
        return storage.transaction(connection -> {
            if (maximumPending > 0) {
                try (Statement count = connection.createStatement();
                     ResultSet result = count.executeQuery(
                             "SELECT COUNT(*) FROM discord_outbox WHERE status IN ('PENDING','SENDING','FAILED')")) {
                    if (result.next() && result.getInt(1) >= maximumPending) {
                        throw new IllegalStateException("Discord delivery queue is full");
                    }
                }
            }
            if (dedupeKey != null && !dedupeKey.isBlank()) {
                try (PreparedStatement existing = connection.prepareStatement(
                        "SELECT id FROM discord_outbox WHERE dedupe_key=? AND status IN ('PENDING','SENDING') LIMIT 1")) {
                    existing.setString(1, dedupeKey);
                    try (ResultSet r = existing.executeQuery()) {
                        if (r.next()) return r.getLong(1);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO discord_outbox (dedupe_key,channel_id,message,priority,status,attempts," +
                            "next_attempt_at,created_at,last_error) VALUES (?,?,?,?,'PENDING',0,?,?,'')",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, dedupeKey == null ? "" : dedupeKey);
                statement.setString(2, channelId); statement.setString(3, message);
                statement.setInt(4, priority); statement.setLong(5, now); statement.setLong(6, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new IllegalStateException("SQLite did not return an outbox ID");
                    return keys.getLong(1);
                }
            }
        });
    }


    public CompletableFuture<Integer> recoverInterrupted() {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE discord_outbox SET status='PENDING',next_attempt_at=?,last_error=" +
                            "CASE WHEN last_error='' THEN 'Recovered after restart' ELSE last_error END " +
                            "WHERE status='SENDING'")) {
                statement.setLong(1, System.currentTimeMillis());
                return statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Integer> cleanupSent(long olderThan) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM discord_outbox WHERE status='SENT' AND created_at<?")) {
                statement.setLong(1, olderThan);
                return statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<List<OutboxMessage>> due(long now, int limit) {
        return storage.execute(connection -> {
            List<OutboxMessage> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM discord_outbox WHERE status='PENDING' AND next_attempt_at<=? " +
                            "ORDER BY priority DESC, id ASC LIMIT ?")) {
                statement.setLong(1, now); statement.setInt(2, Math.max(1, limit));
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) values.add(read(r));
                }
            }
            return values;
        });
    }

    public CompletableFuture<Boolean> claim(long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE discord_outbox SET status='SENDING' WHERE id=? AND status='PENDING'")) {
                statement.setLong(1, id); return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Void> delivered(long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE discord_outbox SET status='SENT', last_error='' WHERE id=?")) {
                statement.setLong(1, id); statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> failed(long id, int attempts, long nextAttempt, String error, int maxAttempts) {
        return storage.execute(connection -> {
            String status = attempts >= maxAttempts ? "FAILED" : "PENDING";
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE discord_outbox SET status=?,attempts=?,next_attempt_at=?,last_error=? WHERE id=?")) {
                statement.setString(1, status); statement.setInt(2, attempts);
                statement.setLong(3, nextAttempt); statement.setString(4, error); statement.setLong(5, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<int[]> counts() {
        return storage.execute(connection -> {
            int pending = 0, failed = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet r = statement.executeQuery(
                         "SELECT status,COUNT(*) FROM discord_outbox GROUP BY status")) {
                while (r.next()) {
                    if ("PENDING".equals(r.getString(1)) || "SENDING".equals(r.getString(1))) pending += r.getInt(2);
                    if ("FAILED".equals(r.getString(1))) failed += r.getInt(2);
                }
            }
            return new int[]{pending, failed};
        });
    }

    public CompletableFuture<Integer> retryFailed(long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE discord_outbox SET status='PENDING',attempts=0,next_attempt_at=?,last_error='' WHERE status='FAILED'")) {
                statement.setLong(1, now); return statement.executeUpdate();
            }
        });
    }


    public CompletableFuture<Integer> clearFailed() {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM discord_outbox WHERE status='FAILED'")) {
                return statement.executeUpdate();
            }
        });
    }

    private static OutboxMessage read(ResultSet r) throws Exception {
        return new OutboxMessage(r.getLong("id"), r.getString("dedupe_key"), r.getString("channel_id"),
                r.getString("message"), r.getInt("priority"), r.getString("status"),
                r.getInt("attempts"), r.getLong("next_attempt_at"), r.getLong("created_at"), r.getString("last_error"));
    }
}
