package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.config.ConfigManager;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.ModuleManager;
import com.hubertstudios.coredsc.scripting.MiniJson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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

/**
 * Production, loopback-only configuration control plane.
 *
 * <p>The editor deliberately has no public bind mode, never exposes secrets.yml,
 * stores only a SHA-256 token digest, and never applies configuration by itself.
 * Saves are delegated to ConfigManager for contextual validation, backups,
 * optimistic locking and same-directory replacement.</p>
 */
public final class WebEditorModule implements CoreModule {
    private static final Set<String> LOOPBACK_NAMES = Set.of("127.0.0.1", "::1", "localhost");
    private static final String AUTH_SCHEME = "Bearer ";
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final List<String> EMBED_EVENTS = List.of(
            "startup", "shutdown", "join", "quit", "death");

    private static final List<ModuleControl> MODULE_CONTROLS = List.of(
            module("placeholderapi", "PlaceholderAPI", "Compatibility", "Rich placeholders in configured messages."),
            module("delivery-queue", "Delivery queue", "Foundation", "Durable Discord delivery during outages."),
            module("network", "Network mode", "Foundation", "Redis-backed multi-server coordination."),
            module("link", "Account linking", "Identity", "Secure Minecraft and Discord account links."),
            module("link-rewards", "Link rewards", "Identity", "Idempotent rewards after linking."),
            module("nickname-sync", "Nickname sync", "Identity", "Synchronize Minecraft names to Discord."),
            module("booster-rewards", "Booster rewards", "Community", "Server booster reward automation."),
            module("ban-sync", "Ban sync", "Moderation", "Synchronize bans between platforms."),
            module("luckperms-sync", "LuckPerms sync", "Identity", "Role and group synchronization."),
            module("chat-sync", "Chat sync", "Messaging", "Bidirectional Discord and Minecraft chat."),
            module("console", "Smart Console", "Operations", "Classified, deduplicated console incidents."),
            module("server-events", "Server events", "Messaging", "Join, leave, death and lifecycle embeds."),
            module("custom-commands", "Custom commands", "Automation", "Config-driven Minecraft and Discord commands."),
            module("status-channels", "Status channels", "Operations", "Live Discord channel status names."),
            module("cases", "Cases and appeals", "Moderation", "Moderation cases and player appeals."),
            module("moderation-bridge", "Moderation bridge", "Moderation", "Unified moderation audit feed."),
            module("tickets", "Tickets", "Community", "Cross-platform support tickets."),
            module("reports", "Reports", "Moderation", "Player reports and staff workflow."),
            module("applications", "Applications", "Community", "Whitelisting and staff applications."),
            module("workflows", "Workflows", "Automation", "Event-driven automation chains."),
            module("authme", "AuthMe recovery", "Identity", "Guarded Discord password recovery."),
            module("voicechat-sync", "Voice proximity", "Voice", "Discord proximity-room coordination."),
            module("economy-market", "Economy & Market", "Gameplay", "Vault balance, inventory and market terminal."),
            module("lore-sync", "Lore & NPC Sync", "Gameplay", "Cinematic NPC webhook broadcasts."),
            module("competitive", "Competitive ELO", "Gameplay", "PvP ratings and Discord leaderboards."),
            module("web-editor", "Web Editor", "Foundation", "Temporary local visual configuration dashboard."),
            new ModuleControl("python-bot", "Python worker", "Developer", "External CPython automation worker.",
                    "bot/config.yml", "enabled")
    );

    private static final List<ChannelMapping> CHANNEL_MAPPINGS = List.of(
            mapping("guild", "Primary Discord server", "config.yml", "discord.guild-id", "discord.guild-id", "guild", ""),
            mapping("chat-out", "Minecraft → Discord chat", "modules/chat-sync.yml", "minecraft-to-discord.channel-id", "chat-sync.minecraft-to-discord.channel-id", "text", "chat-sync"),
            mapping("chat-in", "Discord → Minecraft chat", "modules/chat-sync.yml", "discord-to-minecraft.channel-id", "chat-sync.discord-to-minecraft.channel-id", "text", "chat-sync"),
            mapping("events", "Server events", "modules/server-events.yml", "channel-id", "server-events.channel-id", "text", "server-events"),
            mapping("console", "Smart Console", "modules/console.yml", "channel-id", "console.channel-id", "text", "console"),
            mapping("tickets", "Tickets parent channel", "modules/tickets.yml", "parent-channel-id", "tickets.parent-channel-id", "text", "tickets"),
            mapping("reports", "Reports parent channel", "modules/reports.yml", "parent-channel-id", "reports.parent-channel-id", "text", "reports"),
            mapping("applications", "Applications parent channel", "modules/applications.yml", "parent-channel-id", "applications.parent-channel-id", "text", "applications"),
            mapping("appeals", "Appeals parent channel", "modules/cases.yml", "appeals.parent-channel-id", "cases.appeals.parent-channel-id", "text", "cases"),
            mapping("voice-category", "Voice room category", "modules/voicechat-sync.yml", "discord.category-id", "voicechat-sync.discord.category-id", "category", "voicechat-sync"),
            mapping("voice-lobby", "Voice lobby", "modules/voicechat-sync.yml", "discord.lobby-channel-id", "voicechat-sync.discord.lobby-channel-id", "voice", "voicechat-sync"),
            mapping("lore", "Default lore channel", "modules/lore-sync.yml", "default-channel-id", "lore-sync.default-channel-id", "text", "lore-sync"),
            mapping("competitive", "Competitive leaderboard", "modules/competitive.yml", "discord.channel-id", "competitive.discord.channel-id", "text", "competitive")
    );

