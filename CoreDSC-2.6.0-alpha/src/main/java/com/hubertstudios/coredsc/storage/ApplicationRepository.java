package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

                                                     
public final class ApplicationRepository {
    public record Application(
            long id, String minecraftUuid, String minecraftName, String discordUserId,
            String status, String channelId, long createdAt, long submittedAt,
            long decidedAt, String decidedBy, String decisionNote
    ) { }

    private final SQLiteStorage storage;
    public ApplicationRepository(SQLiteStorage storage) { this.storage = storage; }

    public CompletableFuture<Long> start(String uuid, String name, String discordId, long now) {
        return storage.transaction(connection -> {
            try (PreparedStatement existing = connection.prepareStatement(
                    "SELECT id FROM applications WHERE minecraft_uuid=? AND status IN ('DRAFT','PENDING') " +
                            "ORDER BY id DESC LIMIT 1")) {
                existing.setString(1, uuid);
                try (ResultSet r = existing.executeQuery()) {
                    if (r.next()) return r.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO applications (minecraft_uuid,minecraft_name,discord_user_id,status,created_at) " +
                            "VALUES (?,?,?,'DRAFT',?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, uuid); statement.setString(2, name);
                statement.setString(3, discordId == null ? "" : discordId); statement.setLong(4, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new IllegalStateException("SQLite did not return an application ID");
                    return keys.getLong(1);
                }
            }
        });
    }

    public CompletableFuture<Void> answer(long applicationId, String questionId, String answer, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO application_answers (application_id,question_id,answer,updated_at) VALUES (?,?,?,?) " +
                            "ON CONFLICT(application_id,question_id) DO UPDATE SET answer=excluded.answer,updated_at=excluded.updated_at")) {
                statement.setLong(1, applicationId); statement.setString(2, questionId);
                statement.setString(3, answer); statement.setLong(4, now); statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Map<String, String>> answers(long applicationId) {
        return storage.execute(connection -> {
            Map<String, String> values = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT question_id,answer FROM application_answers WHERE application_id=? ORDER BY question_id")) {
                statement.setLong(1, applicationId);
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) values.put(r.getString(1), r.getString(2));
                }
            }
            return values;
        });
    }

    public CompletableFuture<Optional<Application>> activeForUser(String uuid) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM applications WHERE minecraft_uuid=? AND status IN ('DRAFT','PENDING') " +
                            "ORDER BY id DESC LIMIT 1")) {
                statement.setString(1, uuid);
                try (ResultSet r = statement.executeQuery()) {
                    return r.next() ? Optional.of(read(r)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<Application>> find(long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM applications WHERE id=?")) {
                statement.setLong(1, id);
                try (ResultSet r = statement.executeQuery()) {
                    return r.next() ? Optional.of(read(r)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Boolean> submit(long id, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE applications SET status='PENDING', submitted_at=? WHERE id=? AND status='DRAFT'")) {
                statement.setLong(1, now); statement.setLong(2, id); return statement.executeUpdate() == 1;
            }
        });
    }


    public CompletableFuture<Boolean> revertSubmission(long id) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE applications SET status='DRAFT', submitted_at=0, channel_id='' " +
                            "WHERE id=? AND status='PENDING' AND decided_at=0")) {
                statement.setLong(1, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Boolean> decide(long id, String status, String by, String note, long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE applications SET status=?, decided_at=?, decided_by=?, decision_note=? " +
                            "WHERE id=? AND status='PENDING'")) {
                statement.setString(1, status); statement.setLong(2, now); statement.setString(3, by);
                statement.setString(4, note == null ? "" : note); statement.setLong(5, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Void> setChannel(long id, String channelId) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE applications SET channel_id=? WHERE id=?")) {
                statement.setString(1, channelId); statement.setLong(2, id); statement.executeUpdate();
            }
            return null;
        });
    }

    private static Application read(ResultSet r) throws Exception {
        return new Application(r.getLong("id"), r.getString("minecraft_uuid"), r.getString("minecraft_name"),
                r.getString("discord_user_id"), r.getString("status"), r.getString("channel_id"),
                r.getLong("created_at"), r.getLong("submitted_at"), r.getLong("decided_at"),
                r.getString("decided_by"), r.getString("decision_note"));
    }
}
