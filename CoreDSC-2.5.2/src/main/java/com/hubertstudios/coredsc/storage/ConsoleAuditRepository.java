package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

                                                                     
public final class ConsoleAuditRepository {
    private final SQLiteStorage storage;

    public ConsoleAuditRepository(SQLiteStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<Void> append(
            String discordUserId,
            String discordUserName,
            String command,
            String mode,
            String outcome,
            String detail,
            long createdAt
    ) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO console_audit (discord_user_id,discord_user_name,command,mode,outcome,detail,created_at) " +
                            "VALUES (?,?,?,?,?,?,?)")) {
                statement.setString(1, safe(discordUserId, 32));
                statement.setString(2, safe(discordUserName, 128));
                statement.setString(3, safe(command, 1000));
                statement.setString(4, safe(mode, 32));
                statement.setString(5, safe(outcome, 64));
                statement.setString(6, safe(detail, 1000));
                statement.setLong(7, createdAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Integer> deleteOlderThan(long cutoff) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM console_audit WHERE created_at<?")) {
                statement.setLong(1, cutoff);
                return statement.executeUpdate();
            }
        });
    }

    private static String safe(String value, int limit) {
        String output = value == null ? "" : value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ');
        return output.length() <= limit ? output : output.substring(0, limit);
    }
}
