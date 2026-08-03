package com.hubertstudios.coredsc.cloud;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.scripting.MiniJson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Outbound-only authenticated WebSocket agent for the hosted CoreDSC editor.
 * It owns no Bukkit objects and forwards only typed, locally revalidated work.
 */
public final class CloudEditorAgent implements WebSocket.Listener, AutoCloseable {
    public enum State { STOPPED, CONNECTING, CONNECTED, BACKING_OFF, FAILED }

    private record PendingRequest(CompletableFuture<Map<String, Object>> future, ScheduledFuture<?> timeout) { }

    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private final CoreDSCPlugin plugin;
    private final CloudIdentity identity;
    private final CloudOperationRouter router;
    private final Supplier<Map<String, Object>> heartbeatSupplier;
    private final URI endpoint;
    private final String serverName;
    private final String minecraftVersion;
    private final String schedulerRuntime;
    private final int maximumQueuedFrames;
    private final long maximumBackoffSeconds;
    private final ScheduledExecutorService executor;
    private final HttpClient client;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    private final AtomicInteger queuedFrames = new AtomicInteger();
    private final Object sendLock = new Object();
    private final CloudTextFrameAssembler receivedText = new CloudTextFrameAssembler(
            CloudProtocolLimits.MAXIMUM_MESSAGE_BYTES);
    private CompletableFuture<WebSocket> sendChain = CompletableFuture.completedFuture(null);
    private volatile State state = State.STOPPED;
    private volatile String detail = "not started";

