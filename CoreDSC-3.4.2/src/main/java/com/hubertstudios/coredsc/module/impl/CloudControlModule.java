package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.cloud.AutoModOperationService;
import com.hubertstudios.coredsc.cloud.ChannelOperationService;
import com.hubertstudios.coredsc.cloud.CloudConfigurationService;
import com.hubertstudios.coredsc.cloud.CloudEditorAgent;
import com.hubertstudios.coredsc.cloud.CloudIdentity;
import com.hubertstudios.coredsc.cloud.CloudMediaStore;
import com.hubertstudios.coredsc.cloud.CloudOperationRouter;
import com.hubertstudios.coredsc.cloud.ModerationOperationService;
import com.hubertstudios.coredsc.cloud.OperationsCoordinator;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.ModuleManager;
import com.hubertstudios.coredsc.scheduler.CoreTask;
import com.hubertstudios.coredsc.storage.CloudOperationRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Outbound-only hosted dashboard agent and local operations security boundary. */
public final class CloudControlModule implements CoreModule, Listener {
    private enum State { INITIALIZING, CONNECTING, READY, FAILED, STOPPED }

    public record EditorLink(String url, long expiresAt, boolean alreadyPaired) { }
    public record PairingConfirmation(String discordUserId, String displayName) { }

    private final CoreDSCPlugin plugin;
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean active;
    private volatile String detail = "not started";
    private volatile String serverName = "CoreDSC Server";
    private volatile String minecraftVersion = "unknown";
    private volatile CloudEditorAgent agent;
    private volatile OperationsCoordinator operations;
    private volatile ModerationOperationService moderation;
    private volatile CloudOperationRepository repository;
    private CoreTask initializationTask;
    private CoreTask expiryTask;
    private CoreTask cleanupTask;

