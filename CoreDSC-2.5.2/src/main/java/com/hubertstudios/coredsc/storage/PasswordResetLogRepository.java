package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Atomic audit/cooldown storage for AuthMe password resets. */
public final class PasswordResetLogRepository {

    public record ResetReservation(boolean allowed, long remainingMillis, long resetAt) { }

    private final SQLiteStorage storage;

    public PasswordResetLogRepository(SQLiteStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<ResetReservation> reserveReset(
            String minecraftUuid,
            String discordUserId,
            long now,
            long cooldownMillis
    ) {
        return storage.transaction(connection -> {
            long lastReset = -1L;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT reset_at FROM password_reset_logs " +
                            "WHERE minecraft_uuid = ? ORDER BY reset_at DESC LIMIT 1")) {
                select.setString(1, minecraftUuid);
                try (ResultSet resultSet = select.executeQuery()) {
                    if (resultSet.next()) {
                        lastReset = resultSet.getLong("reset_at");
                    }
                }
            }

            if (cooldownMillis > 0L && lastReset >= 0L) {
                long elapsed = Math.max(0L, now - lastReset);
                if (elapsed < cooldownMillis) {
                    return new ResetReservation(false, cooldownMillis - elapsed, 0L);
                }
            }

            long reservationTime = lastReset >= now ? lastReset + 1L : now;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO password_reset_logs (minecraft_uuid, discord_user_id, reset_at) " +
                            "VALUES (?, ?, ?)")) {
                insert.setString(1, minecraftUuid);
                insert.setString(2, discordUserId);
                insert.setLong(3, reservationTime);
                insert.executeUpdate();
            }
            return new ResetReservation(true, 0L, reservationTime);
        });
    }

    public CompletableFuture<Void> removeReservation(String minecraftUuid, long resetAt) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM password_reset_logs WHERE minecraft_uuid = ? AND reset_at = ?")) {
                statement.setString(1, minecraftUuid);
                statement.setLong(2, resetAt);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