    private static final Set<String> STRUCTURED_PATHS = structuredPaths();

    public record SessionInfo(String url, int durationMinutes, Instant expiresAt) { }

    private record Asset(String contentType, byte[] content) { }

    private record ModuleControl(
            String id,
            String label,
            String category,
            String description,
            String file,
            String path
    ) { }

    private record ChannelMapping(
            String id,
            String label,
            String file,
            String path,
            String runtimePath,
            String channelType,
            String moduleId
    ) { }

    private record Session(
            String id,
            byte[] tokenHash,
            Instant startedAt,
            Instant expiresAt,
            int port,
            Set<String> allowedOrigins
    ) { }

    private static final class HttpProblem extends Exception {
        private static final long serialVersionUID = 1L;

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
            throw new IllegalArgumentException("WebEditor refuses every non-loopback bind address");
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
        if (path.equals("/api/dashboard")) {
            requireMethod(exchange, "GET");
            synchronized (lifecycleLock) {
                requireActiveSession(authenticated);
                sendJson(exchange, 200, dashboardPayload());
            }
            return;
        }
        if (path.equals("/api/discord/guilds")) {
            requireMethod(exchange, "GET");
            synchronized (lifecycleLock) {
                requireActiveSession(authenticated);
                sendJson(exchange, 200, Map.of("guilds", guildPayload()));
            }
            return;
        }
        if (path.equals("/api/discord/channels")) {
            requireMethod(exchange, "GET");
            String guildId = queryParameter(exchange, "guildId");
            synchronized (lifecycleLock) {
                requireActiveSession(authenticated);
                sendJson(exchange, 200, Map.of(
                        "guildId", guildId,
                        "channels", channelPayload(guildId)));
            }
            return;
        }
        if (path.equals("/api/structured")) {
            requireMethod(exchange, "PUT");
            requireJsonContent(exchange);
            Map<String, Object> request = MiniJson.parseObject(readBody(exchange, maximumFileBytes));
            List<ConfigManager.EditorPatch> patches = structuredPatches(request);
            ConfigManager.EditorBatchSaveResult result;
            synchronized (lifecycleLock) {
                requireActiveSession(authenticated);
                result = plugin.getConfigManager().saveEditorPatch(patches, maximumFileBytes);
            }
            plugin.recordFeatureUse("web_editor_structured_save");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("revisions", result.revisions());
            payload.put("backup", result.backupPath());
            payload.put("warnings", warningPayload(result.warnings()));
            payload.put("changedFiles", result.changedFiles());
            payload.put("reloadRequired", !result.changedFiles().isEmpty());
            sendJson(exchange, 200, payload);
            return;
        }
        if (path.equals("/api/apply")) {
            requireMethod(exchange, "POST");
            synchronized (lifecycleLock) {
                requireActiveSession(authenticated);
            }
            sendJson(exchange, 202, Map.of(
                    "accepted", true,
                    "message", "CoreDSC will apply the saved configuration. This temporary session will close."));
            plugin.getCoreScheduler().runGlobalLater(() -> {
                CoreDSCPlugin.ReloadResult result = plugin.reloadConfiguration();
                if (result.success()) {
                    plugin.getLogger().info("[WebEditor] Saved configuration applied successfully.");
                } else {
                    plugin.getLogger().warning("[WebEditor] Apply failed: " + result.message());
                }
            }, 10L);
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

    private Map<String, Object> dashboardPayload() throws IOException {
        FileConfiguration config = plugin.getAppConfig();
        Map<String, String> revisions = dashboardRevisions();
        ModuleManager moduleManager = plugin.getModuleManager();
        Map<String, ModuleManager.ModuleStatus> statuses = moduleManager == null
                ? Map.of() : moduleManager.getStatuses();

        List<Map<String, Object>> modules = new ArrayList<>();
        for (ModuleControl control : MODULE_CONTROLS) {
            ModuleManager.ModuleStatus status = statuses.get(control.id());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", control.id());
            item.put("label", control.label());
            item.put("category", control.category());
            item.put("description", control.description());
            item.put("file", control.file());
            item.put("path", control.path());
            item.put("enabled", config.getBoolean("modules." + control.id(), false));
            item.put("state", status == null ? "UNKNOWN" : status.state().name());
            item.put("detail", status == null ? "Not loaded" : status.detail());
            modules.add(item);
        }

        List<Map<String, Object>> mappings = new ArrayList<>();
        for (ChannelMapping mapping : CHANNEL_MAPPINGS) {
            Object configured = config.get(mapping.runtimePath());
            String value = configured == null ? "" : String.valueOf(configured).trim();
            if (value.equals("0")) value = "";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", mapping.id());
            item.put("label", mapping.label());
            item.put("file", mapping.file());
            item.put("path", mapping.path());
            item.put("runtimePath", mapping.runtimePath());
            item.put("channelType", mapping.channelType());
            item.put("moduleId", mapping.moduleId());
            item.put("value", value);
            mappings.add(item);
        }

        List<Map<String, Object>> embeds = new ArrayList<>();
        for (String event : EMBED_EVENTS) {
            String root = "server-events.events." + event;
            String embed = root + ".embed.";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("event", event);
            item.put("label", eventLabel(event));
            item.put("enabled", config.getBoolean(root + ".enabled", true));
            item.put("title", value(config, embed + "title",
                    value(config, "server-events.embeds.title", "CoreDSC • %server_name%")));
            item.put("description", value(config, embed + "description",
                    value(config, root + ".format", "")));
            item.put("color", value(config, embed + "color",
                    value(config, "server-events.embeds.color", "#5865F2")));
            item.put("thumbnailUrl", value(config, embed + "thumbnail-url", ""));
            item.put("imageUrl", value(config, embed + "image-url", ""));
            item.put("footer", value(config, embed + "footer",
                    value(config, "server-events.embeds.footer", "")));
            embeds.add(item);
        }

        DiscordBotService discord = plugin.getDiscordService();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modules", modules);
        payload.put("mappings", mappings);
        payload.put("embeds", embeds);
        payload.put("revisions", revisions);
        payload.put("guilds", guildPayload());
        payload.put("discordReady", discord != null && discord.isReady());
        payload.put("scheduler", plugin.getCoreScheduler().runtime().name());
        payload.put("rawEditorAvailable", true);
        return payload;
    }

    private Map<String, String> dashboardRevisions() throws IOException {
        Set<String> files = new LinkedHashSet<>();
        for (ModuleControl control : MODULE_CONTROLS) files.add(control.file());
        for (ChannelMapping mapping : CHANNEL_MAPPINGS) files.add(mapping.file());
        files.add("modules/server-events.yml");
        Map<String, String> revisions = new LinkedHashMap<>();
        for (String file : files) {
            ConfigManager.EditorDocument document = plugin.getConfigManager()
                    .readEditorDocument(file, maximumFileBytes);
            revisions.put(file, document.revision());
        }
        return Map.copyOf(revisions);
    }

    private List<Map<String, Object>> guildPayload() {
        DiscordBotService service = plugin.getDiscordService();
        JDA jda = service == null ? null : service.getJda();
        if (jda == null) return List.of();
        long configured = service.getConfiguredGuildId();
        return jda.getGuilds().stream()
                .sorted(Comparator.comparing(Guild::getName, String.CASE_INSENSITIVE_ORDER))
                .map(guild -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", guild.getId());
                    item.put("name", guild.getName());
                    item.put("configured", guild.getIdLong() == configured);
                    item.put("textChannels", guild.getTextChannels().size());
                    item.put("voiceChannels", guild.getVoiceChannels().size());
                    return item;
                }).toList();
    }

