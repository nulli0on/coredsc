package com.hubertstudios.coredsc.metrics;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.module.ModuleManager;
import com.hubertstudios.coredsc.module.impl.PythonBotModule;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimpleBarChart;
import org.bstats.charts.SimplePie;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Official bStats integration for aggregate, privacy-safe adoption metrics.
 *
 * <p>No custom endpoint exists. Per-plugin settings live in {@code telemetry.yml};
 * the server-wide bStats opt-out remains authoritative.</p>
 *
 * <p>All Bukkit/config access happens while a snapshot is built on the server
 * thread. bStats chart callbacks only read the immutable snapshot, because the
 * bStats scheduler can request chart values asynchronously.</p>
 */
public final class MetricsService {
    public enum State {
        DISABLED,
        RUNNING,
        FAILED
    }

    /** CoreDSC's registered Bukkit plugin ID on bStats. */
    public static final int BSTATS_PLUGIN_ID = 32949;

    private static final List<String> MODULE_IDS = List.of(
            "placeholderapi", "delivery-queue", "network", "link", "link-rewards",
            "nickname-sync", "booster-rewards", "ban-sync", "luckperms-sync",
            "chat-sync", "console", "server-events", "custom-commands", "status-channels",
            "cases", "moderation-bridge", "tickets", "reports", "applications",
            "workflows", "authme", "voicechat-sync", "economy-market", "lore-sync",
            "competitive", "web-editor", "python-bot"
    );

    private static final Set<String> SERVER_EVENT_IDS = Set.of(
            "startup", "shutdown", "join", "quit", "first-join", "world", "death",
            "advancement", "kick", "account-link", "account-unlink", "ticket-create",
            "ticket-close", "report-create"
    );

    private final CoreDSCPlugin plugin;
    private volatile State state = State.DISABLED;
    private volatile String detail = "not started";
    private static final Set<String> FEATURE_ACTIVITY_KEYS = Set.of(
            "chat_mc_to_discord", "chat_discord_to_mc", "link_code_created", "account_linked",
            "account_unlinked", "server_event", "delivery_queued", "ticket_created",
            "report_created", "application_created", "custom_command", "workflow_run",
            "python_execution", "status_update", "role_sync", "nickname_sync", "ban_sync",
            "booster_reward", "link_reward", "web_editor_session", "web_editor_save",
            "web_editor_structured_save", "economy_balance", "economy_inventory",
            "economy_market", "lore_event", "competitive_match", "competitive_leaderboard",
            "smart_console_incident"
    );

    private volatile Snapshot snapshot = Snapshot.empty();
    private final Map<String, LongAdder> featureActivity = new ConcurrentHashMap<>();
    private volatile boolean featureActivityEnabled = true;
    private Metrics metrics;

    public MetricsService(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        File file = new File(plugin.getDataFolder(), "telemetry.yml");
        try {
            YamlConfiguration config = loadTelemetry(file);
            boolean metricsEnabled = config.getBoolean("bstats.enabled", true);
            featureActivityEnabled = metricsEnabled
                    && config.getBoolean("bstats.feature-activity", true);
            if (!metricsEnabled) {
                featureActivity.clear();
                state = State.DISABLED;
                detail = "disabled in telemetry.yml";
                return;
            }

            refreshSnapshotNow();
            Metrics created = new Metrics(plugin, BSTATS_PLUGIN_ID);
            registerCharts(created);
            metrics = created;
            state = State.RUNNING;
            detail = "official bStats active (plugin id " + BSTATS_PLUGIN_ID + ")";
            plugin.getLogger().info("[Metrics] Official bStats metrics started. "
                    + "Opt out in plugins/CoreDSC/telemetry.yml or plugins/bStats/config.yml.");
        } catch (IOException | InvalidConfigurationException | RuntimeException error) {
            featureActivityEnabled = false;
            featureActivity.clear();
            metrics = null;
            state = State.FAILED;
            detail = rootMessage(error);
            plugin.getLogger().warning("[Metrics] bStats could not start: " + detail
                    + ". CoreDSC will continue without metrics.");
        }
    }

    /**
     * Refreshes all chart values after CoreDSC configuration/modules change.
     * The actual snapshot construction is always marshalled to the server thread.
     */
    public void refreshSnapshot() {
        if (state == State.DISABLED || !plugin.isEnabled()) {
            return;
        }
        plugin.runSync(this::refreshSnapshotSafely);
    }