    public CloudEditorAgent(
            CoreDSCPlugin plugin,
            CloudIdentity identity,
            CloudOperationRouter router,
            Supplier<Map<String, Object>> heartbeatSupplier,
            URI endpoint,
            String serverName,
            String minecraftVersion,
            String schedulerRuntime,
            int maximumQueuedFrames,
            long maximumBackoffSeconds
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.router = Objects.requireNonNull(router, "router");
        this.heartbeatSupplier = Objects.requireNonNull(heartbeatSupplier, "heartbeatSupplier");
        this.endpoint = requireWebSocketUri(endpoint);
        this.serverName = cleanHeader(serverName, 80);
        this.minecraftVersion = cleanHeader(minecraftVersion, 80);
        this.schedulerRuntime = cleanHeader(schedulerRuntime, 20);
        this.maximumQueuedFrames = Math.max(32, Math.min(1_024, maximumQueuedFrames));
        this.maximumBackoffSeconds = Math.max(10L, Math.min(900L, maximumBackoffSeconds));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "CoreDSC-Cloud-Agent");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newScheduledThreadPool(2, factory);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .build();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        state = State.CONNECTING;
        detail = "connecting to " + endpoint.getHost();
        executor.execute(this::attemptConnect);
        executor.scheduleAtFixedRate(this::heartbeatTick, 10L, 30L, TimeUnit.SECONDS);
    }

    public State state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public UUID instanceId() {
        return identity.instanceId();
    }

    public boolean connected() {
        return state == State.CONNECTED && socket.get() != null;
    }

    public CompletableFuture<Map<String, Object>> createEditorLink(
            UUID minecraftUuid,
            String minecraftName,
            String boundDiscordUserId
    ) {
        return createEditorLink(minecraftUuid, minecraftName, boundDiscordUserId, false);
    }

    public CompletableFuture<Map<String, Object>> createEditorLink(
            UUID minecraftUuid,
            String minecraftName,
            String boundDiscordUserId,
            boolean ownershipRecovery
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (minecraftUuid != null) payload.put("minecraftUuid", minecraftUuid.toString());
        if (minecraftName != null && !minecraftName.isBlank()) payload.put("minecraftName", minecraftName);
        if (boundDiscordUserId != null && !boundDiscordUserId.isBlank()) {
            payload.put("boundDiscordUserId", boundDiscordUserId);
        }
        if (ownershipRecovery) payload.put("ownershipRecovery", true);
        return sendEvent("pairing.create", payload, 15_000L);
    }

    public CompletableFuture<Map<String, Object>> confirmPairing(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Confirmation code must use the format ABCD-EFGH"));
        }
        return sendEvent("pairing.confirm", Map.of("code", normalized), 15_000L);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        // A connection attempt may complete after plugin disable. Never allow a
        // late HttpClient callback to resurrect the cloud agent or leak a live
        // outbound socket after close() has begun.
        if (!running.get()) {
            webSocket.sendClose(1000, "CoreDSC is stopping")
                    .exceptionally(error -> {
                        webSocket.abort();
                        return null;
                    });
            return;
        }
        WebSocket previous = socket.getAndSet(webSocket);
        receivedText.reset();
        if (previous != null && previous != webSocket) previous.abort();
        reconnectScheduled.set(false);
        reconnectAttempt.set(0);
        state = State.CONNECTED;
        detail = "authenticated outbound connection established";
        plugin.getLogger().info("[Cloud] Connected instance " + identity.instanceId()
                + " to the hosted CoreDSC control plane.");
        webSocket.request(1);
        sendHeartbeat();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (socket.get() != webSocket) return CompletableFuture.completedFuture(null);
        String complete;
        try {
            complete = receivedText.append(data, last);
        } catch (CloudTextFrameAssembler.MessageTooLargeException error) {
            webSocket.sendClose(1009, "Message too large");
            return CompletableFuture.completedFuture(null);
        }
        if (complete != null) handleMessage(complete);
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
        if (socket.get() == webSocket) webSocket.sendClose(1003, "Binary frames are unsupported");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (!socket.compareAndSet(webSocket, null)) {
            return CompletableFuture.completedFuture(null);
        }
        receivedText.reset();
        failPending(new IllegalStateException("Cloud connection closed (" + statusCode + "): " + reason));
        if (running.get()) scheduleReconnect("connection closed with code " + statusCode);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (!socket.compareAndSet(webSocket, null)) return;
        receivedText.reset();
        failPending(error);
        if (running.get()) scheduleReconnect("connection error: " + rootMessage(error));
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        state = State.STOPPED;
        detail = "plugin disabled";
        receivedText.reset();
        WebSocket current = socket.getAndSet(null);
        if (current != null) {
            Map<String, Object> message = Map.of(
                    "v", 1,
                    "type", "event",
                    "event", "shutdown.clean",
                    "requestId", UUID.randomUUID().toString(),
                    "payload", Map.of("at", System.currentTimeMillis()));
            try {
                current.sendText(MiniJson.write(message), true)
                        .orTimeout(500L, TimeUnit.MILLISECONDS)
                        .whenComplete((ignored, error) -> current.sendClose(1000, "CoreDSC disabled"));
                executor.schedule(() -> {
                    current.abort();
                    executor.shutdownNow();
                }, 750L, TimeUnit.MILLISECONDS);
            } catch (RuntimeException error) {
                current.abort();
                executor.shutdownNow();
            }
        } else {
            executor.shutdownNow();
        }
        failPending(new IllegalStateException("Cloud agent stopped"));
    }

    private void attemptConnect() {
        if (!running.get() || socket.get() != null) return;
        state = State.CONNECTING;
        reconnectScheduled.set(false);
        try {
            long timestamp = System.currentTimeMillis();
            byte[] nonceBytes = new byte[32];
            secureRandom.nextBytes(nonceBytes);
            String nonce = BASE64.encodeToString(nonceBytes);
            java.util.Arrays.fill(nonceBytes, (byte) 0);
            String canonical = "coredsc-agent-v1\n" + identity.instanceId() + "\n" + timestamp + "\n" + nonce;
            String signature = identity.sign(canonical);
            client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .header("X-CoreDSC-Instance", identity.instanceId().toString())
                    .header("X-CoreDSC-Timestamp", Long.toString(timestamp))
                    .header("X-CoreDSC-Nonce", nonce)
                    .header("X-CoreDSC-Public-Key", identity.publicKeyBase64())
                    .header("X-CoreDSC-Signature", signature)
                    .header("X-CoreDSC-Name", serverName)
                    .header("X-CoreDSC-Version", plugin.getDescription().getVersion())
                    .header("X-CoreDSC-Minecraft", minecraftVersion)
                    .header("X-CoreDSC-Scheduler", schedulerRuntime)
                    .buildAsync(endpoint, this)
                    .whenComplete((connected, error) -> {
                        if (error != null && running.get()) {
                            scheduleReconnect("connect failed: " + rootMessage(error));
                        }
                    });
        } catch (Throwable error) {
            scheduleReconnect("authentication setup failed: " + rootMessage(error));
        }
    }

    private void scheduleReconnect(String reason) {
        if (!running.get() || !reconnectScheduled.compareAndSet(false, true)) return;
        int attempt = reconnectAttempt.incrementAndGet();
        long exponential = 1L << Math.min(8, Math.max(0, attempt - 1));
        long jitterMillis = secureRandom.nextLong(1_000L);
        long delayMillis = Math.min(maximumBackoffSeconds * 1_000L, exponential * 1_000L + jitterMillis);
        state = State.BACKING_OFF;
        detail = reason + "; retry in " + Math.max(1L, delayMillis / 1_000L) + "s";
        executor.schedule(this::attemptConnect, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void heartbeatTick() {
        if (!running.get()) return;
        if (connected()) sendHeartbeat();
        else if (!reconnectScheduled.get()) scheduleReconnect("heartbeat detected no connection");
    }

    private void sendHeartbeat() {
        Map<String, Object> payload;
        try {
            payload = new LinkedHashMap<>(heartbeatSupplier.get());
        } catch (Throwable error) {
            plugin.getLogger().warning("[Cloud] Could not prepare heartbeat: " + rootMessage(error));
            return;
        }
        payload.put("pluginVersion", plugin.getDescription().getVersion());
        payload.put("minecraftVersion", minecraftVersion);
        payload.put("scheduler", schedulerRuntime);
        payload.put("timestamp", System.currentTimeMillis());
        send(Map.of("v", 1, "type", "heartbeat", "payload", Map.copyOf(payload)))
                .exceptionally(error -> null);
    }

    private CompletableFuture<Map<String, Object>> sendEvent(
            String event,
            Map<String, Object> payload,
            long timeoutMillis
    ) {
        if (!connected()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Cloud agent is not connected: " + detail));
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        ScheduledFuture<?> timeout = executor.schedule(() -> {
            PendingRequest removed = pendingRequests.remove(requestId);
            if (removed != null) removed.future().completeExceptionally(
                    new IllegalStateException("Cloud request timed out"));
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        pendingRequests.put(requestId, new PendingRequest(future, timeout));
        send(Map.of("v", 1, "type", "event", "event", event,
                "requestId", requestId, "payload", payload)).whenComplete((ignored, error) -> {
            if (error == null) return;
            PendingRequest removed = pendingRequests.remove(requestId);
            if (removed != null) {
                removed.timeout().cancel(false);
                removed.future().completeExceptionally(error);
            }
        });
        return future;
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(String text) {
        Map<String, Object> message;
        try {
            message = MiniJson.parseObject(text);
        } catch (RuntimeException error) {
            closeProtocol(1007, "Invalid JSON");
            return;
        }
        if (number(message.get("v")) != 1L) {
            closeProtocol(1008, "Unsupported protocol");
            return;
        }
        String type = string(message.get("type"));
        if (type.equals("response")) {
            String requestId = string(message.get("requestId"));
            PendingRequest pending = pendingRequests.remove(requestId);
            if (pending == null) return;
            pending.timeout().cancel(false);
            if (Boolean.TRUE.equals(message.get("ok"))) {
                Object raw = message.get("payload");
                pending.future().complete(raw instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : Map.of("value", raw));
            } else {
                Object rawError = message.get("error");
                String errorMessage = rawError instanceof Map<?, ?> map
                        ? string(map.get("message")) : "Cloud request was rejected";
                pending.future().completeExceptionally(new IllegalStateException(
                        errorMessage.isBlank() ? "Cloud request was rejected" : errorMessage));
            }
            return;
        }
        if (!type.equals("request")) return;
        handleOperationRequest(message);
    }

    @SuppressWarnings("unchecked")
    private void handleOperationRequest(Map<String, Object> message) {
        String requestId = string(message.get("requestId"));
        String operation = string(message.get("operation"));
        String idempotencyKey = string(message.get("idempotencyKey"));
        long deadline = number(message.get("deadline"));
        Map<String, Object> payload = message.get("payload") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        Map<String, Object> actor = message.get("actor") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();
        if (!requestId.matches("[0-9a-f-]{36}") || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,100}")) {
            sendError(requestId, "INVALID_REQUEST", "Malformed request identity", false);
            return;
        }
        if (deadline <= System.currentTimeMillis()) {
            sendError(requestId, "REQUEST_EXPIRED", "Operation deadline expired before local execution", true);
            return;
        }
        router.route(operation, payload, actor, idempotencyKey).whenComplete((result, error) -> {
            if (error != null) {
                sendError(requestId, "LOCAL_REJECTED", rootMessage(error), retryable(error));
                return;
            }
            send(Map.of("v", 1, "type", "response", "requestId", requestId,
                    "ok", true, "payload", result)).exceptionally(sendError -> null);
        });
    }

    private void sendError(String requestId, String code, String message, boolean retryable) {
        String safeRequestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId;
        send(Map.of("v", 1, "type", "response", "requestId", safeRequestId, "ok", false,
                "error", Map.of("code", code, "message", truncate(message, 500),
                        "retryable", retryable))).exceptionally(error -> null);
    }

    private CompletableFuture<Void> send(Map<String, Object> message) {
        String json = MiniJson.write(message);
        if (CloudProtocolLimits.exceedsMessageLimit(json)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Cloud message exceeds " + CloudProtocolLimits.MAXIMUM_MESSAGE_BYTES + " UTF-8 bytes"));
        }
        if (queuedFrames.incrementAndGet() > maximumQueuedFrames) {
            queuedFrames.decrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Cloud outbound queue reached " + maximumQueuedFrames + " frames"));
        }
        synchronized (sendLock) {
            CompletableFuture<WebSocket> next = sendChain.handle((ignored, previousError) -> {
                WebSocket current = socket.get();
                if (current == null) throw new IllegalStateException("Cloud WebSocket is disconnected");
                return current;
            }).thenCompose(current -> current.sendText(json, true));
            sendChain = next.whenComplete((ignored, error) -> queuedFrames.decrementAndGet());
            return next.thenApply(ignored -> null);
        }
    }

    private void closeProtocol(int code, String reason) {
        WebSocket current = socket.get();
        if (current != null) current.sendClose(code, reason);
    }

    private void failPending(Throwable error) {
        pendingRequests.forEach((id, pending) -> {
            if (pendingRequests.remove(id, pending)) {
                pending.timeout().cancel(false);
                pending.future().completeExceptionally(error);
            }
        });
    }

    private static URI requireWebSocketUri(URI uri) {
        Objects.requireNonNull(uri, "endpoint");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if ((!scheme.equals("wss") && !scheme.equals("ws")) || uri.getHost() == null) {
            throw new IllegalArgumentException("Cloud endpoint must be an absolute wss:// or ws:// URI");
        }
        if (scheme.equals("ws") && !isLoopback(uri.getHost())) {
            throw new IllegalArgumentException("Unencrypted ws:// is allowed only for loopback development");
        }
        return uri;
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    private static String cleanHeader(String value, int maximum) {
        String text = value == null ? "" : value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    private static String string(Object value) {
        return value instanceof String text ? text.trim() : "";
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static boolean retryable(Throwable error) {
        Throwable root = root(error);
        return root instanceof java.io.IOException
                || root instanceof java.util.concurrent.TimeoutException
                || root instanceof com.hubertstudios.coredsc.storage.SQLiteStorage.DatabaseQueueFullException;
    }

    private static String truncate(String value, int maximum) {
        String text = value == null || value.isBlank() ? "Unknown failure" : value;
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = root(error);
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private static Throwable root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }
}
