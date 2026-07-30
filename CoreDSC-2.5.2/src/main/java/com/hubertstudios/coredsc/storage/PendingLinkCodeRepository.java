package com.hubertstudios.coredsc.storage;

import com.hubertstudios.coredsc.storage.LinkedAccountRepository.LinkedAccount;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Issues and atomically consumes temporary account-linking codes. */
public final class PendingLinkCodeRepository {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum IssueStatus {
        ISSUED,
        ALREADY_LINKED
    }

    public record IssueResult(IssueStatus status, String code, long expiresAt) { }

    public enum LinkStatus {
        LINKED,
        INVALID_OR_EXPIRED,
        MINECRAFT_ALREADY_LINKED,
        DISCORD_ALREADY_LINKED
    }

    public record LinkResult(LinkStatus status, LinkedAccount account) { }

    private final SQLiteStorage storage;

    public PendingLinkCodeRepository(SQLiteStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public CompletableFuture<IssueResult> issueCode(
            String minecraftUuid,
            String minecraftName,
            long expiresAt,
            int requestedLength
    ) {
        int length = Math.max(6, Math.min(16, requestedLength));
        return storage.transaction(connection -> {
            if (LinkedAccountRepository.selectByMinecraft(connection, minecraftUuid) != null) {
                return new IssueResult(IssueStatus.ALREADY_LINKED, null, 0L);
            }

            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM pending_link_codes WHERE minecraft_uuid = ?")) {
                delete.setString(1, minecraftUuid);
                delete.executeUpdate();
            }

            SQLException lastCollision = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                String code = randomCode(length);
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO pending_link_codes " +
                                "(code, minecraft_uuid, minecraft_name, expires_at) VALUES (?, ?, ?, ?)")) {
                    insert.setString(1, code);
                    insert.setString(2, minecraftUuid);
                    insert.setString(3, minecraftName == null ? "" : minecraftName);
                    insert.setLong(4, expiresAt);
                    insert.executeUpdate();
                    return new IssueResult(IssueStatus.ISSUED, code, expiresAt);
                } catch (SQLException collision) {
                    if (collision.getErrorCode() != 19) {
                        throw collision;
                    }
                    lastCollision = collision;
                }
            }
            throw lastCollision == null
                    ? new SQLException("Could not generate a unique link code")
                    : lastCollision;
        });
    }

    public CompletableFuture<LinkResult> consumeAndLink(
            String rawCode,
            String discordUserId,
            long now
    ) {
        String code = normalizeCode(rawCode);
        if (!isValidCode(code)) {
            return CompletableFuture.completedFuture(
                    new LinkResult(LinkStatus.INVALID_OR_EXPIRED, null));
        }
        return storage.transaction(connection -> {
            PendingCode pending = selectPending(connection, code);
            if (pending == null || pending.expiresAt() <= now) {
                deleteCode(connection, code);
                return new LinkResult(LinkStatus.INVALID_OR_EXPIRED, null);
            }

            if (LinkedAccountRepository.selectByMinecraft(connection, pending.minecraftUuid()) != null) {
                deleteCode(connection, code);
                return new LinkResult(LinkStatus.MINECRAFT_ALREADY_LINKED, null);
            }
            if (LinkedAccountRepository.selectByDiscord(connection, discordUserId) != null) {
                return new LinkResult(LinkStatus.DISCORD_ALREADY_LINKED, null);
            }

            LinkedAccount account = new LinkedAccount(
                    pending.minecraftUuid(),
                    discordUserId,
                    pending.minecraftName(),
                    now
            );
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO linked_accounts " +
                            "(minecraft_uuid, discord_user_id, minecraft_name, linked_at) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, account.minecraftUuid());
                insert.setString(2, account.discordUserId());
                insert.setString(3, account.minecraftName());
                insert.setLong(4, account.linkedAt());
                insert.executeUpdate();
            }
            deleteCode(connection, code);
            return new LinkResult(LinkStatus.LINKED, account);
        });
    }

    public CompletableFuture<Integer> deleteExpired(long now) {
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM pending_link_codes WHERE expires_at <= ?")) {
                statement.setLong(1, now);
                return statement.executeUpdate();
            }
        });
    }

    private static PendingCode selectPending(java.sql.Connection connection, String code) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT code, minecraft_uuid, minecraft_name, expires_at " +
                        "FROM pending_link_codes WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new PendingCode(
                        resultSet.getString("code"),
                        resultSet.getString("minecraft_uuid"),
                        resultSet.getString("minecraft_name"),
                        resultSet.getLong("expires_at")
                );
            }
        }
    }

    private static void deleteCode(java.sql.Connection connection, String code) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM pending_link_codes WHERE code = ?")) {
            statement.setString(1, code);
            statement.executeUpdate();
        }
    }

    private static String randomCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return builder.toString();
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean isValidCode(String code) {
        if (code.length() < 6 || code.length() > 16) {
            return false;
        }
        for (int index = 0; index < code.length(); index++) {
            if (CODE_ALPHABET.indexOf(code.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }

    private record PendingCode(String code, String minecraftUuid, String minecraftName, long expiresAt) { }
}