    /**
     * Re-reads telemetry.yml during /coredsc reload. Enabling metrics after a
     * disabled startup is supported. The official bStats client has no safe
     * per-plugin shutdown hook, so disabling an already-running client takes
     * full effect after the next server restart; feature counters stop
     * immediately and the status text states that boundary explicitly.
     */
    public void reloadConfiguration() {
        File file = new File(plugin.getDataFolder(), "telemetry.yml");
        try {
            YamlConfiguration config = loadTelemetry(file);
            boolean requestedActivity = config.getBoolean("bstats.feature-activity", true);
            boolean requestedEnabled = config.getBoolean("bstats.enabled", true);
            featureActivityEnabled = requestedActivity && requestedEnabled;
            if (!featureActivityEnabled) {
                featureActivity.clear();
            }

            if (!requestedEnabled) {
                if (metrics != null && state == State.RUNNING) {
                    detail = "disable requested in telemetry.yml; restart required to stop the active bStats client";
                } else {
                    state = State.DISABLED;
                    detail = "disabled in telemetry.yml";
                }
                return;
            }

            if (metrics == null || state != State.RUNNING) {
                start();
                return;
            }
            refreshSnapshot();
            detail = "official bStats active (plugin id " + BSTATS_PLUGIN_ID + ")";
        } catch (IOException | InvalidConfigurationException | RuntimeException error) {
            plugin.getLogger().warning("[Metrics] telemetry.yml reload failed; existing metrics state was kept: "
                    + rootMessage(error));
        }
    }

    public void stop() {
        // The official bStats client binds its scheduler to the Bukkit plugin
        // lifecycle and intentionally exposes no per-plugin shutdown method.
        // Stop accepting/counting feature activity immediately so module teardown
        // cannot accumulate data after the plugin has begun disabling.
        featureActivityEnabled = false;
        featureActivity.clear();
        metrics = null;
        state = State.DISABLED;
        detail = "stopped with plugin lifecycle";
    }

    public State getState() {
        return state;
    }

    public String getDetail() {
        return detail;
    }

    private void refreshSnapshotSafely() {
        try {
            refreshSnapshotNow();
        } catch (RuntimeException error) {
            plugin.getLogger().warning("[Metrics] Could not refresh chart snapshot: " + rootMessage(error));
        }
    }

