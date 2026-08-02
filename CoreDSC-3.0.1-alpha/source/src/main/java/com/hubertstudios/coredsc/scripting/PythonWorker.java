package com.hubertstudios.coredsc.scripting;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


public final class PythonWorker {
    private static final Snapshot EMPTY_SNAPSHOT = new Snapshot(List.of(), Set.of(), List.of());

    public enum State { STOPPED, STARTING, READY, FAILED }

    public record OptionSpec(String name, String description, String type, boolean required, int maxLength) { }
    public record CommandSpec(
            String name,
            String description,
            Set<String> platforms,
            String permission,
            boolean ephemeral,
            boolean guildOnly,
            boolean linkedOnly,
            int cooldownSeconds,
            List<Long> allowedRoleIds,
            List<OptionSpec> options,
            String handler,
            String script
    ) { }
    public record Snapshot(List<CommandSpec> commands, Set<String> events, List<String> scripts) { }
    public record ExecutionResult(List<Map<String, Object>> actions) { }

    private final CoreDSCPlugin plugin;
    private final File botDirectory;
    private final Consumer<Snapshot> readyCallback;
    private final Map<String, CompletableFuture<ExecutionResult>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicInteger generation = new AtomicInteger();
    private final ArrayDeque<Long> restartTimes = new ArrayDeque<>();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile State state = State.STOPPED;
    private volatile String detail = "stopped";
    private volatile Snapshot snapshot = EMPTY_SNAPSHOT;
    private volatile CompletableFuture<Snapshot> readyFuture = new CompletableFuture<>();
    private volatile int requestTimeoutMillis;
    private volatile int startupTimeoutSeconds;
    private volatile int shutdownTimeoutSeconds;
    private volatile int maximumQueueSize;
    private volatile int maximumRestartsPerHour;
    private volatile boolean inheritSensitiveEnvironment;
    private volatile String configuredExecutable;

