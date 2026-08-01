package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.config.ConfigManager;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.scripting.MiniJson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

 
                                                 
  
                                                                                
                                                                                 
                                                                           
                                                         
   
public final class WebEditorModule implements CoreModule {
    private static final Set<String> LOOPBACK_NAMES = Set.of("127.0.0.1", "::1", "localhost");
    private static final String AUTH_SCHEME = "Bearer ";
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    public record SessionInfo(String url, int durationMinutes, Instant expiresAt) { }

    private record Asset(String contentType, byte[] content) { }

    private record Session(
            String id,
            byte[] tokenHash,
            Instant startedAt,
            Instant expiresAt,
            int port,
            Set<String> allowedOrigins
    ) { }

    private static final class HttpProblem extends Exception {
        private final int status;

        private HttpProblem(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final CoreDSCPlugin plugin;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Object lifecycleLock = new Object();
    private final ArrayDeque<Long> failedAuthenticationWindow = new ArrayDeque<>();

    private volatile boolean armed;
    private volatile Session session;
    private HttpServer server;
    private ExecutorService requestExecutor;
    private ScheduledExecutorService expiryExecutor;
    private InetAddress bindAddress;
    private int configuredPort;
    private int defaultMinutes;
    private int maximumMinutes;
    private int maximumFailedAuthAttempts;
    private int maximumFileBytes;
    private Map<String, Asset> assets = Map.of();
    private volatile String lastStopReason = "not started";

    public WebEditorModule(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String id() {
        return "web-editor";
    }

    @Override
    public void enable() {
        FileConfiguration config = plugin.getAppConfig();
        String configuredAddress = text(config.getString("web-editor.bind-address", "127.0.0.1"));
        if (!LOOPBACK_NAMES.contains(configuredAddress)) {
            throw new IllegalArgumentException(
                    "web-editor.bind-address must be 127.0.0.1, ::1 or localhost");
        }
        try {
            bindAddress = InetAddress.getByName(configuredAddress);
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not resolve web-editor.bind-address", error);
        }
        if (!bindAddress.isLoopbackAddress()) {
            throw new IllegalArgumentException("WebEditor alpha refuses every non-loopback bind address");
        }

        configuredPort = range(config.getInt("web-editor.port", 8765), 1024, 65_535,
                "web-editor.port");
        maximumMinutes = range(config.getInt("web-editor.session.maximum-minutes", 30), 1, 60,
                "web-editor.session.maximum-minutes");
        defaultMinutes = range(config.getInt("web-editor.session.default-minutes", 15), 1,
                maximumMinutes, "web-editor.session.default-minutes");
        maximumFailedAuthAttempts = range(config.getInt(
                        "web-editor.session.maximum-failed-auth-attempts-per-minute", 30),
                5, 120, "web-editor.session.maximum-failed-auth-attempts-per-minute");
        maximumFileBytes = range(config.getInt(
                        "web-editor.editor.maximum-file-bytes", 1_048_576),
                65_536, 2_097_152, "web-editor.editor.maximum-file-bytes");
        assets = loadAssets();
        armed = true;
        lastStopReason = "waiting for a console-started session";
    }

    @Override
    public void disable() {
        armed = false;
        synchronized (lifecycleLock) {
            stopLocked("module disabled");
            assets = Map.of();
        }
    }

    @Override
    public String statusDetail() {
        Session current = session;
        if (!armed) {
            return "disabled";
        }
        if (current == null) {
            return "armed, listener stopped (" + lastStopReason + ")";
        }
        long seconds = Math.max(0L, Duration.between(Instant.now(), current.expiresAt()).getSeconds());
        return "temporary loopback session active on port " + current.port()
                + ", expires in " + seconds + "s";
    }

    public boolean isSessionActive() {
        Session current = session;
        return current != null && Instant.now().isBefore(current.expiresAt());
    }

    public SessionInfo startSession(int requestedMinutes) throws IOException {
        synchronized (lifecycleLock) {
            if (!armed) {
                throw new IllegalStateException("WebEditor module is not enabled");
            }
            int minutes = requestedMinutes <= 0 ? defaultMinutes : requestedMinutes;
            if (minutes < 1 || minutes > maximumMinutes) {
                throw new IllegalArgumentException(
                        "Session duration must be between 1 and " + maximumMinutes + " minutes");
            }
            stopLocked("replaced by a new session");

            byte[] tokenBytes = new byte[32];
            secureRandom.nextBytes(tokenBytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            java.util.Arrays.fill(tokenBytes, (byte) 0);
            byte[] tokenHash = sha256(token.getBytes(StandardCharsets.UTF_8));
            Instant startedAt = Instant.now();
            Instant expiresAt = startedAt.plusSeconds(minutes * 60L);
            String sessionId = UUID.randomUUID().toString();

            HttpServer createdServer = null;
            ExecutorService createdRequestExecutor = null;
            ScheduledExecutorService createdExpiryExecutor = null;
            try {
                createdServer = HttpServer.create(new InetSocketAddress(bindAddress, configuredPort), 16);
                int actualPort = createdServer.getAddress().getPort();
                String baseUrl = "http://" + urlHost(bindAddress) + ":" + actualPort;
                LinkedHashSet<String> originCandidates = new LinkedHashSet<>();
                originCandidates.add(baseUrl);
                if (bindAddress.getAddress().length == 16) {
                    originCandidates.add("http://[::1]:" + actualPort);
                }
                originCandidates.add("http://localhost:" + actualPort);
                Set<String> allowedOrigins = Set.copyOf(originCandidates);
                Session createdSession = new Session(sessionId, tokenHash, startedAt, expiresAt,
                        actualPort, allowedOrigins);

                createdRequestExecutor = Executors.newFixedThreadPool(2, runnable -> daemonThread(
                        runnable, "CoreDSC-WebEditor-HTTP-" + THREAD_SEQUENCE.incrementAndGet()));
                createdExpiryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> daemonThread(
                        runnable, "CoreDSC-WebEditor-Expiry"));
                createdServer.setExecutor(createdRequestExecutor);
                createdServer.createContext("/", this::handleRequest);

                server = createdServer;
                requestExecutor = createdRequestExecutor;
                expiryExecutor = createdExpiryExecutor;
                session = createdSession;
                failedAuthenticationWindow.clear();
                createdServer.start();
                createdExpiryExecutor.schedule(() -> expireSession(sessionId), minutes, TimeUnit.MINUTES);
                lastStopReason = "active";
                plugin.recordFeatureUse("web_editor_session");
                plugin.getLogger().info("[WebEditor] Temporary loopback session started on port "
                        + actualPort + " for " + minutes + " minute(s); capability token omitted from logs.");
                return new SessionInfo(baseUrl + "/#token=" + token, minutes, expiresAt);
            } catch (IOException | RuntimeException error) {
                java.util.Arrays.fill(tokenHash, (byte) 0);
                session = null;
                server = null;
                requestExecutor = null;
                expiryExecutor = null;
                if (createdServer != null) {
                    createdServer.stop(0);
                }
                if (createdRequestExecutor != null) {
                    createdRequestExecutor.shutdownNow();
                }
                if (createdExpiryExecutor != null) {
                    createdExpiryExecutor.shutdownNow();
                }
                lastStopReason = "start failed";
                throw error;
            }
        }
    }

    public boolean stopSession() {
        synchronized (lifecycleLock) {
            boolean active = session != null;
            stopLocked("stopped by administrator");
            return active;
        }
    }

    private void expireSession(String sessionId) {
        synchronized (lifecycleLock) {
            Session current = session;
            if (current == null || !current.id().equals(sessionId)) {
                return;
            }
            stopLocked("session expired");
            plugin.getLogger().info("[WebEditor] Temporary session expired and its listener was stopped.");
        }
    }

    private void stopLocked(String reason) {
        Session currentSession = session;
        session = null;
        if (currentSession != null) {
            java.util.Arrays.fill(currentSession.tokenHash(), (byte) 0);
        }
        HttpServer currentServer = server;
        server = null;
        if (currentServer != null) {
            currentServer.stop(0);
        }
        ExecutorService currentRequestExecutor = requestExecutor;
        requestExecutor = null;
        if (currentRequestExecutor != null) {
            currentRequestExecutor.shutdownNow();
        }
        ScheduledExecutorService currentExpiryExecutor = expiryExecutor;
        expiryExecutor = null;
        if (currentExpiryExecutor != null) {
            currentExpiryExecutor.shutdownNow();
        }
        failedAuthenticationWindow.clear();
        lastStopReason = reason;
    }

    private void handleRequest(HttpExchange exchange) {
        applySecurityHeaders(exchange.getResponseHeaders());
        try {
            String path = exchange.getRequestURI().getPath();
            Asset asset = assetFor(path);
            if (asset != null) {
                requireMethod(exchange, "GET");
                sendBytes(exchange, 200, asset.contentType(), asset.content());
                return;
            }
            if (!path.startsWith("/api/")) {
                throw new HttpProblem(404, "Not found");
            }
            Session authenticated = authenticate(exchange);
            if (authenticated == null) {
                return;
            }
            requireAllowedOrigin(exchange, authenticated);
            handleApi(exchange, authenticated, path);
        } catch (HttpProblem problem) {
            sendProblemSafely(exchange, problem.status, problem.getMessage());
        } catch (InvalidConfigurationException error) {
            sendProblemSafely(exchange, 422, rootMessage(error));
        } catch (ConfigManager.EditorConflictException error) {
            sendProblemSafely(exchange, 409, error.getMessage());
        } catch (IllegalArgumentException error) {
            sendProblemSafely(exchange, 400, error.getMessage());
        } catch (IOException error) {
            plugin.getLogger().warning("[WebEditor] Request failed: " + rootMessage(error));
            sendProblemSafely(exchange, 500, "The configuration operation failed. Check the server log.");
        } catch (RuntimeException error) {
            plugin.getLogger().warning("[WebEditor] Unexpected request failure: " + rootMessage(error));
            sendProblemSafely(exchange, 500, "Unexpected WebEditor failure. Check the server log.");
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, Session authenticated, String path)
            throws IOException, InvalidConfigurationException, HttpProblem {
        if (path.equals("/api/session")) {
            requireMethod(exchange, "GET");
            Map<String, Object> payload;
            synchronized (lifecycleLock) {
                requireActiveSession(authenticated);
                payload = sessionPayload(authenticated);
            }
            sendJson(exchange, 200, payload);
            return;
        }
        if (path.startsWith("/api/file/")) {
            int id = fileId(path, "/api/file/");
            String file = fileForId(id);
            if (exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                ConfigManager.EditorDocument document;
                synchronized (lifecycleLock) {
                    requireActiveSession(authenticated);
                    document = plugin.getConfigManager().readEditorDocument(file, maximumFileBytes);
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("id", id);
                payload.put("path", document.path());
                payload.put("content", document.content());
                payload.put("revision", document.revision());
                payload.put("sizeBytes", document.sizeBytes());
                sendJson(exchange, 200, payload);
                return;
            }
            requireMethod(exchange, "PUT");
            requireTextContent(exchange);
            String expectedRevision = normalizeRevision(
                    exchange.getRequestHeaders().getFirst("If-Match"));
            String content = readBody(exchange, maximumFileBytes);
            ConfigManager.EditorSaveResult result;
            synchronized (lifecycleLock) {
                requireActiveSession(authenticated);
                result = plugin.getConfigManager()
                        .saveEditorDocument(file, content, expectedRevision, maximumFileBytes);
            }
            plugin.recordFeatureUse("web_editor_save");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("path", file);
            payload.put("revision", result.revision());
            payload.put("backup", result.backupPath());
            payload.put("warnings", warningPayload(result.warnings()));
            payload.put("reloadRequired", true);
            sendJson(exchange, 200, payload);
            return;
        }
        if (path.startsWith("/api/validate/")) {
            requireMethod(exchange, "POST");
            requireTextContent(exchange);
            int id = fileId(path, "/api/validate/");
            String file = fileForId(id);
            String content = readBody(exchange, maximumFileBytes);
            ConfigManager.EditorValidation result;
            synchronized (lifecycleLock) {
                requireActiveSession(authenticated);
                result = plugin.getConfigManager()
                        .validateEditorDocument(file, content, maximumFileBytes);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("valid", true);
            payload.put("path", file);
            payload.put("warnings", warningPayload(result.warnings()));
            sendJson(exchange, 200, payload);
            return;
        }
        throw new HttpProblem(404, "Not found");
    }

    private void requireActiveSession(Session authenticated) throws HttpProblem {
        Session current = session;
        if (current != authenticated || !Instant.now().isBefore(authenticated.expiresAt())) {
            throw new HttpProblem(401, "WebEditor session is unavailable or expired");
        }
    }

    private Map<String, Object> sessionPayload(Session authenticated) {
        List<Map<String, Object>> files = new ArrayList<>();
        List<String> paths = plugin.getConfigManager().editableFilePaths();
        for (int id = 0; id < paths.size(); id++) {
            files.add(Map.of("id", id, "path", paths.get(id)));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("product", "CoreDSC WebEditor");
        payload.put("stage", "alpha");
        payload.put("pluginVersion", plugin.getDescription().getVersion());
        payload.put("startedAt", authenticated.startedAt().toString());
        payload.put("expiresAt", authenticated.expiresAt().toString());
        payload.put("maximumFileBytes", maximumFileBytes);
        payload.put("files", files);
        payload.put("secretsExcluded", true);
        payload.put("reloadRequired", true);
        return payload;
    }

    private Session authenticate(HttpExchange exchange) throws IOException {
        Session current = session;
        if (current == null || !Instant.now().isBefore(current.expiresAt())) {
            sendJson(exchange, 401, Map.of("error", "WebEditor session is unavailable or expired"));
            return null;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        boolean valid = false;
        if (authorization != null && authorization.startsWith(AUTH_SCHEME)
                && authorization.length() <= AUTH_SCHEME.length() + 128) {
            String token = authorization.substring(AUTH_SCHEME.length());
            valid = MessageDigest.isEqual(current.tokenHash(),
                    sha256(token.getBytes(StandardCharsets.UTF_8)));
        }
        if (!valid) {
            int status = claimFailedAuthentication() ? 401 : 429;
            sendJson(exchange, status, Map.of("error", status == 401
                    ? "Authentication required" : "Too many failed authentication attempts"));
            return null;
        }
        return current;
    }

    private boolean claimFailedAuthentication() {
        synchronized (failedAuthenticationWindow) {
            long now = System.currentTimeMillis();
            long cutoff = now - 60_000L;
            while (!failedAuthenticationWindow.isEmpty()
                    && failedAuthenticationWindow.peekFirst() < cutoff) {
                failedAuthenticationWindow.removeFirst();
            }
            if (failedAuthenticationWindow.size() >= maximumFailedAuthAttempts) {
                return false;
            }
            failedAuthenticationWindow.addLast(now);
            return true;
        }
    }

    private static void requireAllowedOrigin(HttpExchange exchange, Session authenticated)
            throws HttpProblem {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !authenticated.allowedOrigins().contains(origin)) {
            throw new HttpProblem(403, "Cross-origin request blocked");
        }
    }

    private String fileForId(int id) throws HttpProblem {
        List<String> files = plugin.getConfigManager().editableFilePaths();
        if (id < 0 || id >= files.size()) {
            throw new HttpProblem(404, "Unknown configuration file");
        }
        return files.get(id);
    }

    private static int fileId(String path, String prefix) throws HttpProblem {
        String value = path.substring(prefix.length());
        if (!value.matches("[0-9]{1,3}")) {
            throw new HttpProblem(404, "Unknown configuration file");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new HttpProblem(404, "Unknown configuration file");
        }
    }

    private Asset assetFor(String path) {
        String key = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/app.js" -> "app.js";
            case "/styles.css" -> "styles.css";
            default -> "";
        };
        return key.isEmpty() ? null : assets.get(key);
    }

    private Map<String, Asset> loadAssets() {
        Map<String, Asset> loaded = new LinkedHashMap<>();
        loaded.put("index.html", loadAsset("webeditor/index.html", "text/html; charset=utf-8"));
        loaded.put("app.js", loadAsset("webeditor/app.js", "text/javascript; charset=utf-8"));
        loaded.put("styles.css", loadAsset("webeditor/styles.css", "text/css; charset=utf-8"));
        return Map.copyOf(loaded);
    }

    private Asset loadAsset(String path, String contentType) {
        try (InputStream input = plugin.getResource(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled WebEditor asset: " + path);
            }
            return new Asset(contentType, input.readAllBytes());
        } catch (IOException error) {
            throw new IllegalStateException("Could not read bundled WebEditor asset: " + path, error);
        }
    }

    private static void requireTextContent(HttpExchange exchange) throws HttpProblem {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("text/plain") && !normalized.startsWith("application/yaml")) {
            throw new HttpProblem(415, "Content-Type must be text/plain or application/yaml");
        }
    }

    private static String readBody(HttpExchange exchange, int maximumBytes)
            throws IOException, HttpProblem {
        String rawLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (rawLength != null) {
            try {
                if (Long.parseLong(rawLength) > maximumBytes) {
                    throw new HttpProblem(413, "Configuration draft exceeds the size limit");
                }
            } catch (NumberFormatException error) {
                throw new HttpProblem(400, "Invalid Content-Length header");
            }
        }
        byte[] content = exchange.getRequestBody().readNBytes(maximumBytes + 1);
        if (content.length > maximumBytes) {
            throw new HttpProblem(413, "Configuration draft exceeds the size limit");
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private static String normalizeRevision(String value) {
        String revision = text(value);
        if (revision.length() >= 2 && revision.startsWith("\"") && revision.endsWith("\"")) {
            revision = revision.substring(1, revision.length() - 1);
        }
        return revision.toLowerCase(Locale.ROOT);
    }

    private static List<Map<String, Object>> warningPayload(List<ConfigManager.ConfigIssue> warnings) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ConfigManager.ConfigIssue warning : warnings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", warning.kind().name());
            item.put("path", warning.path());
            item.put("suggestion", warning.suggestion());
            item.put("message", warning.message());
            payload.add(item);
        }
        return payload;
    }

    private static void requireMethod(HttpExchange exchange, String expected) throws HttpProblem {
        if (!exchange.getRequestMethod().equalsIgnoreCase(expected)) {
            exchange.getResponseHeaders().set("Allow", expected);
            throw new HttpProblem(405, "Method not allowed");
        }
    }

    private static void applySecurityHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store, max-age=0");
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                        + "img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
                        + "form-action 'none'");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
    }

    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        sendBytes(exchange, status, "application/json; charset=utf-8",
                MiniJson.write(payload).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] content)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void sendProblemSafely(HttpExchange exchange, int status, String message) {
        try {
            sendJson(exchange, status, Map.of("error", message == null ? "Request failed" : message));
        } catch (IOException ignored) {
                                                                                
        }
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String urlHost(InetAddress address) {
        String host = address.getHostAddress();
        int zone = host.indexOf('%');
        if (zone >= 0) {
            host = host.substring(0, zone);
        }
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    private static int range(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