    private void refreshSnapshotNow() {
        FileConfiguration config = plugin.getAppConfig();
        ModuleManager manager = plugin.getModuleManager();
        Map<String, ModuleManager.ModuleStatus> statuses = manager == null
                ? Map.of() : manager.getStatuses();

        Map<String, Integer> configuredModules = new LinkedHashMap<>();
        Map<String, Integer> activeModules = new LinkedHashMap<>();
        int configuredCount = 0;
        int failedCount = 0;
        for (String moduleId : MODULE_IDS) {
            if (config.getBoolean("modules." + moduleId, false)) {
                configuredModules.put(moduleId, 1);
                configuredCount++;
            }
            ModuleManager.ModuleStatus status = statuses.get(moduleId);
            if (status != null && status.state() == ModuleManager.ModuleState.ENABLED) {
                activeModules.put(moduleId, 1);
            } else if (status != null && status.state() == ModuleManager.ModuleState.FAILED) {
                failedCount++;
            }
        }

        if (configuredModules.isEmpty()) {
            configuredModules.put("none", 1);
        }
        if (activeModules.isEmpty()) {
            activeModules.put("none", 1);
        }

        Map<String, Integer> dependencies = new LinkedHashMap<>();
        addInstalledDependency(dependencies, "AuthMe", "AuthMe");
        addInstalledDependency(dependencies, "LuckPerms", "LuckPerms");
        addInstalledDependency(dependencies, "PlaceholderAPI", "PlaceholderAPI");
        addInstalledDependency(dependencies, "SimpleVoiceChat", "voicechat");
        addInstalledDependency(dependencies, "Vault", "Vault");
        if (dependencies.isEmpty()) {
            dependencies.put("none", 1);
        }

        PythonBotModule python = manager == null ? null : manager.getModule(PythonBotModule.class);
        int scriptCount = python == null ? 0 : python.loadedScripts().size();
        int eventAdapterCount = python == null ? 0 : python.configuredEventBridges();
        String pythonState = python == null
                ? "disabled"
                : normalizeEnum(python.workerState().name(), Set.of(
                        "stopped", "starting", "ready", "failed"), "other");

        int statusCount = countConfiguredEntries(config.getMapList("status-channels.channels"));
        Map<String, Integer> statusTypes = statusChannelTypes(config);
        if (statusTypes.isEmpty()) {
            statusTypes = Map.of("none", 1);
        }

        int enabledServerEvents = countEnabledServerEvents(config);
        int customCommands = countEnabledMapEntries(config.getMapList("custom-commands.commands"));
        int workflows = countEnabledMapEntries(config.getMapList("workflows.definitions"));
        int supportFeatures = countTrue(
                isActive(statuses, "cases"),
                isActive(statuses, "tickets"),
                isActive(statuses, "reports"),
                isActive(statuses, "applications")
        );

        snapshot = new Snapshot(
                immutable(configuredModules),
                immutable(activeModules),
                immutable(dependencies),
                failedCount == 0 ? "healthy" : "has_failed_modules",
                bucketCount(configuredCount),
                normalizeLanguage(config.getString("language", "en")),
                normalizeEnum(config.getString("discord.command-registration", "guild"),
                        Set.of("guild", "global"), "other"),
                normalizeTokenSource(config.getString("discord.token-source", "ENV")),
                chatSyncDirection(config, statuses),
                chatWebhookMode(config, statuses),
                webhookAvatarMode(config, statuses),
                reverseChatAccountPolicy(config, statuses),
                reverseChatRolePolicy(config, statuses),
                linkRequirement(config, statuses),
                networkMode(config, statuses),
                consoleRemoteMode(config, statuses),
                luckPermsAuthority(config, statuses),
                serverEventsProfile(config, statuses, enabledServerEvents),
                bucketCount(statusCount),
                immutable(statusTypes),
                statusOfflineNaming(config, statuses),
                voiceChatMode(config, statuses),
                voiceChatGuestPolicy(config, statuses),
                pythonState,
                bucketCount(scriptCount),
                bucketCount(eventAdapterCount),
                bucketCount(customCommands),
                bucketCount(workflows),
                bucketCount(enabledServerEvents),
                deliveryQueueCapacity(config, statuses),
                bucketCount(supportFeatures),
                config.getBoolean("reports.chat-history.enabled", false)
                        && isActive(statuses, "reports") ? "enabled" : "disabled"
        );
    }

    private void registerCharts(Metrics target) {
        target.addCustomChart(new SimpleBarChart("configured_modules", () -> snapshot.configuredModules()));
        target.addCustomChart(new SimpleBarChart("active_modules", () -> snapshot.activeModules()));
        target.addCustomChart(new SimpleBarChart("installed_dependencies", () -> snapshot.dependencies()));
        target.addCustomChart(new SimplePie("module_health", () -> snapshot.moduleHealth()));
        target.addCustomChart(new SimplePie("configured_module_count", () -> snapshot.configuredModuleCount()));
        target.addCustomChart(new SimplePie("language", () -> snapshot.language()));
        target.addCustomChart(new SimplePie("discord_command_registration", () -> snapshot.commandRegistration()));
        target.addCustomChart(new SimplePie("discord_token_source", () -> snapshot.tokenSource()));
        target.addCustomChart(new SimplePie("chat_sync_direction", () -> snapshot.chatDirection()));
        target.addCustomChart(new SimplePie("chat_webhook_mode", () -> snapshot.chatWebhookMode()));
        target.addCustomChart(new SimplePie("webhook_avatar_mode", () -> snapshot.webhookAvatarMode()));
        target.addCustomChart(new SimplePie("reverse_chat_account_policy", () -> snapshot.reverseChatAccountPolicy()));
        target.addCustomChart(new SimplePie("reverse_chat_role_policy", () -> snapshot.reverseChatRolePolicy()));
        target.addCustomChart(new SimplePie("link_requirement", () -> snapshot.linkRequirement()));
        target.addCustomChart(new SimplePie("network_mode", () -> snapshot.networkMode()));
        target.addCustomChart(new SimplePie("console_remote_mode", () -> snapshot.consoleRemoteMode()));
        target.addCustomChart(new SimplePie("luckperms_initial_authority", () -> snapshot.luckPermsAuthority()));
        target.addCustomChart(new SimplePie("server_events_profile", () -> snapshot.serverEventsProfile()));
        target.addCustomChart(new SimplePie("status_channel_count", () -> snapshot.statusChannelCount()));
        target.addCustomChart(new SimpleBarChart("status_channel_types", () -> snapshot.statusChannelTypes()));
        target.addCustomChart(new SimplePie("status_offline_naming", () -> snapshot.statusOfflineNaming()));
        target.addCustomChart(new SimplePie("voicechat_mode", () -> snapshot.voiceChatMode()));
        target.addCustomChart(new SimplePie("voicechat_guest_policy", () -> snapshot.voiceChatGuestPolicy()));
        target.addCustomChart(new SimplePie("python_worker_state", () -> snapshot.pythonState()));
        target.addCustomChart(new SimplePie("python_script_count", () -> snapshot.pythonScriptCount()));
        target.addCustomChart(new SimplePie("python_event_adapter_count", () -> snapshot.pythonEventAdapterCount()));
        target.addCustomChart(new SimplePie("custom_command_count", () -> snapshot.customCommandCount()));
        target.addCustomChart(new SimplePie("workflow_count", () -> snapshot.workflowCount()));
        target.addCustomChart(new SimplePie("enabled_server_event_count", () -> snapshot.serverEventCount()));
        target.addCustomChart(new SimplePie("delivery_queue_capacity", () -> snapshot.deliveryQueueCapacity()));
        target.addCustomChart(new SimplePie("support_feature_count", () -> snapshot.supportFeatureCount()));
        target.addCustomChart(new SimplePie("reports_chat_history", () -> snapshot.reportsChatHistory()));
        target.addCustomChart(new SimpleBarChart("feature_activity", this::drainFeatureActivity));
    }

