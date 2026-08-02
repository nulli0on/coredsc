package com.hubertstudios.coredsc.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CompletableFuture;


public final class LinkSecurityRepository {
    private final SQLiteStorage storage;
    public LinkSecurityRepository(SQLiteStorage storage) { this.storage = storage; }

    public CompletableFuture<Boolean> registerAttempt(String ipHash, long now, long windowMillis, int maximum) {
        return storage.transaction(connection -> {
            try (PreparedStatement cleanup = connection.prepareStatement(
                    "DELETE FROM link_security_attempts WHERE attempted_at<?")) {
                cleanup.setLong(1, now - Math.max(windowMillis, 86_400_000L)); cleanup.executeUpdate();
            }
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM link_security_attempts WHERE ip_hash=? AND attempted_at>=?")) {
                count.setString(1, ipHash); count.setLong(2, now - windowMillis);
                try (ResultSet r = count.executeQuery()) {
                    if (r.next() && r.getInt(1) >= maximum) return false;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO link_security_attempts (ip_hash,attempted_at) VALUES (?,?)")) {
                insert.setString(1, ipHash); insert.setLong(2, now); insert.executeUpdate();
            }
            return true;
        });
    }
}
