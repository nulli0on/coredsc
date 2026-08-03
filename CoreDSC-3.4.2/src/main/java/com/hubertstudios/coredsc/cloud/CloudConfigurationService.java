package com.hubertstudios.coredsc.cloud;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.config.ConfigManager;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.ModuleManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Strict allowlisted configuration and Discord discovery surface for the cloud dashboard. */
public final class CloudConfigurationService {
    private record ModuleControl(String id, String label, String category, String description) { }
    private record Mapping(String id, String label, String file, String path, String type, String moduleId) { }

    private static final List<ModuleControl> MODULES = List.of(
            module("placeholderapi", "PlaceholderAPI", "Foundation", "Rich placeholders in configured messages."),
            module("delivery-queue", "Delivery queue", "Foundation", "Durable Discord delivery with bounded retries."),
            module("network", "Redis network", "Network", "Existing Redis event and link synchronization."),
            module("cloud-control", "Hosted control plane", "Foundation", "Outbound connection to your central CoreDSC dashboard."),
            module("link", "Account linking", "Identity", "Secure Minecraft and Discord account links."),
            module("link-rewards", "Link rewards", "Identity", "Idempotent commands after successful linking."),
            module("nickname-sync", "Nickname sync", "Identity", "Minecraft-to-Discord nickname policy."),
            module("booster-rewards", "Booster rewards", "Community", "Discord boost rewards with duplicate protection."),
            module("ban-sync", "Ban sync", "Moderation", "Linked account ban synchronization."),
            module("luckperms-sync", "Role sync", "Identity", "LuckPerms and Discord role synchronization."),
            module("chat-sync", "Chat sync", "Bridge", "Bidirectional chat with webhook identities."),
            module("console", "Smart Console", "Operations", "Classified incidents and controlled remote console."),
            module("server-events", "Server events", "Bridge", "Join, leave, death, start, and stop embeds."),
            module("economy-market", "Economy terminal", "Community", "Vault balance, inventory, and market commands."),
            module("lore-sync", "Lore sync", "Community", "Cinematic NPC and storyline webhooks."),
            module("competitive", "Competitive", "Community", "ELO ratings and leaderboards."),
            module("custom-commands", "Custom commands", "Automation", "Configured Discord slash commands."),
            module("status-channels", "Status channels", "Operations", "Live Discord status channel names."),
            module("cases", "Cases and appeals", "Moderation", "Unified case history and appeals."),
            module("moderation-bridge", "Moderation bridge", "Moderation", "Audits actions from other moderation plugins."),
            module("tickets", "Tickets", "Support", "Private support threads."),
            module("reports", "Reports", "Support", "Player reports with evidence-ready threads."),
            module("applications", "Applications", "Support", "Structured application workflows."),
            module("workflows", "Workflows", "Automation", "Event-driven actions."),
            module("authme", "AuthMe bridge", "Compatibility", "Account recovery and authentication integration."),
            module("voicechat-sync", "Voice chat sync", "Bridge", "Simple Voice Chat proximity rooms."),
            module("web-editor", "Local editor fallback", "Foundation", "Loopback-only emergency editor."),
            module("python-bot", "Python extensions", "Developer", "Sandboxed optional developer worker.")
    );