    /** Records one bounded, anonymous feature-use event for the next bStats submission. */
    public void recordFeatureUse(String feature) {
        if (!featureActivityEnabled || feature == null || !FEATURE_ACTIVITY_KEYS.contains(feature)) {
            return;
        }
        featureActivity.computeIfAbsent(feature, ignored -> new LongAdder()).increment();
    }

    private Map<String, Integer> drainFeatureActivity() {
        if (!featureActivityEnabled) {
            return Map.of();
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String key : FEATURE_ACTIVITY_KEYS) {
            LongAdder counter = featureActivity.get(key);
            if (counter == null) {
                continue;
            }
            long count = counter.sumThenReset();
            if (count > 0L) {
                values.put(key, (int) Math.min(count, 10_000L));
            }
        }
        return values;
    }

    private static YamlConfiguration loadTelemetry(File file)
            throws IOException, InvalidConfigurationException {
        if (!file.isFile()) {
            throw new IOException("telemetry.yml does not exist");
        }
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        return config;
    }

    private void addInstalledDependency(Map<String, Integer> output, String chartName, String pluginName) {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin(pluginName);
        if (dependency != null && dependency.isEnabled()) {
            output.put(chartName, 1);
        }
    }

    private static boolean isActive(Map<String, ModuleManager.ModuleStatus> statuses, String moduleId) {
        ModuleManager.ModuleStatus status = statuses.get(moduleId);
        return status != null && status.state() == ModuleManager.ModuleState.ENABLED;
    }

    private static int countConfiguredEntries(List<Map<?, ?>> entries) {
        int count = 0;
        for (Map<?, ?> entry : entries) {
            if (!string(entry.get("id")).isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static int countEnabledMapEntries(List<Map<?, ?>> entries) {
        int count = 0;
        for (Map<?, ?> entry : entries) {
            Object enabled = entry.get("enabled");
            if (!(enabled instanceof Boolean value) || value) {
                count++;
            }
        }
        return count;
    }

    private static int countEnabledServerEvents(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("server-events.events");
        if (section == null) {
            return 0;
        }
        int count = 0;
        for (String eventId : SERVER_EVENT_IDS) {
            if (section.getBoolean(eventId + ".enabled", false)) {
                count++;
            }
        }
        return count;
    }

    private static int countTrue(boolean... values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Integer> statusChannelTypes(FileConfiguration config) {
        Map<String, Integer> output = new LinkedHashMap<>();
        for (Map<?, ?> entry : config.getMapList("status-channels.channels")) {
            if (string(entry.get("id")).isBlank()) {
                continue;
            }
            String type = string(entry.get("type")).trim().toLowerCase(Locale.ROOT);
            if (!type.equals("text")) {
                type = "voice";
            }
            output.merge(type, 1, Integer::sum);
        }
        return output;
    }

    private static String chatSyncDirection(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "chat-sync")) {
            return "disabled";
        }
        boolean minecraftToDiscord = config.getBoolean("chat-sync.minecraft-to-discord.enabled", true);
        boolean discordToMinecraft = config.getBoolean("chat-sync.discord-to-minecraft.enabled", false);
        if (minecraftToDiscord && discordToMinecraft) {
            return "two_way";
        }
        if (minecraftToDiscord) {
            return "minecraft_to_discord";
        }
        if (discordToMinecraft) {
            return "discord_to_minecraft";
        }
        return "configured_but_inactive";
    }

    private static String chatWebhookMode(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "chat-sync")
                || !config.getBoolean("chat-sync.minecraft-to-discord.enabled", true)) {
            return "disabled";
        }
        return config.getBoolean("chat-sync.minecraft-to-discord.webhook.enabled", false)
                ? "player_webhook" : "bot_message";
    }

