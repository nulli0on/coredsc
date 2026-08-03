package com.hubertstudios.coredsc.storage;

import com.hubertstudios.coredsc.CoreDSCPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the SQLite connection and confines every JDBC operation to one
 * dedicated worker thread. SQLite connections must not be shared across
 * Bukkit, JDA and scheduler threads.
 */
public final class SQLiteStorage {
    private static final int SCHEMA_VERSION = 13;
    private static final int DEFAULT_QUEUE_CAPACITY = 8_192;
    private static final int MINIMUM_QUEUE_CAPACITY = 256;
    private static final int MAXIMUM_QUEUE_CAPACITY = 65_536;
    private static final long QUEUE_POLL_MILLIS = 100L;
    private static final long SATURATION_WARNING_INTERVAL_MILLIS = 60_000L;

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

    /**
     * Signals deliberate load shedding. CoreDSC never runs rejected JDBC work
     * on a Folia region thread because doing so could stall or crash a region.
     */
    public static final class DatabaseQueueFullException extends RejectedExecutionException {
        private static final long serialVersionUID = 1L;

        private DatabaseQueueFullException(String message) {
            super(message);
        }
    }

    private interface QueueEntry extends Runnable {
        void reject(Throwable error);
    }

    private final CoreDSCPlugin plugin;
    private final int queueCapacity;
    private final BlockingQueue<QueueEntry> writeQueue;
    private final Thread worker;
    private final AtomicInteger queueHighWaterMark = new AtomicInteger();
    private final AtomicLong rejectedOperations = new AtomicLong();
    private final AtomicLong lastSaturationWarning = new AtomicLong();
    private volatile State state = State.NEW;
    private volatile String failureReason = "";
    private Connection connection;
    private CompletableFuture<Void> closeFuture;

    public SQLiteStorage(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.queueCapacity = configuredQueueCapacity(plugin);
        this.writeQueue = new ArrayBlockingQueue<>(queueCapacity, true);
        this.worker = new Thread(this::workerLoop, "CoreDSC-SQLite");
        // The worker is non-daemon so accepted writes are not silently lost.
        // CoreDSC's disable lifecycle performs a bounded wait for this worker to
        // drain and checkpoint before the plugin releases its class loader.
        this.worker.setDaemon(false);
        this.worker.start();
    }

    public State getState() {
        return state;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public int getQueuedOperationCount() {
        return writeQueue.size();
    }

    public int getQueueHighWaterMark() {
        return queueHighWaterMark.get();
    }

    public long getRejectedOperationCount() {
        return rejectedOperations.get();
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
        QueueEntry entry = new QueueEntry() {
            @Override
            public void run() {
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
                    migrate(connection, databaseFile);
                    synchronized (SQLiteStorage.this) {
                        if (state != State.CLOSING) {
                            state = State.READY;
                        }
                    }
                    future.complete(null);
                } catch (Throwable throwable) {
                    failureReason = rootMessage(throwable);
                    synchronized (SQLiteStorage.this) {
                        if (state != State.CLOSING) {
                            state = State.FAILED;
                        }
                    }
                    future.completeExceptionally(throwable);
                }
            }

            @Override
            public void reject(Throwable error) {
                future.completeExceptionally(error);
            }
        };
        if (!offerControlEntry(entry)) {
            RejectedExecutionException exception = new RejectedExecutionException(
                    "SQLite worker rejected its initialisation command");
            failureReason = rootMessage(exception);
            state = State.FAILED;
            future.completeExceptionally(exception);
        }
        return future;
    }

