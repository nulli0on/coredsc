package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Moderation cases and linked appeals. */
public final class CaseRepository {
    public record ModerationCase(
            long id, String action, String targetUuid, String targetName, String executor,
            String reason, String duration, String source, String externalId,
            String status, long createdAt, long updatedAt
    ) { }
    public record Appeal(
            long id, long caseId, String minecraftUuid, String discordUserId, String message,
            String status, String channelId, long createdAt, long decidedAt,
            String decidedBy, String decisionNote
    ) { }

    private final SQLiteStorage storage;
    public CaseRepository(SQLiteStorage storage) { this.storage = storage; }

    public CompletableFuture<Long> createCase(
            String action, String targetUuid, String targetName, String executor,
            String reason, String duration, String source, String externalId, long now
    ) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO moderation_cases (action,target_uuid,target_name,executor,reason,duration," +
                            "source,external_id,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?, 'ACTIVE',?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, action); statement.setString(2, targetUuid == null ? "" : targetUuid);
                statement.setString(3, targetName == null ? "" : targetName);
                statement.setString(4, executor == null ? "" : executor);
                statement.setString(5, reason == null ? "" : reason);
                statement.setString(6, duration == null ? "" : duration);
                statement.setString(7, source == null ? "" : source);
                statement.setString(8, externalId == null ? "" : externalId);
                statement.setLong(9, now); statement.setLong(10, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new IllegalStateException("SQLite did not return a case ID");
                    return keys.getLong(1);
                }
            }
        });
    }

    public CompletableFuture<Optional<ModerationCase>> findCase(long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM moderation_cases WHERE id=?")) {
                statement.setLong(1, id);
                try (ResultSet r = statement.executeQuery()) {
                    return r.next() ? Optional.of(readCase(r)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<ModerationCase>> findCasesForTarget(String target, int limit) {
        return storage.execute(connection -> {
            List<ModerationCase> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM moderation_cases WHERE lower(target_name)=lower(?) OR target_uuid=? " +
                            "ORDER BY created_at DESC LIMIT ?")) {
                statement.setString(1, target); statement.setString(2, target); statement.setInt(3, Math.max(1, limit));
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) values.add(readCase(r));
                }
            }
            return values;
        });
    }

    public CompletableFuture<Boolean> updateCaseStatus(long id, String status, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE moderation_cases SET status=?, updated_at=? WHERE id=?")) {
                statement.setString(1, status); statement.setLong(2, now); statement.setLong(3, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Long> createAppeal(
            long caseId, String minecraftUuid, String discordUserId, String message, long now
    ) {
        return storage.transaction(connection -> {
            try (PreparedStatement existing = connection.prepareStatement(
                    "SELECT 1 FROM appeals WHERE case_id=? AND minecraft_uuid=? AND status='PENDING' LIMIT 1")) {
                existing.setLong(1, caseId); existing.setString(2, minecraftUuid);
                try (ResultSet r = existing.executeQuery()) {
                    if (r.next()) throw new IllegalStateException("A pending appeal already exists for this case");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO appeals (case_id,minecraft_uuid,discord_user_id,message,status,created_at) " +
                            "VALUES (?,?,?,?, 'PENDING',?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, caseId); statement.setString(2, minecraftUuid);
                statement.setString(3, discordUserId == null ? "" : discordUserId);
                statement.setString(4, message); statement.setLong(5, now); statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new IllegalStateException("SQLite did not return an appeal ID");
                    return keys.getLong(1);
                }
            }
        });
    }


    public CompletableFuture<Void> deletePendingAppeal(long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM appeals WHERE id=? AND status='PENDING'")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<Appeal>> findAppeal(long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM appeals WHERE id=?")) {
                statement.setLong(1, id);
                try (ResultSet r = statement.executeQuery()) {
                    return r.next() ? Optional.of(readAppeal(r)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<List<Appeal>> findAppealsForUser(String minecraftUuid) {
        return storage.execute(connection -> {
            List<Appeal> values = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM appeals WHERE minecraft_uuid=? ORDER BY created_at DESC")) {
                statement.setString(1, minecraftUuid);
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) values.add(readAppeal(r));
                }
            }
            return values;
        });
    }

    public CompletableFuture<Boolean> decideAppeal(
            long id, String status, String decidedBy, String note, long now
    ) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE appeals SET status=?, decided_at=?, decided_by=?, decision_note=? " +
                            "WHERE id=? AND status='PENDING'")) {
                statement.setString(1, status); statement.setLong(2, now);
                statement.setString(3, decidedBy); statement.setString(4, note == null ? "" : note);
                statement.setLong(5, id); return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Void> setAppealChannel(long id, String channelId) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE appeals SET channel_id=? WHERE id=?")) {
                statement.setString(1, channelId); statement.setLong(2, id); statement.executeUpdate();
            }
            return null;
        });
    }

    private static ModerationCase readCase(ResultSet r) throws Exception {
        return new ModerationCase(r.getLong("id"), r.getString("action"), r.getString("target_uuid"),
                r.getString("target_name"), r.getString("executor"), r.getString("reason"),
                r.getString("duration"), r.getString("source"), r.getString("external_id"),
                r.getString("status"), r.getLong("created_at"), r.getLong("updated_at"));
    }

    private static Appeal readAppeal(ResultSet r) throws Exception {
        return new Appeal(r.getLong("id"), r.getLong("case_id"), r.getString("minecraft_uuid"),
                r.getString("discord_user_id"), r.getString("message"), r.getString("status"),
                r.getString("channel_id"), r.getLong("created_at"), r.getLong("decided_at"),
                r.getString("decided_by"), r.getString("decision_note"));
    }
}