    private static final List<Mapping> MAPPINGS = List.of(
            new Mapping("guild", "Primary Discord server", "config.yml", "discord.guild-id", "guild", ""),
            new Mapping("chat-out", "Minecraft → Discord chat", "modules/chat-sync.yml", "minecraft-to-discord.channel-id", "text", "chat-sync"),
            new Mapping("chat-in", "Discord → Minecraft chat", "modules/chat-sync.yml", "discord-to-minecraft.channel-id", "text", "chat-sync"),
            new Mapping("events", "Server events", "modules/server-events.yml", "channel-id", "text", "server-events"),
            new Mapping("console", "Smart Console", "modules/console.yml", "channel-id", "text", "console"),
            new Mapping("tickets", "Tickets parent", "modules/tickets.yml", "parent-channel-id", "text", "tickets"),
            new Mapping("reports", "Reports parent", "modules/reports.yml", "parent-channel-id", "text", "reports"),
            new Mapping("applications", "Applications parent", "modules/applications.yml", "parent-channel-id", "text", "applications"),
            new Mapping("leaderboard", "Competitive leaderboard", "modules/competitive.yml", "discord.channel-id", "text", "competitive"),
            new Mapping("lore", "Lore events", "modules/lore-sync.yml", "default-channel-id", "text", "lore-sync"),
            new Mapping("voice-category", "Voice category", "modules/voicechat-sync.yml", "discord.category-id", "category", "voicechat-sync"),
            new Mapping("voice-lobby", "Voice lobby", "modules/voicechat-sync.yml", "discord.lobby-channel-id", "voice", "voicechat-sync")
    );
    private static final List<String> EMBED_EVENTS = List.of("startup", "shutdown", "join", "quit", "death");
    private static final Set<String> ALLOWED_PATCHES = allowedPatches();

    private final CoreDSCPlugin plugin;
    private final int maximumFileBytes;

    public CloudConfigurationService(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.maximumFileBytes = Math.max(65_536, Math.min(2_097_152,
                plugin.getAppConfig().getInt("cloud-control.maximum-file-bytes", 1_048_576)));
    }