    private static String webhookAvatarMode(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "chat-sync")
                || !config.getBoolean("chat-sync.minecraft-to-discord.webhook.enabled", false)) {
            return "not_used";
        }
        String avatar = string(config.getString("chat-sync.minecraft-to-discord.webhook.avatar-url", ""));
        if (avatar.isBlank()) {
            return "none";
        }
        String normalized = avatar.toLowerCase(Locale.ROOT);
        if (normalized.contains("mc-heads.net") && normalized.contains("%uuid%")) {
            return "default_player_head";
        }
        return "custom_template";
    }

    private static String reverseChatAccountPolicy(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "chat-sync")
                || !config.getBoolean("chat-sync.discord-to-minecraft.enabled", false)) {
            return "disabled";
        }
        boolean linked = config.getBoolean("chat-sync.discord-to-minecraft.allow-linked-users", true);
        boolean unlinked = config.getBoolean("chat-sync.discord-to-minecraft.allow-unlinked-users", true);
        if (linked && unlinked) {
            return "linked_and_unlinked";
        }
        if (linked) {
            return "linked_only";
        }
        if (unlinked) {
            return "unlinked_only";
        }
        return "nobody";
    }

    private static String reverseChatRolePolicy(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "chat-sync")
                || !config.getBoolean("chat-sync.discord-to-minecraft.enabled", false)) {
            return "disabled";
        }
        boolean allowlist = !config.getStringList(
                "chat-sync.discord-to-minecraft.allowed-role-ids").isEmpty();
        boolean blocklist = !config.getStringList(
                "chat-sync.discord-to-minecraft.blocked-role-ids").isEmpty();
        if (allowlist && blocklist) {
            return "allowlist_and_blocklist";
        }
        if (allowlist) {
            return "allowlist";
        }
        if (blocklist) {
            return "blocklist";
        }
        return "no_role_filter";
    }

    private static String linkRequirement(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "link")) {
            return "link_module_disabled";
        }
        return config.getBoolean("link.required.enabled", false) ? "required" : "optional";
    }

    private static String networkMode(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "network")) {
            return "disabled";
        }
        return normalizeEnum(config.getString("network.mode", "local"),
                Set.of("local", "redis"), "other");
    }

    private static String consoleRemoteMode(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "console")) {
            return "module_disabled";
        }
        return normalizeEnum(config.getString("console.remote.mode", "OFF"),
                Set.of("off", "allowlist", "full"), "other");
    }

    private static String luckPermsAuthority(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "luckperms-sync")) {
            return "disabled";
        }
        return normalizeEnum(config.getString("luckperms-sync.initial-authority", "minecraft"),
                Set.of("minecraft", "discord", "merge"), "other");
    }

    private static String serverEventsProfile(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses,
            int enabledEvents
    ) {
        if (!isActive(statuses, "server-events")) {
            return "disabled";
        }
        String batching = config.getBoolean("server-events.batching.enabled", true)
                ? "batched" : "immediate";
        return batching + "_" + bucketCount(enabledEvents);
    }

    private static String statusOfflineNaming(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "status-channels")) {
            return "disabled";
        }
        List<Map<?, ?>> channels = config.getMapList("status-channels.channels");
        int configured = 0;
        int custom = 0;
        String defaultName = string(config.getString(
                "status-channels.default-offline-name", "Server is offline")).trim();
        for (Map<?, ?> entry : channels) {
            if (string(entry.get("id")).isBlank()) {
                continue;
            }
            configured++;
            String offline = string(entry.get("offline-name")).trim();
            if (!offline.isBlank() && !offline.equals(defaultName)) {
                custom++;
            }
        }
        if (configured == 0) {
            return "no_channels";
        }
        if (custom == 0) {
            return "default_for_all";
        }
        if (custom == configured) {
            return "custom_for_all";
        }
        return "mixed";
    }

    private static String voiceChatMode(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "voicechat-sync")) {
            return "disabled";
        }
        boolean minecraftToDiscord = config.getBoolean(
                "voicechat-sync.audio.minecraft-to-discord.enabled", false);
        boolean discordToMinecraft = config.getBoolean(
                "voicechat-sync.audio.discord-to-minecraft.enabled", false);
        if (minecraftToDiscord || discordToMinecraft) {
            return "audio_requested_fail_closed";
        }
        return "discord_proximity_rooms";
    }

    private static String voiceChatGuestPolicy(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "voicechat-sync")) {
            return "disabled";
        }
        boolean linkedGuests = config.getBoolean("voicechat-sync.rooms.allow-linked-guests", true);
        boolean unlinkedGuests = config.getBoolean("voicechat-sync.rooms.allow-unlinked-guests", false);
        if (linkedGuests && unlinkedGuests) {
            return "linked_and_unlinked";
        }
        if (linkedGuests) {
            return "linked_only";
        }
        if (unlinkedGuests) {
            return "unlinked_only";
        }
        return "no_guests";
    }

    private static String deliveryQueueCapacity(
            FileConfiguration config,
            Map<String, ModuleManager.ModuleStatus> statuses
    ) {
        if (!isActive(statuses, "delivery-queue")) {
            return "disabled";
        }
        int maximum = config.getInt("delivery-queue.maximum-pending", 5000);
        if (maximum <= 500) return "up_to_500";
        if (maximum <= 2000) return "501_to_2000";
        if (maximum <= 5000) return "2001_to_5000";
        if (maximum <= 10000) return "5001_to_10000";
        return "over_10000";
    }

    private static String normalizeLanguage(String raw) {
        String value = string(raw).trim().toLowerCase(Locale.ROOT)
                .replace('_', '-');
        if (value.startsWith("en")) return "english";
        if (value.startsWith("de")) return "german";
        return "other";
    }

    private static String normalizeTokenSource(String raw) {
        String value = string(raw).trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "ENV" -> "environment_variable";
            case "SECRETS.YML" -> "secrets_yml";
            default -> "other";
        };
    }

    private static String normalizeEnum(String raw, Set<String> allowed, String fallback) {
        String value = string(raw).trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return allowed.contains(value) ? value : fallback;
    }

    private static String bucketCount(int count) {
        if (count <= 0) return "0";
        if (count == 1) return "1";
        if (count <= 3) return "2_to_3";
        if (count <= 5) return "4_to_5";
        if (count <= 10) return "6_to_10";
        if (count <= 20) return "11_to_20";
        return "21_plus";
    }

    private static Map<String, Integer> immutable(Map<String, Integer> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private record Snapshot(
            Map<String, Integer> configuredModules,
            Map<String, Integer> activeModules,
            Map<String, Integer> dependencies,
            String moduleHealth,
            String configuredModuleCount,
            String language,
            String commandRegistration,
            String tokenSource,
            String chatDirection,
            String chatWebhookMode,
            String webhookAvatarMode,
            String reverseChatAccountPolicy,
            String reverseChatRolePolicy,
            String linkRequirement,
            String networkMode,
            String consoleRemoteMode,
            String luckPermsAuthority,
            String serverEventsProfile,
            String statusChannelCount,
            Map<String, Integer> statusChannelTypes,
            String statusOfflineNaming,
            String voiceChatMode,
            String voiceChatGuestPolicy,
            String pythonState,
            String pythonScriptCount,
            String pythonEventAdapterCount,
            String customCommandCount,
            String workflowCount,
            String serverEventCount,
            String deliveryQueueCapacity,
            String supportFeatureCount,
            String reportsChatHistory
    ) {
        private static Snapshot empty() {
            return new Snapshot(
                    Map.of("none", 1), Map.of("none", 1), Map.of("none", 1),
                    "unknown", "0", "other", "other", "other",
                    "disabled", "disabled", "not_used", "disabled", "disabled",
                    "link_module_disabled", "disabled", "module_disabled", "disabled",
                    "disabled", "0", Map.of("none", 1), "disabled", "disabled",
                    "disabled", "disabled", "0", "0", "0", "0", "0",
                    "disabled", "0", "disabled"
            );
        }
    }
}