    private List<Map<String, Object>> channelPayload(String guildId) throws HttpProblem {
        if (!positiveSnowflake(guildId)) {
            throw new HttpProblem(400, "guildId must be a positive Discord guild ID");
        }
        DiscordBotService service = plugin.getDiscordService();
        JDA jda = service == null ? null : service.getJda();
        if (jda == null || !service.isReady()) {
            throw new HttpProblem(503, "Discord is not ready. Check the bot connection and try again.");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new HttpProblem(404, "The bot cannot see that Discord guild");
        }

        List<Map<String, Object>> channels = new ArrayList<>();
        guild.getCategories().forEach(category -> channels.add(channelItem(
                category.getId(), category.getName(), "category", "", true, category.getPosition())));
        guild.getTextChannels().forEach(channel -> channels.add(channelItem(
                channel.getId(), channel.getName(), "text", parentName(channel),
                channel.canTalk(), channel.getPosition())));
        guild.getVoiceChannels().forEach(channel -> channels.add(channelItem(
                channel.getId(), channel.getName(), "voice", parentName(channel),
                true, channel.getPosition())));
        channels.sort(Comparator
                .comparing((Map<String, Object> item) -> String.valueOf(item.get("parent")),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(item -> String.valueOf(item.get("type")))
                .thenComparingInt(item -> ((Number) item.get("position")).intValue()));
        return List.copyOf(channels);
    }

    private static Map<String, Object> channelItem(
            String id,
            String name,
            String type,
            String parent,
            boolean usable,
            int position
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", name);
        item.put("type", type);
        item.put("parent", parent);
        item.put("usable", usable);
        item.put("position", position);
        return item;
    }

    private List<ConfigManager.EditorPatch> structuredPatches(Map<String, Object> request)
            throws HttpProblem {
        Object rawChanges = request.get("changes");
        if (!(rawChanges instanceof List<?> changes) || changes.isEmpty()) {
            throw new HttpProblem(400, "changes must be a non-empty JSON array");
        }
        if (changes.size() > 100) {
            throw new HttpProblem(400, "A structured save may contain at most 100 changes");
        }
        List<ConfigManager.EditorPatch> patches = new ArrayList<>();
        for (Object raw : changes) {
            if (!(raw instanceof Map<?, ?> change)) {
                throw new HttpProblem(400, "Every structured change must be a JSON object");
            }
            String file = text(change.get("file"));
            String key = text(change.get("path"));
            if (!STRUCTURED_PATHS.contains(structuredKey(file, key))) {
                throw new HttpProblem(403, "The visual editor is not allowed to change " + file + " -> " + key);
            }
            if (!change.containsKey("value")) {
                throw new HttpProblem(400, "Structured change is missing value");
            }
            patches.add(new ConfigManager.EditorPatch(
                    file,
                    key,
                    change.get("value"),
                    text(change.get("revision"))));
        }
        return List.copyOf(patches);
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
        payload.put("stage", "production-local");
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

    private static ModuleControl module(
            String id,
            String label,
            String category,
            String description
    ) {
        return new ModuleControl(id, label, category, description,
                "modules/" + id + ".yml", "enabled");
    }

    private static ChannelMapping mapping(
            String id,
            String label,
            String file,
            String path,
            String runtimePath,
            String channelType,
            String moduleId
    ) {
        return new ChannelMapping(id, label, file, path, runtimePath, channelType, moduleId);
    }

    private static Set<String> structuredPaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (ModuleControl control : MODULE_CONTROLS) {
            paths.add(structuredKey(control.file(), control.path()));
        }
        for (ChannelMapping mapping : CHANNEL_MAPPINGS) {
            paths.add(structuredKey(mapping.file(), mapping.path()));
        }
        for (String event : EMBED_EVENTS) {
            String root = "events." + event;
            paths.add(structuredKey("modules/server-events.yml", root + ".enabled"));
            for (String key : List.of("title", "description", "color",
                    "thumbnail-url", "image-url", "footer")) {
                paths.add(structuredKey("modules/server-events.yml", root + ".embed." + key));
            }
        }
        return Set.copyOf(paths);
    }

    private static String structuredKey(String file, String path) {
        return file + "\u0000" + path;
    }

    private static String eventLabel(String event) {
        return switch (event) {
            case "startup" -> "Server start";
            case "shutdown" -> "Server stop";
            case "join" -> "Player join";
            case "quit" -> "Player leave";
            case "death" -> "Player death";
            default -> event;
        };
    }

    private static String parentName(TextChannel channel) {
        return channel.getParentCategory() == null ? "" : channel.getParentCategory().getName();
    }

    private static String parentName(VoiceChannel channel) {
        return channel.getParentCategory() == null ? "" : channel.getParentCategory().getName();
    }

    private static String queryParameter(HttpExchange exchange, String requested) throws HttpProblem {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) return "";
        if (query.length() > 2048) throw new HttpProblem(414, "Query string is too long");
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String rawKey = separator < 0 ? pair : pair.substring(0, separator);
            if (!URLDecoder.decode(rawKey, StandardCharsets.UTF_8).equals(requested)) continue;
            String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            return URLDecoder.decode(rawValue, StandardCharsets.UTF_8).trim();
        }
        return "";
    }

    private static boolean positiveSnowflake(String value) {
        try {
            return value != null && Long.parseUnsignedLong(value.trim()) > 0L;
        } catch (NumberFormatException error) {
            return false;
        }
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

    private static void requireJsonContent(HttpExchange exchange) throws HttpProblem {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("application/json")) {
            throw new HttpProblem(415, "Content-Type must be application/json");
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
                        + "img-src 'self' data: https:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
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
            // The client disconnected before the error response could be written.
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

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String value(FileConfiguration config, String path, String fallback) {
        String configured = config.getString(path);
        return configured == null ? fallback : configured.trim();
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
