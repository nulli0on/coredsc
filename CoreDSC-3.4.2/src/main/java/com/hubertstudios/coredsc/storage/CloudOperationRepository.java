package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Restart-safe idempotency and reversible operation state on the SQLite funnel. */
public final class CloudOperationRepository {
    public record StoredResult(String operation, String requestFingerprint, String resultJson, long createdAt) { }
    public record ChannelSnapshot(
            String channelId,
            String permissionName,
            boolean overwriteExisted,
            long allowedRaw,
            long deniedRaw,
            int slowmodeSeconds,
            String operationId,
            long createdAt
    ) { }

    private final SQLiteStorage storage;

    public CloudOperationRepository(SQLiteStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<Optional<StoredResult>> findResult(String idempotencyKey) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT operation,request_fingerprint,result_json,created_at FROM cloud_operation_results WHERE idempotency_key=?")) {
                statement.setString(1, idempotencyKey);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(new StoredResult(
                            result.getString("operation"), result.getString("request_fingerprint"),
                            result.getString("result_json"), result.getLong("created_at"))) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Void> storeResult(
            String idempotencyKey,
            String operation,
            String requestFingerprint,
            String resultJson,
            long createdAt
    ) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO cloud_operation_results"
                            + "(idempotency_key,operation,request_fingerprint,result_json,created_at) "
                            + "VALUES (?,?,?,?,?)")) {
                statement.setString(1, safe(idempotencyKey, 100));
                statement.setString(2, safe(operation, 80));
                statement.setString(3, safeFingerprint(requestFingerprint));
                statement.setString(4, safeJson(resultJson));
                statement.setLong(5, createdAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> saveChannelSnapshot(ChannelSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO channel_operation_snapshots"
                            + "(channel_id,permission_name,overwrite_existed,allowed_raw,denied_raw,slowmode_seconds,operation_id,created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(channel_id,permission_name) DO UPDATE SET "
                            + "overwrite_existed=excluded.overwrite_existed,allowed_raw=excluded.allowed_raw,"
                            + "denied_raw=excluded.denied_raw,slowmode_seconds=excluded.slowmode_seconds,"
                            + "operation_id=excluded.operation_id,created_at=excluded.created_at")) {
                statement.setString(1, safe(snapshot.channelId(), 32));
                statement.setString(2, safe(snapshot.permissionName(), 32));
                statement.setInt(3, snapshot.overwriteExisted() ? 1 : 0);
                statement.setLong(4, snapshot.allowedRaw());
                statement.setLong(5, snapshot.deniedRaw());
                statement.setInt(6, snapshot.slowmodeSeconds());
                statement.setString(7, safe(snapshot.operationId(), 100));
                statement.setLong(8, snapshot.createdAt());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<ChannelSnapshot>> findChannelSnapshot(
            String channelId,
            String permissionName
    ) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT channel_id,permission_name,overwrite_existed,allowed_raw,denied_raw,"
                            + "slowmode_seconds,operation_id,created_at FROM channel_operation_snapshots "
                            + "WHERE channel_id=? AND permission_name=?")) {
                statement.setString(1, channelId);
                statement.setString(2, permissionName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(new ChannelSnapshot(
                            result.getString("channel_id"), result.getString("permission_name"),
                            result.getInt("overwrite_existed") != 0, result.getLong("allowed_raw"),
                            result.getLong("denied_raw"), result.getInt("slowmode_seconds"),
                            result.getString("operation_id"), result.getLong("created_at")))
                            : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Void> deleteChannelSnapshot(String channelId, String permissionName) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM channel_operation_snapshots WHERE channel_id=? AND permission_name=?")) {
                statement.setString(1, channelId);
                statement.setString(2, permissionName);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> putState(String key, String json, long updatedAt) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO cloud_runtime_state(state_key,state_json,updated_at) VALUES (?,?,?) "
                            + "ON CONFLICT(state_key) DO UPDATE SET state_json=excluded.state_json,updated_at=excluded.updated_at")) {
                statement.setString(1, safe(key, 80));
                statement.setString(2, safeJson(json));
                statement.setLong(3, updatedAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<String>> getState(String key) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT state_json FROM cloud_runtime_state WHERE state_key=?")) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Map<String, String>> listStates(String prefix) {
        return storage.execute(connection -> {
            Map<String, String> values = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT state_key,state_json FROM cloud_runtime_state WHERE state_key LIKE ? ORDER BY state_key")) {
                statement.setString(1, safe(prefix, 70) + "%");
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.put(result.getString(1), result.getString(2));
                }
            }
            return Map.copyOf(values);
        });
    }

    public CompletableFuture<Void> removeState(String key) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM cloud_runtime_state WHERE state_key=?")) {
                statement.setString(1, key);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Integer> deleteResultsOlderThan(long cutoff) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM cloud_operation_results WHERE created_at<?")) {
                statement.setLong(1, cutoff);
                return statement.executeUpdate();
            }
        });
    }

    private static String safeFingerprint(String value) {
        String fingerprint = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Cloud request fingerprint must be a SHA-256 value");
        }
        return fingerprint;
    }

    private static String safe(String value, int maximum) {
        String text = value == null ? "" : value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ');
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    private static String safeJson(String value) {
        if (value == null || value.isBlank()) return "{}";
        if (value.length() > 262_144) {
            throw new IllegalArgumentException("Cloud operation state exceeds 256 KiB");
        }
        return value;
    }
}