    public PythonWorker(CoreDSCPlugin plugin, File botDirectory, Consumer<Snapshot> readyCallback) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.botDirectory = Objects.requireNonNull(botDirectory, "botDirectory");
        this.readyCallback = Objects.requireNonNull(readyCallback, "readyCallback");
        loadSettings();
    }

    public State state() { return state; }
    public String detail() { return detail; }
    public Snapshot snapshot() { return snapshot; }
    public boolean isReady() { return state == State.READY && process != null && process.isAlive(); }

    public synchronized CompletableFuture<Snapshot> startAsync() {
        if (isReady()) return CompletableFuture.completedFuture(snapshot);
        if (state == State.STARTING && !readyFuture.isDone()) return readyFuture;
        stopping.set(false);
        int expectedGeneration = generation.incrementAndGet();
        state = State.STARTING;
        detail = "starting";
        readyFuture = new CompletableFuture<>();
        CompletableFuture<Snapshot> exposed = readyFuture;
        startDaemon("CoreDSC-Python-Startup-" + expectedGeneration, () -> {
            try {
                loadSettings();
                startProcess(expectedGeneration);
                exposed.get(startupTimeoutSeconds, TimeUnit.SECONDS);
            } catch (Throwable throwable) {
                if (generation.get() == expectedGeneration) {
                    fail("Python worker startup failed: " + rootMessage(throwable), throwable);
                    generation.incrementAndGet();
                    publishUnavailableSnapshot();
                    stopProcess(true);
                    exposed.completeExceptionally(throwable);
                }
            }
        });
        return exposed;
    }

    public CompletableFuture<Snapshot> restartAsync() {
        stopping.set(true);
        return stopAsync().handle((ignored, error) -> null).thenCompose(ignored -> {
            stopping.set(false);
            return startAsync();
        });
    }

    public CompletableFuture<Void> stopAsync() {
        stopping.set(true);
        generation.incrementAndGet();
        publishUnavailableSnapshot();
        Process current = process;
        if (current == null) {
            failPending(new java.util.concurrent.CancellationException("Python worker stopped"));
            state = State.STOPPED;
            detail = "stopped";
            return CompletableFuture.completedFuture(null);
        }
        try {
            sendRaw(Map.of("type", "shutdown"));
        } catch (RuntimeException ignored) {
            
        }
        current.destroy();
        return current.onExit().orTimeout(shutdownTimeoutSeconds, TimeUnit.SECONDS)
                .handle((ignored, error) -> {
                    if (current.isAlive()) current.destroyForcibly();
                    stopProcess(false);
                    state = State.STOPPED;
                    detail = "stopped";
                    return null;
                });
    }

    public CompletableFuture<ExecutionResult> execute(
            String kind,
            String handler,
            String eventName,
            Map<String, Object> context
    ) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Python worker is not ready"));
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();
        synchronized (pending) {
            if (pending.size() >= maximumQueueSize) {
                return CompletableFuture.failedFuture(new IllegalStateException("Python request queue is full"));
            }
            pending.put(requestId, future);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "execute");
        request.put("request_id", requestId);
        request.put("kind", kind);
        request.put("handler", handler == null ? "" : handler);
        request.put("event", eventName == null ? "" : eventName);
        request.put("context", context == null ? Map.of() : context);
        try {
            sendRaw(request);
        } catch (RuntimeException error) {
            pending.remove(requestId);
            future.completeExceptionally(error);
            return future;
        }
        return future.orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> {
                    pending.remove(requestId);
                    if (isTimeout(error)) {
                        terminateHungWorker(requestId);
                    }
                });
    }

    private void startProcess(int expectedGeneration) throws IOException {
        snapshot = EMPTY_SNAPSHOT;

        File runtimeScript = new File(botDirectory, "runtime/worker.py");
        File scriptsDirectory = new File(botDirectory, "scripts");
        if (!runtimeScript.isFile() || !runtimeScript.canRead()) {
            throw new IOException("Bundled Python worker is missing or unreadable: "
                    + runtimeScript.getAbsolutePath());
        }
        if (!scriptsDirectory.isDirectory()) {
            throw new IOException("Python scripts directory does not exist: "
                    + scriptsDirectory.getAbsolutePath());
        }

        IOException last = null;
        List<String> attempted = new ArrayList<>();
        for (List<String> prefix : executableCandidates()) {
            if (generation.get() != expectedGeneration || stopping.get()) {
                throw new java.util.concurrent.CancellationException("Python startup was cancelled");
            }
            attempted.add(String.join(" ", prefix));
            List<String> command = new ArrayList<>(prefix);
            command.add("-I");
            command.add("-B");
            command.add("-u");
            command.add(runtimeScript.getAbsolutePath());
            command.add("--scripts");
            command.add(scriptsDirectory.getAbsolutePath());
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(botDirectory);
                sanitizeEnvironment(builder.environment());
                Process started = builder.start();
                if (generation.get() != expectedGeneration || stopping.get()) {
                    started.destroyForcibly();
                    throw new java.util.concurrent.CancellationException("Python startup was cancelled");
                }
                process = started;
                writer = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
                startReaders(started, expectedGeneration);
                started.onExit().thenAccept(ignored -> handleExit(started, expectedGeneration));
                sendRaw(Map.of(
                        "type", "hello",
                        "protocol", 1,
                        "core_version", plugin.getDescription().getVersion(),
                        "server_id", plugin.getAppConfig().getString("network.server-id", "")
                ));
                return;
            } catch (IOException exception) {
                last = exception;
            }
        }
        String guidance = "No usable Python 3 executable was found. Tried: "
                + String.join(", ", attempted)
                + ". Install Python 3, set bot.python-executable to an absolute executable path, "
                + "or disable plugins/CoreDSC/bot/config.yml.";
        throw last == null ? new IOException(guidance) : new IOException(guidance
                + " Last operating-system error: " + rootMessage(last), last);
    }

    private void startReaders(Process started, int expectedGeneration) {
        startDaemon("CoreDSC-Python-Protocol-" + expectedGeneration, () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleProtocolLine(line, expectedGeneration);
                }
            } catch (IOException exception) {
                if (!stopping.get() && generation.get() == expectedGeneration) {
                    plugin.getLogger().warning("[Python] Protocol reader stopped: " + rootMessage(exception));
                }
            }
        });
        startDaemon("CoreDSC-Python-Stderr-" + expectedGeneration, () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    started.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                int emitted = 0;
                while ((line = reader.readLine()) != null) {
                    if (emitted++ < 200) plugin.getLogger().warning("[Python] " + line);
                }
            } catch (IOException ignored) {
                
            }
        });
    }

    private void handleProtocolLine(String line, int expectedGeneration) {
        if (generation.get() != expectedGeneration || line.isBlank()) return;
        Map<String, Object> message;
        try {
            message = MiniJson.parseObject(line);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Python] Invalid protocol JSON: " + rootMessage(exception));
            return;
        }
        String type = text(message.get("type"));
        switch (type) {
            case "ready" -> handleReady(message, expectedGeneration);
            case "result" -> handleResult(message);
            case "error" -> handleError(message);
            case "log" -> handleLog(message);
            default -> plugin.getLogger().warning("[Python] Unknown protocol message: " + type);
        }
    }

    private void handleReady(Map<String, Object> message, int expectedGeneration) {
        if (generation.get() != expectedGeneration) return;
        List<CommandSpec> commands = parseCommands(message.get("commands"));
        Set<String> events = stringSet(message.get("events"));
        List<String> scripts = stringList(message.get("scripts"));
        Snapshot ready = new Snapshot(List.copyOf(commands), Set.copyOf(events), List.copyOf(scripts));
        snapshot = ready;
        state = State.READY;
        detail = scripts.size() + " script(s), " + commands.size() + " command(s)";
        readyFuture.complete(ready);
        publishSnapshot(ready);
        plugin.getLogger().info("Python developer worker ready: " + detail + ".");
    }

    private void handleResult(Map<String, Object> message) {
        String requestId = text(message.get("request_id"));
        CompletableFuture<ExecutionResult> future = pending.remove(requestId);
        if (future == null) return;
        List<Map<String, Object>> actions = new ArrayList<>();
        Object raw = message.get("actions");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    actions.add(Collections.unmodifiableMap(copy));
                }
            }
        }
        future.complete(new ExecutionResult(List.copyOf(actions)));
    }

    private void handleError(Map<String, Object> message) {
        String requestId = text(message.get("request_id"));
        String error = text(message.get("message"));
        String trace = text(message.get("traceback"));
        CompletableFuture<ExecutionResult> future = pending.remove(requestId);
        IllegalStateException exception = new IllegalStateException(
                error.isBlank() ? "Python script failed" : error);
        if (future != null) future.completeExceptionally(exception);
        plugin.getLogger().warning("[Python] Script error: " + exception.getMessage()
                + (trace.isBlank() ? "" : "\n" + trace));
    }

    private void handleLog(Map<String, Object> message) {
        String level = text(message.get("level")).toUpperCase(Locale.ROOT);
        String script = text(message.get("script"));
        String text = text(message.get("message"));
        String prefix = script.isBlank() ? "[Python] " : "[Python:" + script + "] ";
        if (level.equals("ERROR") || level.equals("WARNING")) plugin.getLogger().warning(prefix + text);
        else plugin.getLogger().info(prefix + text);
    }

    private void handleExit(Process exited, int expectedGeneration) {
        if (process == exited) {
            process = null;
            writer = null;
        }
        if (generation.get() != expectedGeneration) return;
        publishUnavailableSnapshot();
        failPending(new IllegalStateException("Python worker exited with code " + exited.exitValue()));
        if (stopping.get() || !plugin.isEnabled()) {
            state = State.STOPPED;
            detail = "stopped";
            return;
        }
        state = State.FAILED;
        detail = "process exited with code " + exited.exitValue();
        if (!claimRestart()) {
            plugin.getLogger().severe("[Python] Automatic restart limit reached; use /coredsc bot restart.");
            return;
        }
        startDaemon("CoreDSC-Python-Restart", () -> {
            try {
                Thread.sleep(2000L);
                if (plugin.isEnabled() && !stopping.get()) startAsync();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void startDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized boolean claimRestart() {
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        while (!restartTimes.isEmpty() && restartTimes.peekFirst() < cutoff) restartTimes.removeFirst();
        if (restartTimes.size() >= maximumRestartsPerHour) return false;
        restartTimes.addLast(System.currentTimeMillis());
        return true;
    }

    private void terminateHungWorker(String requestId) {
        Process current = process;
        if (current == null || !current.isAlive()) return;
        plugin.getLogger().warning("[Python] Request " + requestId
                + " exceeded " + requestTimeoutMillis + " ms; restarting the worker.");
        current.destroyForcibly();
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }

    private void sendRaw(Map<String, Object> message) {
        BufferedWriter current = writer;
        Process currentProcess = process;
        if (current == null || currentProcess == null || !currentProcess.isAlive()) {
            throw new IllegalStateException("Python worker process is unavailable");
        }
        synchronized (writeLock) {
            try {
                current.write(MiniJson.write(message));
                current.newLine();
                current.flush();
            } catch (IOException exception) {
                throw new IllegalStateException("Could not write to Python worker", exception);
            }
        }
    }

    private void publishUnavailableSnapshot() {
        publishSnapshot(EMPTY_SNAPSHOT);
    }

    private void publishSnapshot(Snapshot value) {
        snapshot = value;
        try {
            readyCallback.accept(value);
        } catch (RuntimeException callbackError) {
            plugin.getLogger().warning("[Python] Snapshot callback failed: " + rootMessage(callbackError));
        }
    }

    private void stopProcess(boolean failed) {
        Process current = process;
        process = null;
        writer = null;
        if (current != null && current.isAlive()) current.destroyForcibly();
        failPending(new IllegalStateException("Python worker stopped"));
        if (!failed) {
            state = State.STOPPED;
            detail = "stopped";
        }
    }

    private void failPending(Throwable error) {
        pending.forEach((id, future) -> future.completeExceptionally(error));
        pending.clear();
        CompletableFuture<Snapshot> ready = readyFuture;
        if (!ready.isDone()) ready.completeExceptionally(error);
    }

    private void fail(String message, Throwable error) {
        state = State.FAILED;
        detail = message;
        plugin.getLogger().warning("[Python] " + message);
        if (plugin.getAppConfig().getBoolean("debug", false) && error != null) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, message, error);
        }
    }

    private void loadSettings() {
        FileConfiguration config = plugin.getAppConfig();
        configuredExecutable = config.getString("bot.python-executable", "auto");
        startupTimeoutSeconds = clamp(config.getInt("bot.startup-timeout-seconds", 10), 2, 60);
        requestTimeoutMillis = clamp(config.getInt("bot.request-timeout-milliseconds", 2000), 100, 60_000);
        shutdownTimeoutSeconds = clamp(config.getInt("bot.shutdown-timeout-seconds", 5), 1, 30);
        maximumQueueSize = clamp(config.getInt("bot.maximum-queue-size", 100), 1, 10_000);
        maximumRestartsPerHour = clamp(config.getInt("bot.maximum-restarts-per-hour", 3), 0, 100);
        inheritSensitiveEnvironment = config.getBoolean("bot.security.inherit-sensitive-environment", false);
    }

    private List<List<String>> executableCandidates() {
        String configured = configuredExecutable == null ? "auto" : configuredExecutable.trim();
        if (!configured.isBlank() && !configured.equalsIgnoreCase("auto")) {
            return List.of(splitExecutable(configured));
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<List<String>> candidates = new ArrayList<>();
        if (windows) candidates.add(List.of("py", "-3"));
        candidates.add(List.of("python3"));
        candidates.add(List.of("python"));
        return candidates;
    }

    private static List<String> splitExecutable(String configured) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < configured.length(); i++) {
            char c = configured.charAt(i);
            if ((c == '\'' || c == '"')) {
                if (quoted && c == quote) {
                    quoted = false;
                } else if (!quoted) {
                    quoted = true;
                    quote = c;
                } else {
                    current.append(c);
                }
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else current.append(c);
        }
        if (!current.isEmpty()) parts.add(current.toString());
        if (parts.isEmpty()) throw new IllegalArgumentException("bot.python-executable is blank");
        return List.copyOf(parts);
    }

    private void sanitizeEnvironment(Map<String, String> environment) {
        if (inheritSensitiveEnvironment) return;
        List<String> remove = new ArrayList<>();
        for (String key : environment.keySet()) {
            String upper = key.toUpperCase(Locale.ROOT);
            if (upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("PASSWORD")
                    || upper.contains("LICENSE") || upper.equals("COREDSC_BOT_TOKEN")) {
                remove.add(key);
            }
        }
        remove.forEach(environment::remove);
        environment.put("PYTHONIOENCODING", "utf-8");
        environment.put("PYTHONUNBUFFERED", "1");
    }

    private static List<CommandSpec> parseCommands(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<CommandSpec> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String name = text(map.get("name")).toLowerCase(Locale.ROOT);
            String description = text(map.get("description"));
            String handler = text(map.get("handler"));
            if (!name.matches("[a-z0-9_-]{1,32}") || description.isBlank()
                    || description.length() > 100 || handler.isBlank()) {
                continue;
            }
            Set<String> platforms = stringSet(map.get("platforms")).stream()
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .filter(value -> value.equals("MINECRAFT") || value.equals("DISCORD"))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (platforms.isEmpty()) platforms = Set.of("DISCORD");
            List<Long> roles = longList(map.get("allowed_role_ids"));
            List<OptionSpec> options = parseOptions(map.get("options"));
            result.add(new CommandSpec(
                    name,
                    description,
                    Set.copyOf(platforms),
                    text(map.get("permission")),
                    bool(map.get("ephemeral"), true),
                    bool(map.get("guild_only"), false),
                    bool(map.get("linked_only"), false),
                    clamp(integer(map.get("cooldown_seconds"), 0), 0, 86_400),
                    roles,
                    options,
                    handler,
                    text(map.get("script"))
            ));
        }
        return List.copyOf(result);
    }

    private static List<OptionSpec> parseOptions(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<OptionSpec> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String name = text(map.get("name")).toLowerCase(Locale.ROOT);
            String description = text(map.get("description"));
            String type = text(map.get("type")).toUpperCase(Locale.ROOT);
            if (!name.matches("[a-z0-9_-]{1,32}") || !names.add(name)
                    || description.isBlank() || description.length() > 100) continue;
            if (!Set.of("STRING", "INTEGER", "BOOLEAN", "USER").contains(type)) type = "STRING";
            if (result.size() >= 25) break;
            result.add(new OptionSpec(name, description, type, bool(map.get("required"), false),
                    clamp(integer(map.get("max_length"), 200), 1, 6000)));
        }
        result.sort((left, right) -> Boolean.compare(right.required(), left.required()));
        return List.copyOf(result);
    }

    private static Set<String> stringSet(Object raw) {
        return new LinkedHashSet<>(stringList(raw));
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : list) {
            String text = text(value);
            if (!text.isBlank()) result.add(text);
        }
        return List.copyOf(result);
    }

    private static List<Long> longList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Long> result = new ArrayList<>();
        for (Object value : list) {
            try {
                long parsed = Long.parseLong(text(value));
                if (parsed > 0L) result.add(parsed);
            } catch (NumberFormatException ignored) { }
        }
        return List.copyOf(result);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(text(value)); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