    public CloudControlModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "cloud-control";
    }

    @Override
    public void enable() {
        active = true;
        state.set(State.INITIALIZING);
        detail = "creating local instance identity";
        serverName = clean(environmentOrConfig("COREDSC_CLOUD_INSTANCE_NAME",
                "cloud-control.instance-name", plugin.getServer().getName()), 80);
        if (serverName.isBlank()) serverName = clean(plugin.getServer().getName(), 80);
        minecraftVersion = clean(plugin.getServer().getMinecraftVersion(), 80);
        plugin.getServer().getOnlinePlayers().forEach(player -> onlinePlayers.add(player.getUniqueId()));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        initializationTask = plugin.getCoreScheduler().runAsync(() -> {
            try {
                CloudIdentity identity = CloudIdentity.loadOrCreate(plugin);
                plugin.runSync(() -> initializeOnGlobal(identity));
            } catch (Throwable error) {
                plugin.runSync(() -> fail("Could not create or load the local Ed25519 identity", error));
            }
        });
    }

    private void initializeOnGlobal(CloudIdentity identity) {
        if (!active || !plugin.isEnabled()) return;
        try {
            repository = new CloudOperationRepository(plugin.getStorage());
            CloudConfigurationService configuration = new CloudConfigurationService(plugin);
            ChannelOperationService channels = new ChannelOperationService(plugin, repository);
            moderation = new ModerationOperationService(plugin, repository);
            operations = new OperationsCoordinator(plugin, repository, channels, this::onlineSnapshot);
            AutoModOperationService autoMod = new AutoModOperationService(plugin);
            CloudMediaStore mediaStore = new CloudMediaStore(plugin);
            CloudOperationRouter router = new CloudOperationRouter(plugin, repository, configuration,
                    moderation, channels, operations, autoMod, mediaStore, this::healthSnapshot, this::onlineSnapshot);

            URI endpoint = URI.create(environmentOrConfig("COREDSC_CLOUD_ENDPOINT",
                    "cloud-control.endpoint", "wss://coredsc.example.invalid/api/v1/agent/connect"));
            agent = new CloudEditorAgent(
                    plugin,
                    identity,
                    router,
                    this::healthSnapshot,
                    endpoint,
                    serverName,
                    minecraftVersion,
                    plugin.getCoreScheduler().runtime().name(),
                    plugin.getAppConfig().getInt("cloud-control.transport.maximum-queued-frames", 256),
                    plugin.getAppConfig().getLong("cloud-control.transport.maximum-backoff-seconds", 300L));

            operations.start();
            state.set(State.CONNECTING);
            detail = "outbound authentication in progress";
            agent.start();
            expiryTask = plugin.getCoreScheduler().runGlobalTimer(this::expireSanctions,
                    100L, 20L * 60L);
            cleanupTask = plugin.getCoreScheduler().runGlobalTimer(this::cleanupResults,
                    20L * 60L, 20L * 60L * 60L * 6L);
        } catch (Throwable error) {
            fail("Cloud control initialization failed", error);
        }
    }

    @Override
    public void disable() {
        active = false;
        state.set(State.STOPPED);
        detail = "module disabled";
        HandlerList.unregisterAll(this);
        cancel(initializationTask);
        cancel(expiryTask);
        cancel(cleanupTask);
        initializationTask = null;
        expiryTask = null;
        cleanupTask = null;
        CloudEditorAgent currentAgent = agent;
        agent = null;
        if (currentAgent != null) currentAgent.close();
        OperationsCoordinator currentOperations = operations;
        operations = null;
        if (currentOperations != null) currentOperations.stop();
        moderation = null;
        repository = null;
        onlinePlayers.clear();
    }

    @Override
    public String statusDetail() {
        CloudEditorAgent current = agent;
        if (current != null) {
            if (current.connected()) state.set(State.READY);
            detail = current.detail();
        }
        return state.get() + " · " + detail;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        onlinePlayers.add(playerId);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        onlinePlayers.remove(playerId);
    }

    /** Player pairing is identity-bound and therefore requires an existing CoreDSC account link. */
    public CompletableFuture<EditorLink> createPlayerEditorLink(UUID playerId, String playerName) {
        if (playerId == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Player UUID is required"));
        CloudEditorAgent current;
        try {
            current = requireAgent();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
        return new LinkedAccountRepository(plugin.getStorage()).findByMinecraftUuid(playerId.toString())
                .thenCompose(link -> {
                    if (link.isEmpty()) return CompletableFuture.failedFuture(new SecurityException(
                            "Link your Minecraft account to Discord first, or create the editor link from the local console."));
                    return current.createEditorLink(playerId, clean(playerName, 16),
                            link.get().discordUserId());
                }).thenApply(CloudControlModule::editorLink);
    }

    /** Local console links are unbound until Discord OAuth claim and the second console confirmation. */
    public CompletableFuture<EditorLink> createConsoleEditorLink() {
        try {
            return requireAgent().createEditorLink(null, "Local console", "")
                    .thenApply(CloudControlModule::editorLink);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    /**
     * Starts an explicit ownership recovery. This is intentionally separate from normal editor links
     * and can be invoked only by the local console command path, never by players or RCON.
     */
    public CompletableFuture<EditorLink> createConsoleOwnershipRecoveryLink() {
        try {
            return requireAgent().createEditorLink(null, "Local console ownership recovery", "", true)
                    .thenApply(CloudControlModule::editorLink);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<PairingConfirmation> confirmPairing(String code) {
        try {
            return requireAgent().confirmPairing(code).thenApply(result -> new PairingConfirmation(
                    clean(result.get("discordUserId"), 32), clean(result.get("displayName"), 80)));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public UUID instanceId() {
        CloudEditorAgent current = agent;
        return current == null ? null : current.instanceId();
    }

    public boolean connected() {
        CloudEditorAgent current = agent;
        return current != null && current.connected();
    }

    private CloudEditorAgent requireAgent() {
        CloudEditorAgent current = agent;
        if (!active || current == null) {
            throw new IllegalStateException("Cloud agent is still initializing; retry in a few seconds");
        }
        if (!current.connected()) {
            throw new IllegalStateException("Cloud agent is not connected: " + current.detail());
        }
        return current;
    }

    private Set<UUID> onlineSnapshot() {
        return Set.copyOf(onlinePlayers);
    }

    private Map<String, Object> healthSnapshot() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("serverName", serverName);
        health.put("serverState", plugin.getStartupState().name());
        health.put("onlinePlayers", onlinePlayers.size());
        health.put("uptimeMillis", Math.max(0L, System.currentTimeMillis() - startedAt));
        health.put("scheduler", plugin.getCoreScheduler().runtime().name());
        var storage = plugin.getStorage();
        health.put("storageState", storage == null ? "NOT_INITIALISED" : storage.getState().name());
        health.put("databaseQueue", storage == null ? 0 : storage.getQueuedOperationCount());
        health.put("databaseCapacity", storage == null ? 0 : storage.getQueueCapacity());
        health.put("databasePeak", storage == null ? 0 : storage.getQueueHighWaterMark());
        health.put("databaseRejected", storage == null ? 0L : storage.getRejectedOperationCount());
        var discord = plugin.getDiscordService();
        health.put("discordState", discord == null ? "NOT_INITIALISED" : discord.getState().name());
        ModuleManager modules = plugin.getModuleManager();
        if (modules == null) {
            health.put("enabledModules", 0);
            health.put("failedModules", 0);
        } else {
            Map<String, ModuleManager.ModuleStatus> statuses = modules.getStatuses();
            health.put("enabledModules", (int) statuses.values().stream()
                    .filter(status -> status.state() == ModuleManager.ModuleState.ENABLED).count());
            health.put("failedModules", (int) statuses.values().stream()
                    .filter(status -> status.state() == ModuleManager.ModuleState.FAILED).count());
        }
        OperationsCoordinator currentOperations = operations;
        if (currentOperations != null) {
            health.put("incident", currentOperations.incident("incident.status", Map.of(), "health",
                    "CoreDSC", "health").getNow(Map.of("active", false)));
            health.put("maintenance", currentOperations.maintenance("maintenance.status", Map.of(), "health",
                    "CoreDSC", "health").getNow(Map.of("active", false)));
        }
        return Map.copyOf(health);
    }

    private void expireSanctions() {
        ModerationOperationService current = moderation;
        if (!active || current == null) return;
        current.expireDueSanctions().thenAccept(count -> {
            if (count > 0) plugin.getLogger().info("[Cloud] Expired " + count
                    + " temporary cross-platform sanction(s).");
        }).exceptionally(error -> {
            plugin.getLogger().warning("[Cloud] Sanction expiry sweep failed: " + rootMessage(error));
            return null;
        });
    }

    private void cleanupResults() {
        CloudOperationRepository current = repository;
        if (!active || current == null) return;
        long retentionDays = Math.max(1L, Math.min(365L, plugin.getAppConfig().getLong(
                "cloud-control.idempotency-retention-days", 30L)));
        current.deleteResultsOlderThan(System.currentTimeMillis() - Duration.ofDays(retentionDays).toMillis())
                .exceptionally(error -> {
                    plugin.getLogger().warning("[Cloud] Idempotency cleanup failed: " + rootMessage(error));
                    return 0;
                });
    }

    private void fail(String context, Throwable error) {
        state.set(State.FAILED);
        detail = context + ": " + rootMessage(error);
        CloudEditorAgent currentAgent = agent;
        agent = null;
        if (currentAgent != null) currentAgent.close();
        OperationsCoordinator currentOperations = operations;
        operations = null;
        if (currentOperations != null) plugin.runSync(currentOperations::stop);
        plugin.recordModuleFailure(id(), error);
        ModuleManager modules = plugin.getModuleManager();
        if (modules != null) modules.markRuntimeFailed(id(), detail);
        plugin.addStartupWarning(detail);
        plugin.getLogger().log(java.util.logging.Level.SEVERE, "[Cloud] " + detail, error);
    }

    private static EditorLink editorLink(Map<String, Object> value) {
        String url = clean(value.get("url"), 2_048);
        if (!url.startsWith("https://") && !url.startsWith("http://localhost")) {
            throw new SecurityException("Cloud returned an invalid editor URL");
        }
        long expiresAt = value.get("expiresAt") instanceof Number number ? number.longValue() : 0L;
        return new EditorLink(url, expiresAt, Boolean.TRUE.equals(value.get("paired")));
    }

    private static void cancel(CoreTask task) {
        if (task != null) task.cancel();
    }

    private static String clean(Object value, int maximum) {
        String text = value == null ? "" : String.valueOf(value)
                .replace('\0', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() <= maximum ? text : text.substring(0, maximum);
    }

    private String environmentOrConfig(String environment, String path, String fallback) {
        String fromEnvironment = System.getenv(environment);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) return fromEnvironment.trim();
        String configured = plugin.getAppConfig().getString(path, fallback);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }
}
