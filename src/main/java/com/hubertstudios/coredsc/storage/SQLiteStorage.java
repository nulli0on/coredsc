package com.hubertstudios.coredsc.storage;

import com.hubertstudios.coredsc.CoreDSCPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Owns the SQLite connection and confines every JDBC operation to one
 * dedicated worker thread. SQLite connections must not be shared across
 * Bukkit, JDA and scheduler threads.
 */
public final class SQLiteStorage {

    public enum State {
        NEW,
        INITIALIZING,
        READY,
        FAILED,
        CLOSING,
        CLOSED
    }

    @FunctionalInterface
    public interface SqlOperation<T> {
        T execute(Connection connection) throws Exception;
    }

    private final CoreDSCPlugin plugin;
    private final ExecutorService executor;
    private volatile State state = State.NEW;
    private volatile String failureReason = "";
    private Connection connection;
    private CompletableFuture<Void> closeFuture;

    public SQLiteStorage(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CoreDSC-SQLite");
            thread.setDaemon(true);
            return thread;
        });
    }

    public State getState() {
        return state;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public synchronized CompletableFuture<Void> initAsync() {
        if (state == State.READY) {
            return CompletableFuture.completedFuture(null);
        }
        if (state != State.NEW) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Storage cannot initialise from state " + state));
        }

        state = State.INITIALIZING;
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    File dataFolder = plugin.getDataFolder();
                    if (!dataFolder.exists() && !dataFolder.mkdirs() && !dataFolder.isDirectory()) {
                        throw new SQLException("Could not create plugin data directory: " + dataFolder);
                    }

                    File databaseFile = new File(dataFolder, "data.db");
                    try {
                        Class.forName("org.sqlite.JDBC");
                    } catch (ClassNotFoundException exception) {
                        throw new SQLException(
                                "SQLite JDBC driver is missing from the shaded plugin JAR", exception);
                    }
                    connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
                    configureConnection(connection);
                    migrate(connection);
                    state = State.READY;
                    future.complete(null);
                } catch (Throwable throwable) {
                    failureReason = rootMessage(throwable);
                    state = State.FAILED;
                    closeConnectionQuietly();
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException exception) {
            failureReason = rootMessage(exception);
            state = State.FAILED;
            future.completeExceptionally(exception);
        }
        return future;
    }

    public <T> CompletableFuture<T> execute(SqlOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (state != State.READY) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Storage is not ready (state=" + state + ")"));
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                if (connection == null) {
                    future.completeExceptionally(
                            new IllegalStateException("Storage closed before operation could run"));
                    return;
                }
                try {
                    future.complete(operation.execute(connection));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException exception) {
            future.completeExceptionally(exception);
        }
        return future;
    }

    public <T> CompletableFuture<T> transaction(SqlOperation<T> operation) {
        return execute(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            Exception operationFailure = null;
            Error operationError = null;
            connection.setAutoCommit(false);
            try {
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                operationFailure = exception;
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } catch (Error error) {
                operationError = error;
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    error.addSuppressed(rollbackFailure);
                }
                throw error;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException restoreFailure) {
                    if (operationFailure != null) {
                        operationFailure.addSuppressed(restoreFailure);
                    } else if (operationError != null) {
                        operationError.addSuppressed(restoreFailure);
                    } else {
                        throw restoreFailure;
                    }
                }
            }
        });
    }

    /**
     * Queues connection closure after all already-submitted database work.
     * This method never blocks the Minecraft main thread.
     */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (state == State.CLOSED) {
            return CompletableFuture.completedFuture(null);
        }
        if (state == State.CLOSING) {
            return closeFuture == null
                    ? CompletableFuture.completedFuture(null)
                    : closeFuture;
        }

        state = State.CLOSING;
        CompletableFuture<Void> future = new CompletableFuture<>();
        closeFuture = future;
        try {
            executor.execute(() -> {
                try {
                    closeConnectionQuietly();
                    state = State.CLOSED;
                    future.complete(null);
                } finally {
                    executor.shutdown();
                }
            });
        } catch (RejectedExecutionException exception) {
            closeConnectionQuietly();
            state = State.CLOSED;
            executor.shutdownNow();
            future.completeExceptionally(exception);
        }
        return future;
    }

    private static void configureConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
        connection.setAutoCommit(true);
    }

    private static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS linked_accounts (" +
                            "minecraft_uuid TEXT PRIMARY KEY, " +
                            "discord_user_id TEXT NOT NULL, " +
                            "minecraft_name TEXT NOT NULL DEFAULT '', " +
                            "linked_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS pending_link_codes (" +
                            "code TEXT PRIMARY KEY, " +
                            "minecraft_uuid TEXT NOT NULL, " +
                            "minecraft_name TEXT NOT NULL DEFAULT '', " +
                            "expires_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS password_reset_logs (" +
                            "minecraft_uuid TEXT NOT NULL, " +
                            "discord_user_id TEXT NOT NULL, " +
                            "reset_at INTEGER NOT NULL, " +
                            "PRIMARY KEY (minecraft_uuid, reset_at)" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS tickets (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "minecraft_uuid TEXT NOT NULL, " +
                            "minecraft_name TEXT NOT NULL DEFAULT '', " +
                            "discord_user_id TEXT NOT NULL, " +
                            "reason TEXT NOT NULL, " +
                            "message TEXT NOT NULL, " +
                            "status TEXT NOT NULL, " +
                            "channel_id TEXT NOT NULL DEFAULT '', " +
                            "created_at INTEGER NOT NULL, " +
                            "closed_at INTEGER NOT NULL DEFAULT 0, " +
                            "closed_by TEXT NOT NULL DEFAULT ''" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS support_messages (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "item_type TEXT NOT NULL, " +
                            "item_id INTEGER NOT NULL, " +
                            "sender_platform TEXT NOT NULL, " +
                            "sender_id TEXT NOT NULL DEFAULT '', " +
                            "sender_name TEXT NOT NULL DEFAULT '', " +
                            "message TEXT NOT NULL, " +
                            "created_at INTEGER NOT NULL, " +
                            "minecraft_delivered INTEGER NOT NULL DEFAULT 0, " +
                            "discord_delivered INTEGER NOT NULL DEFAULT 0" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS reports (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "reporter_uuid TEXT NOT NULL, " +
                            "reporter_name TEXT NOT NULL DEFAULT '', " +
                            "reporter_discord_id TEXT NOT NULL, " +
                            "target_uuid TEXT NOT NULL, " +
                            "target_name TEXT NOT NULL DEFAULT '', " +
                            "target_discord_id TEXT NOT NULL DEFAULT '', " +
                            "reason TEXT NOT NULL, " +
                            "message TEXT NOT NULL DEFAULT '', " +
                            "status TEXT NOT NULL, " +
                            "priority TEXT NOT NULL DEFAULT 'NORMAL', " +
                            "channel_id TEXT NOT NULL DEFAULT '', " +
                            "claimed_by TEXT NOT NULL DEFAULT '', " +
                            "created_at INTEGER NOT NULL, " +
                            "closed_at INTEGER NOT NULL DEFAULT 0, " +
                            "closed_by TEXT NOT NULL DEFAULT ''" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS moderation_cases (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "action TEXT NOT NULL, " +
                            "target_uuid TEXT NOT NULL DEFAULT '', " +
                            "target_name TEXT NOT NULL DEFAULT '', " +
                            "executor TEXT NOT NULL DEFAULT '', " +
                            "reason TEXT NOT NULL DEFAULT '', " +
                            "duration TEXT NOT NULL DEFAULT '', " +
                            "source TEXT NOT NULL DEFAULT '', " +
                            "external_id TEXT NOT NULL DEFAULT '', " +
                            "status TEXT NOT NULL DEFAULT 'ACTIVE', " +
                            "created_at INTEGER NOT NULL, " +
                            "updated_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS appeals (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "case_id INTEGER NOT NULL, " +
                            "minecraft_uuid TEXT NOT NULL, " +
                            "discord_user_id TEXT NOT NULL DEFAULT '', " +
                            "message TEXT NOT NULL, " +
                            "status TEXT NOT NULL DEFAULT 'PENDING', " +
                            "channel_id TEXT NOT NULL DEFAULT '', " +
                            "created_at INTEGER NOT NULL, " +
                            "decided_at INTEGER NOT NULL DEFAULT 0, " +
                            "decided_by TEXT NOT NULL DEFAULT '', " +
                            "decision_note TEXT NOT NULL DEFAULT ''" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS applications (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "minecraft_uuid TEXT NOT NULL, " +
                            "minecraft_name TEXT NOT NULL DEFAULT '', " +
                            "discord_user_id TEXT NOT NULL DEFAULT '', " +
                            "status TEXT NOT NULL DEFAULT 'DRAFT', " +
                            "channel_id TEXT NOT NULL DEFAULT '', " +
                            "created_at INTEGER NOT NULL, " +
                            "submitted_at INTEGER NOT NULL DEFAULT 0, " +
                            "decided_at INTEGER NOT NULL DEFAULT 0, " +
                            "decided_by TEXT NOT NULL DEFAULT '', " +
                            "decision_note TEXT NOT NULL DEFAULT ''" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS application_answers (" +
                            "application_id INTEGER NOT NULL, " +
                            "question_id TEXT NOT NULL, " +
                            "answer TEXT NOT NULL, " +
                            "updated_at INTEGER NOT NULL, " +
                            "PRIMARY KEY (application_id, question_id)" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS discord_outbox (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "dedupe_key TEXT NOT NULL DEFAULT '', " +
                            "channel_id TEXT NOT NULL, " +
                            "message TEXT NOT NULL, " +
                            "priority INTEGER NOT NULL DEFAULT 0, " +
                            "status TEXT NOT NULL DEFAULT 'PENDING', " +
                            "attempts INTEGER NOT NULL DEFAULT 0, " +
                            "next_attempt_at INTEGER NOT NULL, " +
                            "created_at INTEGER NOT NULL, " +
                            "last_error TEXT NOT NULL DEFAULT ''" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS link_security_attempts (" +
                            "ip_hash TEXT NOT NULL, " +
                            "attempted_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS workflow_runs (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "workflow_id TEXT NOT NULL, " +
                            "trigger_type TEXT NOT NULL, " +
                            "subject_id TEXT NOT NULL DEFAULT '', " +
                            "status TEXT NOT NULL, " +
                            "detail TEXT NOT NULL DEFAULT '', " +
                            "created_at INTEGER NOT NULL" +
                            ")");


            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS reward_claims (" +
                            "claim_key TEXT PRIMARY KEY, " +
                            "reward_type TEXT NOT NULL, " +
                            "minecraft_uuid TEXT NOT NULL, " +
                            "discord_user_id TEXT NOT NULL DEFAULT '', " +
                            "next_step INTEGER NOT NULL DEFAULT 0, " +
                            "inflight_step INTEGER NOT NULL DEFAULT -1, " +
                            "total_steps INTEGER NOT NULL DEFAULT 0, " +
                            "status TEXT NOT NULL DEFAULT 'PENDING', " +
                            "created_at INTEGER NOT NULL, " +
                            "updated_at INTEGER NOT NULL, " +
                            "last_error TEXT NOT NULL DEFAULT ''" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS booster_states (" +
                            "discord_user_id TEXT PRIMARY KEY, " +
                            "minecraft_uuid TEXT NOT NULL DEFAULT '', " +
                            "active INTEGER NOT NULL DEFAULT 0, " +
                            "boosted_at INTEGER NOT NULL DEFAULT 0, " +
                            "last_reward_period INTEGER NOT NULL DEFAULT -1, " +
                            "updated_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS nickname_states (" +
                            "discord_user_id TEXT PRIMARY KEY, " +
                            "original_nickname TEXT NOT NULL DEFAULT '', " +
                            "synced_nickname TEXT NOT NULL DEFAULT '', " +
                            "updated_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS console_audit (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "discord_user_id TEXT NOT NULL DEFAULT '', " +
                            "discord_user_name TEXT NOT NULL DEFAULT '', " +
                            "command TEXT NOT NULL, " +
                            "mode TEXT NOT NULL, " +
                            "outcome TEXT NOT NULL, " +
                            "detail TEXT NOT NULL DEFAULT '', " +
                            "created_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS ban_sync_state (" +
                            "minecraft_uuid TEXT PRIMARY KEY, " +
                            "minecraft_name TEXT NOT NULL DEFAULT '', " +
                            "discord_user_id TEXT NOT NULL UNIQUE, " +
                            "minecraft_managed INTEGER NOT NULL DEFAULT 0, " +
                            "discord_managed INTEGER NOT NULL DEFAULT 0, " +
                            "reason TEXT NOT NULL DEFAULT '', " +
                            "updated_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS link_enforcement (" +
                            "minecraft_uuid TEXT PRIMARY KEY, " +
                            "first_seen_at INTEGER NOT NULL, " +
                            "last_reminder_at INTEGER NOT NULL DEFAULT 0" +
                            ")");
        }

        addColumnIfMissing(connection, "linked_accounts", "minecraft_name",
                "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(connection, "linked_accounts", "linked_at",
                "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "pending_link_codes", "minecraft_name",
                "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(connection, "pending_link_codes", "expires_at",
                "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "tickets", "claimed_by",
                "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(connection, "tickets", "priority",
                "TEXT NOT NULL DEFAULT 'NORMAL'");
        addColumnIfMissing(connection, "tickets", "updated_at",
                "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "reward_claims", "inflight_step",
                "INTEGER NOT NULL DEFAULT -1");

        // Clean legacy duplicates before adding one-to-one uniqueness constraints.
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "DELETE FROM linked_accounts WHERE rowid NOT IN (" +
                            "SELECT MAX(rowid) FROM linked_accounts GROUP BY discord_user_id)");
            statement.executeUpdate(
                    "DELETE FROM pending_link_codes WHERE rowid NOT IN (" +
                            "SELECT MAX(rowid) FROM pending_link_codes GROUP BY minecraft_uuid)");

            statement.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_linked_accounts_discord " +
                            "ON linked_accounts(discord_user_id)");
            statement.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_pending_codes_minecraft " +
                            "ON pending_link_codes(minecraft_uuid)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_pending_codes_expiry " +
                            "ON pending_link_codes(expires_at)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_reset_logs_minecraft_time " +
                            "ON password_reset_logs(minecraft_uuid, reset_at DESC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_tickets_user_status " +
                            "ON tickets(discord_user_id, status, created_at DESC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_tickets_channel " +
                            "ON tickets(channel_id)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_support_messages_item " +
                            "ON support_messages(item_type, item_id, created_at)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_reports_reporter_status " +
                            "ON reports(reporter_uuid, status, created_at DESC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_reports_channel " +
                            "ON reports(channel_id)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_cases_target_time " +
                            "ON moderation_cases(target_name, created_at DESC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_appeals_case_status " +
                            "ON appeals(case_id, status)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_applications_user_status " +
                            "ON applications(minecraft_uuid, status, created_at DESC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_outbox_status_time " +
                            "ON discord_outbox(status, next_attempt_at, priority DESC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_link_security_time " +
                            "ON link_security_attempts(ip_hash, attempted_at DESC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_reward_claims_subject " +
                            "ON reward_claims(minecraft_uuid, reward_type, created_at DESC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_console_audit_time " +
                            "ON console_audit(created_at DESC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_ban_sync_discord " +
                            "ON ban_sync_state(discord_user_id)");
            statement.execute("PRAGMA user_version = 10");
        }
    }

    private static void addColumnIfMissing(
            Connection connection,
            String table,
            String column,
            String declaration
    ) throws SQLException {
        if (hasColumn(connection, table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN "
                    + column + " " + declaration);
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private void closeConnectionQuietly() {
        Connection current = connection;
        connection = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to close SQLite cleanly: " + rootMessage(exception));
        }
    }

    public static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