    public CompletableFuture<Map<String, Object>> snapshot() {
        try {
            ConfigManager manager = plugin.getConfigManager();
            Map<String, String> revisions = revisions(manager);
            List<Map<String, Object>> modules = MODULES.stream().map(control -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", control.id());
                item.put("label", control.label());
                item.put("category", control.category());
                item.put("description", control.description());
                item.put("file", moduleFile(control));
                item.put("path", "enabled");
                item.put("enabled", plugin.getAppConfig().getBoolean("modules." + control.id(), false));
                item.put("revision", revisions.getOrDefault(moduleFile(control), ""));
                return Map.copyOf(item);
            }).toList();
            List<Map<String, Object>> mappings = MAPPINGS.stream().map(mapping -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", mapping.id());
                item.put("label", mapping.label());
                item.put("file", mapping.file());
                item.put("path", mapping.path());
                item.put("type", mapping.type());
                item.put("moduleId", mapping.moduleId());
                Object configuredValue = valueFromFile(mapping.file(), mapping.path());
                item.put("value", configuredValue == null ? "" : configuredValue);
                item.put("revision", revisions.getOrDefault(mapping.file(), ""));
                return Map.copyOf(item);
            }).toList();
            List<Map<String, Object>> embeds = EMBED_EVENTS.stream().map(event -> embed(event, revisions)).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("modules", modules);
            result.put("mappings", mappings);
            result.put("embeds", embeds);
            result.put("revisions", revisions);
            result.put("guilds", guilds());
            result.put("discordReady", plugin.getDiscordService() != null && plugin.getDiscordService().isReady());
            result.put("scheduler", plugin.getCoreScheduler().runtime().name());
            result.put("secretsExcluded", true);
            result.put("rawEditorAvailable", false);
            return CompletableFuture.completedFuture(Map.copyOf(result));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Map<String, Object>> validate(Map<String, Object> payload) {
        String file = text(payload.get("file"));
        String content = text(payload.get("content"));
        try {
            ConfigManager.EditorValidation validation = plugin.getConfigManager()
                    .validateEditorDocument(file, content, maximumFileBytes);
            return CompletableFuture.completedFuture(Map.of(
                    "valid", true,
                    "warnings", validation.warnings().stream().map(CloudConfigurationService::issue).toList()));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Map<String, Object>> patch(Map<String, Object> payload) {
        try {
            List<ConfigManager.EditorPatch> patches = structuredPatches(payload);
            ConfigManager.EditorBatchSaveResult saved = plugin.getConfigManager()
                    .saveEditorPatch(List.copyOf(patches), maximumFileBytes);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("revisions", saved.revisions());
            result.put("backupPath", saved.backupPath());
            result.put("changedFiles", saved.changedFiles());
            result.put("warnings", saved.warnings().stream().map(CloudConfigurationService::issue).toList());
            result.put("reloadRequired", !saved.changedFiles().isEmpty());
            plugin.recordFeatureUse("web_editor_structured_save");
            return CompletableFuture.completedFuture(Map.copyOf(result));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Map<String, Object>> validateStructured(Map<String, Object> payload) {
        try {
            ConfigManager.EditorPatchValidation validation = plugin.getConfigManager()
                    .validateEditorPatch(structuredPatches(payload), maximumFileBytes);
            return CompletableFuture.completedFuture(Map.of(
                    "valid", true,
                    "files", validation.files(),
                    "revisions", validation.revisions(),
                    "warnings", validation.warnings().stream()
                            .map(CloudConfigurationService::issue).toList()));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Map<String, Object>> applyNetworkTemplate(Map<String, Object> payload) {
        try {
            ConfigManager.EditorBatchSaveResult saved = plugin.getConfigManager()
                    .saveEditorTemplate(templatePatches(payload), maximumFileBytes);
            plugin.recordFeatureUse("web_editor_structured_save");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("revisions", saved.revisions());
            result.put("backupPath", saved.backupPath());
            result.put("changedFiles", saved.changedFiles());
            result.put("warnings", saved.warnings().stream()
                    .map(CloudConfigurationService::issue).toList());
            result.put("reloadRequired", !saved.changedFiles().isEmpty());
            result.put("applyScheduled", !saved.changedFiles().isEmpty());
            if (!saved.changedFiles().isEmpty()) apply();
            return CompletableFuture.completedFuture(Map.copyOf(result));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Map<String, Object>> validateNetworkTemplate(Map<String, Object> payload) {
        try {
            ConfigManager.EditorPatchValidation validation = plugin.getConfigManager()
                    .validateEditorTemplate(templatePatches(payload), maximumFileBytes);
            return CompletableFuture.completedFuture(Map.of(
                    "valid", true,
                    "files", validation.files(),
                    "revisions", validation.revisions(),
                    "warnings", validation.warnings().stream()
                            .map(CloudConfigurationService::issue).toList()));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Map<String, Object>> apply() {
        // Reloading disables and reconstructs this module. A short delayed
        // hand-off lets the WebSocket acknowledge the request before its own
        // connection is intentionally replaced by the new configuration.
        plugin.getCoreScheduler().runGlobalLater(() -> {
            CoreDSCPlugin.ReloadResult result = plugin.reloadConfiguration();
            if (!result.success()) {
                plugin.getLogger().severe("[Cloud] Delayed configuration apply failed: " + result.message());
            } else {
                plugin.getLogger().info("[Cloud] Hosted configuration was applied locally: " + result.message());
            }
        }, 20L);
        return CompletableFuture.completedFuture(Map.of(
                "accepted", true,
                "reconnectExpected", true,
                "message", "Configuration apply scheduled locally; the cloud agent will reconnect automatically."));
    }

    public CompletableFuture<Map<String, Object>> channels(Map<String, Object> payload) {
        try {
            String requested = text(payload.get("guildId"));
            long guildId = requested.isBlank() ? plugin.getDiscordService().getConfiguredGuildId()
                    : parseSnowflake(requested, "guildId");
            Guild guild = requireGuild(guildId);
            List<Map<String, Object>> values = new ArrayList<>();
            guild.getCategories().forEach(category -> values.add(channel(
                    category.getId(), category.getName(), "CATEGORY", "", true, category.getPosition())));
            guild.getTextChannels().forEach(channel -> values.add(channel(
                    channel.getId(), channel.getName(), "TEXT", parent(channel),
                    channel.canTalk(), channel.getPosition())));
            guild.getVoiceChannels().forEach(channel -> values.add(channel(
                    channel.getId(), channel.getName(), "VOICE", parent(channel),
                    guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL), channel.getPosition())));
            values.sort(Comparator.comparing((Map<String, Object> item) -> text(item.get("category")),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(item -> text(item.get("type")))
                    .thenComparingInt(item -> ((Number) item.get("position")).intValue()));
            return CompletableFuture.completedFuture(Map.of(
                    "guild", Map.of("id", guild.getId(), "name", guild.getName()),
                    "channels", List.copyOf(values)));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Map<String, Object>> doctor() {
        List<Map<String, Object>> checks = new ArrayList<>();
        DiscordBotService discord = plugin.getDiscordService();
        checks.add(check("scheduler", "Folia scheduler", plugin.getCoreScheduler().runtime().name(),
                plugin.getCoreScheduler().runtime().name().equals("FOLIA")));
        checks.add(check("storage", "SQLite single-write funnel",
                plugin.getStorage().getState() + " · queue " + plugin.getStorage().getQueuedOperationCount()
                        + "/" + plugin.getStorage().getQueueCapacity(),
                plugin.getStorage().getState().name().equals("READY")
                        && plugin.getStorage().getRejectedOperationCount() == 0));
        checks.add(check("discord", "Discord gateway",
                discord == null ? "Not initialised" : discord.getState().name(),
                discord != null && discord.isReady()));
        JDA jda = discord == null ? null : discord.getJda();
        Guild guild = jda == null ? null : jda.getGuildById(discord.getConfiguredGuildId());
        if (guild != null) {
            var self = guild.getSelfMember();
            for (Permission permission : List.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                    Permission.MESSAGE_EMBED_LINKS, Permission.MANAGE_WEBHOOKS,
                    Permission.MANAGE_CHANNEL, Permission.VIEW_AUDIT_LOGS)) {
                boolean allowed = self.hasPermission(permission);
                checks.add(check("discord.permission." + permission.name().toLowerCase(Locale.ROOT),
                        "Discord permission · " + permission.getName(),
                        allowed ? "Allowed" : "Missing — update the bot role in Discord",
                        allowed));
            }
        }
        ModuleManager modules = plugin.getModuleManager();
        if (modules != null) modules.getStatuses().forEach((id, status) -> checks.add(check(
                "module." + id, "Module · " + id, status.detail(),
                status.state() != ModuleManager.ModuleState.FAILED)));
        // PluginManager is Bukkit-owned state. The cloud agent executes on its
        // own I/O executor, so optional-plugin discovery must cross the global
        // scheduler boundary rather than reading Bukkit state directly.
        return plugin.callSync(() -> Map.of(
                        "vault", plugin.getServer().getPluginManager().isPluginEnabled("Vault"),
                        "luckperms", plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")))
                .thenApply(integrations -> {
                    boolean vault = integrations.get("vault");
                    boolean luckPerms = integrations.get("luckperms");
                    checks.add(check("vault", "Vault",
                            vault ? "Installed" : "Not installed (economy terminal unavailable)", vault));
                    checks.add(check("luckperms", "LuckPerms",
                            luckPerms ? "Installed" : "Not installed (role sync unavailable)", luckPerms));
                    return Map.of("checks", List.copyOf(checks));
                });
    }

    public CompletableFuture<Map<String, Object>> createRecommendedChannels(Map<String, Object> payload) {
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null || !discord.isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Discord bot is not ready"));
        }
        Guild guild;
        try {
            guild = requireGuild(discord.getConfiguredGuildId());
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_CHANNEL)) {
            return CompletableFuture.failedFuture(new SecurityException(
                    "The bot needs Manage Channels before CoreDSC can create the recommended structure"));
        }
        List<String> requested = payload.get("names") instanceof List<?> list
                ? list.stream().map(CloudConfigurationService::text)
                        .filter(name -> name.matches("[a-z0-9-]{2,40}"))
                        .distinct().limit(12).toList()
                : List.of();
        List<String> names = requested.isEmpty() ? List.of(
                "minecraft-chat", "server-events", "staff-console", "reports", "tickets", "leaderboard")
                : requested;
        Category existing = guild.getCategoriesByName("CoreDSC", true).stream().findFirst().orElse(null);
        CompletableFuture<Category> category = existing == null
                ? guild.createCategory("CoreDSC").reason("CoreDSC guided setup").submit()
                : CompletableFuture.completedFuture(existing);
        return category.thenCompose(parent -> createMissingChannels(parent, names))
                .thenApply(created -> Map.of("created", created, "category", "CoreDSC",
                        "message", "Channel creation completed. Review channel mappings before enabling modules."));
    }

    private CompletableFuture<List<Map<String, Object>>> createMissingChannels(
            Category category,
            List<String> names
    ) {
        List<CompletableFuture<Map<String, Object>>> operations = new ArrayList<>();
        for (String name : names) {
            TextChannel existing = category.getTextChannels().stream()
                    .filter(channel -> channel.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (existing != null) {
                operations.add(CompletableFuture.completedFuture(Map.of(
                        "id", existing.getId(), "name", existing.getName(), "created", false)));
            } else {
                operations.add(category.createTextChannel(name).reason("CoreDSC guided setup")
                        .submit().thenApply(channel -> Map.of(
                                "id", channel.getId(), "name", channel.getName(), "created", true)));
            }
        }
        return CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> operations.stream().map(CompletableFuture::join).toList());
    }

    private Map<String, String> revisions(ConfigManager manager) throws IOException {
        Set<String> files = new LinkedHashSet<>();
        files.add("config.yml");
        MODULES.forEach(module -> files.add(moduleFile(module)));
        MAPPINGS.forEach(mapping -> files.add(mapping.file()));
        files.add("modules/server-events.yml");
        Map<String, String> revisions = new LinkedHashMap<>();
        for (String file : files) {
            if (!manager.editableFilePaths().contains(file)) continue;
            revisions.put(file, manager.readEditorDocument(file, maximumFileBytes).revision());
        }
        return Map.copyOf(revisions);
    }

    private Map<String, Object> embed(String event, Map<String, String> revisions) {
        String root = "server-events.events." + event;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", event);
        value.put("label", switch (event) {
            case "startup" -> "Server start";
            case "shutdown" -> "Server stop";
            case "join" -> "Player join";
            case "quit" -> "Player leave";
            case "death" -> "Player death";
            default -> event;
        });
        value.put("enabled", plugin.getAppConfig().getBoolean(root + ".enabled", true));
        value.put("title", plugin.getAppConfig().getString(root + ".embed.title", ""));
        value.put("description", plugin.getAppConfig().getString(root + ".embed.description", ""));
        value.put("color", plugin.getAppConfig().getString(root + ".embed.color", "#5865F2"));
        value.put("thumbnail", plugin.getAppConfig().getString(root + ".embed.thumbnail-url", ""));
        value.put("image", plugin.getAppConfig().getString(root + ".embed.image-url", ""));
        value.put("footer", plugin.getAppConfig().getString(root + ".embed.footer", ""));
        value.put("revision", revisions.getOrDefault("modules/server-events.yml", ""));
        return Map.copyOf(value);
    }

    private List<Map<String, Object>> guilds() {
        DiscordBotService service = plugin.getDiscordService();
        JDA jda = service == null ? null : service.getJda();
        if (jda == null) return List.of();
        long configured = service.getConfiguredGuildId();
        return jda.getGuilds().stream()
                .sorted(Comparator.comparing(Guild::getName, String.CASE_INSENSITIVE_ORDER))
                .map(guild -> Map.<String, Object>of(
                        "id", guild.getId(),
                        "name", guild.getName(),
                        "configured", guild.getIdLong() == configured,
                        "textChannels", guild.getTextChannels().size(),
                        "voiceChannels", guild.getVoiceChannels().size()))
                .toList();
    }

    private Guild requireGuild(long guildId) {
        DiscordBotService service = plugin.getDiscordService();
        JDA jda = service == null ? null : service.getJda();
        if (service == null || !service.isReady() || jda == null) {
            throw new IllegalStateException("Discord is not ready; check /coredsc doctor");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) throw new IllegalArgumentException("The bot cannot see Discord guild " + guildId);
        return guild;
    }

    private Object valueFromFile(String file, String path) {
        String runtime = file.equals("config.yml") ? path
                : file.substring("modules/".length(), file.length() - ".yml".length()) + "." + path;
        return plugin.getAppConfig().get(runtime);
    }

    private static Map<String, Object> issue(ConfigManager.ConfigIssue issue) {
        return Map.of(
                "kind", issue.kind().name(),
                "file", issue.file(),
                "path", issue.path(),
                "suggestion", issue.suggestion(),
                "message", issue.message());
    }

    private static Map<String, Object> check(String id, String label, String detail, boolean healthy) {
        return Map.of("id", id, "label", label, "detail", detail, "healthy", healthy);
    }

    private static Map<String, Object> channel(
            String id, String name, String type, String category, boolean canSend, int position
    ) {
        return Map.of("id", id, "name", name, "type", type,
                "category", category, "canSend", canSend, "position", position);
    }

    private static String parent(TextChannel channel) {
        return channel.getParentCategory() == null ? "" : channel.getParentCategory().getName();
    }

    private static String parent(VoiceChannel channel) {
        return channel.getParentCategory() == null ? "" : channel.getParentCategory().getName();
    }

    private static ModuleControl module(String id, String label, String category, String description) {
        return new ModuleControl(id, label, category, description);
    }

    private static String moduleFile(ModuleControl module) {
        return module.id().equals("python-bot") ? "bot/config.yml" : "modules/" + module.id() + ".yml";
    }

    private static Set<String> allowedPatches() {
        Set<String> values = new LinkedHashSet<>();
        MODULES.forEach(module -> values.add(key(moduleFile(module), "enabled")));
        MAPPINGS.forEach(mapping -> values.add(key(mapping.file(), mapping.path())));
        for (String event : EMBED_EVENTS) {
            String root = "events." + event;
            values.add(key("modules/server-events.yml", root + ".enabled"));
            for (String setting : List.of("title", "description", "color", "thumbnail-url", "image-url", "footer")) {
                values.add(key("modules/server-events.yml", root + ".embed." + setting));
            }
        }
        return Set.copyOf(values);
    }

    private static List<ConfigManager.EditorPatch> structuredPatches(Map<String, Object> payload) {
        return parsePatches(payload, true);
    }

    private static List<ConfigManager.EditorPatch> templatePatches(Map<String, Object> payload) {
        return parsePatches(payload, false);
    }

    private static List<ConfigManager.EditorPatch> parsePatches(
            Map<String, Object> payload,
            boolean requireRevision
    ) {
        Object rawChanges = payload.get("changes");
        if (!(rawChanges instanceof List<?> changes) || changes.isEmpty() || changes.size() > 100) {
            throw new IllegalArgumentException("changes must contain 1-100 structured edits");
        }
        List<ConfigManager.EditorPatch> patches = new ArrayList<>();
        for (Object raw : changes) {
            if (!(raw instanceof Map<?, ?> change)) {
                throw new IllegalArgumentException("Every configuration change must be an object");
            }
            String file = text(change.get("file"));
            String path = text(change.get("path"));
            if (!ALLOWED_PATCHES.contains(key(file, path))) {
                throw new SecurityException("The hosted editor is not allowed to change " + file + " -> " + path);
            }
            if (!change.containsKey("value")) {
                throw new IllegalArgumentException("Configuration change is missing value");
            }
            String revision = text(change.containsKey("revision")
                    ? change.get("revision") : change.get("expectedRevision"));
            if (requireRevision && revision.isBlank()) {
                throw new IllegalArgumentException("Configuration change is missing its expected revision");
            }
            patches.add(new ConfigManager.EditorPatch(file, path, change.get("value"), revision));
        }
        return List.copyOf(patches);
    }

    private static String key(String file, String path) {
        return file + '\0' + path;
    }

    private static long parseSnowflake(String value, String field) {
        if (!value.matches("[0-9]{15,22}")) throw new IllegalArgumentException(field + " must be a Discord ID");
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(field + " must be a Discord ID", error);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