    public <T> CompletableFuture<T> execute(SqlOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> future = new CompletableFuture<>();
        QueueEntry entry = new QueueEntry() {
            @Override
            public void run() {
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
            }

            @Override
            public void reject(Throwable error) {
                future.completeExceptionally(error);
            }
        };

        synchronized (this) {
            if (state != State.READY) {
                future.completeExceptionally(
                        new IllegalStateException("Storage is not ready (state=" + state + ")"));
                return future;
            }
            if (!writeQueue.offer(entry)) {
                DatabaseQueueFullException exception = queueFullException();
                entry.reject(exception);
                warnSaturation();
                return future;
            }
            updateHighWaterMark();
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
        if (!worker.isAlive()) {
            state = State.CLOSED;
            future.complete(null);
        }
        return future;
    }

    private void workerLoop() {
        Throwable terminalFailure = null;
        try {
            while (true) {
                QueueEntry entry = writeQueue.poll(QUEUE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (entry != null) {
                    try {
                        entry.run();
                    } catch (Throwable error) {
                        // Queue entries normally complete their own futures. This
                        // guard prevents one defective entry from killing the only
                        // database worker and stranding every operation behind it.
                        entry.reject(error);
                        plugin.getLogger().warning("An SQLite queue entry failed unexpectedly: "
                                + rootMessage(error));
                    }
                }
                synchronized (this) {
                    if ((state == State.CLOSING || state == State.FAILED)
                            && writeQueue.isEmpty()) {
                        break;
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminalFailure = new IllegalStateException(
                    "SQLite worker was interrupted before its queue drained", interrupted);
        } catch (Throwable error) {
            terminalFailure = error;
        } finally {
            if (terminalFailure != null) {
                failQueuedEntries(terminalFailure);
            }
            checkpointAndCloseConnection();

            CompletableFuture<Void> shutdownFuture;
            synchronized (this) {
                if (terminalFailure != null) {
                    failureReason = rootMessage(terminalFailure);
                    state = State.FAILED;
                } else if (state == State.CLOSING) {
                    state = State.CLOSED;
                }
                shutdownFuture = closeFuture;
            }
            if (shutdownFuture != null) {
                if (terminalFailure == null) {
                    shutdownFuture.complete(null);
                } else {
                    shutdownFuture.completeExceptionally(terminalFailure);
                }
            }
        }
    }

    private boolean offerControlEntry(QueueEntry entry) {
        if (!worker.isAlive()) {
            return false;
        }
        boolean accepted = writeQueue.offer(entry);
        if (accepted) {
            updateHighWaterMark();
        }
        return accepted;
    }

    private void failQueuedEntries(Throwable error) {
        QueueEntry entry;
        while ((entry = writeQueue.poll()) != null) {
            try {
                entry.reject(error);
            } catch (RuntimeException rejectionError) {
                error.addSuppressed(rejectionError);
            }
        }
    }

    private void updateHighWaterMark() {
        queueHighWaterMark.accumulateAndGet(writeQueue.size(), Math::max);
    }

    private DatabaseQueueFullException queueFullException() {
        long rejected = rejectedOperations.incrementAndGet();
        return new DatabaseQueueFullException(
                "CoreDSC's SQLite queue is full (" + writeQueue.size() + '/' + queueCapacity
                        + ", rejected operations=" + rejected + "). The operation was not run on the "
                        + "calling Folia region thread. Check disk latency and enabled high-write modules, "
                        + "then increase storage.sqlite.queue-capacity only if memory headroom permits.");
    }

    private void warnSaturation() {
        long now = System.currentTimeMillis();
        long previous = lastSaturationWarning.get();
        if (now - previous < SATURATION_WARNING_INTERVAL_MILLIS
                || !lastSaturationWarning.compareAndSet(previous, now)) {
            return;
        }
        plugin.getLogger().severe("SQLite load shedding activated: the database queue reached "
                + queueCapacity + " operations. CoreDSC rejected new database work instead of blocking "
                + "a Folia region. Check storage latency and /coredsc status; rejected="
                + rejectedOperations.get() + ", peak=" + queueHighWaterMark.get() + '.');
    }

    private static int configuredQueueCapacity(CoreDSCPlugin plugin) {
        int configured = plugin.getAppConfig().getInt(
                "storage.sqlite.queue-capacity", DEFAULT_QUEUE_CAPACITY);
        if (configured >= MINIMUM_QUEUE_CAPACITY && configured <= MAXIMUM_QUEUE_CAPACITY) {
            return configured;
        }
        plugin.getLogger().warning("storage.sqlite.queue-capacity must be between "
                + MINIMUM_QUEUE_CAPACITY + " and " + MAXIMUM_QUEUE_CAPACITY
                + "; using " + DEFAULT_QUEUE_CAPACITY + '.');
        return DEFAULT_QUEUE_CAPACITY;
    }

    private static void configureConnection(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
        }
        String journalMode;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode = WAL")) {
            journalMode = result.next() ? result.getString(1) : "";
        }
        if (!"wal".equalsIgnoreCase(journalMode)) {
            throw new SQLException("SQLite refused WAL mode (reported '" + journalMode
                    + "'). Keep plugins/CoreDSC/data.db on a local writable disk; "
                    + "network filesystems and read-only mounts are unsupported.");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA wal_autocheckpoint = 1000");
            statement.execute("PRAGMA journal_size_limit = 67108864");
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA synchronous")) {
            int synchronous = result.next() ? result.getInt(1) : -1;
            if (synchronous != 1) {
                throw new SQLException("SQLite synchronous mode verification failed (expected NORMAL/1, got "
                        + synchronous + ")");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            int foreignKeys = result.next() ? result.getInt(1) : 0;
            if (foreignKeys != 1) {
                throw new SQLException("SQLite foreign-key enforcement could not be enabled");
            }
        }
    }

    private void migrate(Connection connection, File databaseFile) throws SQLException {
        int sourceVersion = readUserVersion(connection);
        if (sourceVersion > SCHEMA_VERSION) {
            throw new SQLException("Database schema " + sourceVersion
                    + " is newer than supported schema " + SCHEMA_VERSION + "; downgrade is blocked");
        }
        if (sourceVersion == SCHEMA_VERSION) {
            return;
        }
        if (hasUserTables(connection)) {
            backupDatabaseBeforeMigration(connection, databaseFile, sourceVersion);
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        SQLException sqlFailure = null;
        RuntimeException runtimeFailure = null;
        Error fatalFailure = null;
        connection.setAutoCommit(false);
        try {
            applyMigrations(connection);
            connection.commit();
        } catch (SQLException exception) {
            sqlFailure = exception;
            rollbackMigration(connection, exception);
            throw exception;
        } catch (RuntimeException exception) {
            runtimeFailure = exception;
            rollbackMigration(connection, exception);
            throw exception;
        } catch (Error error) {
            fatalFailure = error;
            rollbackMigration(connection, error);
            throw error;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreFailure) {
                if (sqlFailure != null) {
                    sqlFailure.addSuppressed(restoreFailure);
                } else if (runtimeFailure != null) {
                    runtimeFailure.addSuppressed(restoreFailure);
                } else if (fatalFailure != null) {
                    fatalFailure.addSuppressed(restoreFailure);
                } else {
                    throw restoreFailure;
                }
            }
        }
    }

    private static void applyMigrations(Connection connection) throws SQLException {
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

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS competitive_ratings (" +
                            "minecraft_uuid TEXT PRIMARY KEY, " +
                            "minecraft_name TEXT NOT NULL DEFAULT '', " +
                            "rating INTEGER NOT NULL, " +
                            "wins INTEGER NOT NULL DEFAULT 0, " +
                            "losses INTEGER NOT NULL DEFAULT 0, " +
                            "kills INTEGER NOT NULL DEFAULT 0, " +
                            "deaths INTEGER NOT NULL DEFAULT 0, " +
                            "matches INTEGER NOT NULL DEFAULT 0, " +
                            "updated_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS competitive_leaderboard_messages (" +
                            "channel_id TEXT PRIMARY KEY, " +
                            "message_id TEXT NOT NULL, " +
                            "updated_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cloud_operation_results (" +
                            "idempotency_key TEXT PRIMARY KEY, " +
                            "operation TEXT NOT NULL, " +
                            "request_fingerprint TEXT NOT NULL DEFAULT '', " +
                            "result_json TEXT NOT NULL, " +
                            "created_at INTEGER NOT NULL" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS channel_operation_snapshots (" +
                            "channel_id TEXT NOT NULL, " +
                            "permission_name TEXT NOT NULL, " +
                            "overwrite_existed INTEGER NOT NULL, " +
                            "allowed_raw INTEGER NOT NULL, " +
                            "denied_raw INTEGER NOT NULL, " +
                            "slowmode_seconds INTEGER NOT NULL, " +
                            "operation_id TEXT NOT NULL, " +
                            "created_at INTEGER NOT NULL, " +
                            "PRIMARY KEY(channel_id, permission_name)" +
                            ")");

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cloud_runtime_state (" +
                            "state_key TEXT PRIMARY KEY, " +
                            "state_json TEXT NOT NULL, " +
                            "updated_at INTEGER NOT NULL" +
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
        addColumnIfMissing(connection, "cloud_operation_results", "request_fingerprint",
                "TEXT NOT NULL DEFAULT ''");

        // Never discard ambiguous account/link data during an automatic upgrade.
        // A full snapshot has already been created; fail closed and require an
        // administrator to resolve duplicates deliberately.
        requireUniqueLegacyValues(connection, "linked_accounts", "discord_user_id");
        requireUniqueLegacyValues(connection, "pending_link_codes", "minecraft_uuid");

        try (Statement statement = connection.createStatement()) {
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
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_competitive_rating " +
                            "ON competitive_ratings(rating DESC, wins DESC, updated_at ASC)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_cloud_operation_time " +
                            "ON cloud_operation_results(created_at DESC)");
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    private static int readUserVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static boolean hasUserTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' LIMIT 1")) {
            return resultSet.next();
        }
    }

    /** Creates a consistent SQLite snapshot before any schema or data mutation. */
    private void backupDatabaseBeforeMigration(
            Connection connection,
            File databaseFile,
            int sourceVersion
    ) throws SQLException {
        File backupDirectory = new File(plugin.getDataFolder(), "backups");
        try {
            Files.createDirectories(backupDirectory.toPath());
        } catch (IOException exception) {
            throw new SQLException("Could not create database backup directory " + backupDirectory, exception);
        }

        String sourceLabel = sourceVersion <= 0 ? "legacy" : "v" + sourceVersion;
        long timestamp = System.currentTimeMillis();
        File backup = new File(backupDirectory,
                "data-" + sourceLabel + "-pre-v" + SCHEMA_VERSION + "-" + timestamp + ".db");
        int suffix = 0;
        while (backup.exists()) {
            suffix++;
            backup = new File(backupDirectory,
                    "data-" + sourceLabel + "-pre-v" + SCHEMA_VERSION + "-" + timestamp
                            + "-" + suffix + ".db");
        }

        String escapedPath = backup.getAbsolutePath().replace("'", "''");
        try (Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + escapedPath + "'");
        } catch (SQLException exception) {
            try {
                Files.deleteIfExists(backup.toPath());
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new SQLException("Could not create a pre-migration SQLite backup for "
                    + databaseFile.getName(), exception);
        }
        plugin.getLogger().info("Created pre-migration SQLite backup: " + backup.getAbsolutePath());
    }

    private static void rollbackMigration(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void requireUniqueLegacyValues(
            Connection connection,
            String table,
            String column
    ) throws SQLException {
        String sql = "SELECT COUNT(*) FROM (SELECT " + column + " FROM " + table
                + " GROUP BY " + column + " HAVING COUNT(*) > 1)";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            int duplicateGroups = resultSet.next() ? resultSet.getInt(1) : 0;
            if (duplicateGroups > 0) {
                throw new SQLException("Automatic migration stopped because " + table + "." + column
                        + " contains " + duplicateGroups + " duplicate group(s). Resolve them in a copy of data.db "
                        + "and retry; CoreDSC created a pre-migration backup and did not choose a record to delete.");
            }
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

    private void checkpointAndCloseConnection() {
        Connection current = connection;
        connection = null;
        if (current == null) {
            return;
        }
        try {
            if (!current.isClosed()) {
                try (Statement statement = current.createStatement();
                     ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                    int busy = result.next() ? result.getInt(1) : 0;
                    if (busy != 0) {
                        plugin.getLogger().warning("SQLite WAL checkpoint reported " + busy
                                + " busy writer(s). The WAL will be recovered automatically at next startup.");
                    }
                } catch (SQLException checkpointFailure) {
                    plugin.getLogger().warning("Could not checkpoint SQLite WAL during shutdown: "
                            + rootMessage(checkpointFailure)
                            + ". SQLite will recover the WAL automatically at next startup.");
                }
                current.close();
            }
        } catch (SQLException closeFailure) {
            plugin.getLogger().warning("Failed to close SQLite cleanly: " + rootMessage(closeFailure));
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
