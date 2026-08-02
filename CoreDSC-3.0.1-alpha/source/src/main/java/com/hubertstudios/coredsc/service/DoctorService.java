package com.hubertstudios.coredsc.service;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.config.ConfigManager;
import com.hubertstudios.coredsc.module.ModuleManager;
import com.hubertstudios.coredsc.module.impl.DeliveryQueueModule;
import com.hubertstudios.coredsc.module.impl.NetworkModule;
import com.hubertstudios.coredsc.module.impl.TicketModule;
import com.hubertstudios.coredsc.module.impl.VoiceChatSyncModule;
import com.hubertstudios.coredsc.module.impl.PythonBotModule;
import com.hubertstudios.coredsc.storage.PendingLinkCodeRepository;
import com.hubertstudios.coredsc.storage.RewardClaimRepository;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


public final class DoctorService {
    private final CoreDSCPlugin plugin;
    public DoctorService(CoreDSCPlugin plugin) { this.plugin = plugin; }

    public void diagnose(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        lines.add("§8§m--------------- §bCoreDSC Doctor §8§m---------------");
        lines.add(line("Core", plugin.getStartupState() == CoreDSCPlugin.StartupState.READY,
                plugin.getStartupState().name()));
        var storage = plugin.getStorage();
        lines.add(line("Storage", storage != null && storage.getState().name().equals("READY"),
                storage == null ? "not initialised" : storage.getState().name()));
        if (storage != null) {
            boolean queueHealthy = storage.getRejectedOperationCount() == 0L
                    && storage.getQueuedOperationCount() < storage.getQueueCapacity() * 9 / 10;
            lines.add(line("SQLite funnel", queueHealthy,
                    storage.getQueuedOperationCount() + "/" + storage.getQueueCapacity()
                            + " queued, peak=" + storage.getQueueHighWaterMark()
                            + ", rejected=" + storage.getRejectedOperationCount()));
        }
        DiscordBotService discord = plugin.getDiscordService();
        lines.add(line("Discord", discord != null && discord.isReady(), discord == null ? "not initialised" : discord.getState().name()));
        ConfigManager configManager = plugin.getConfigManager();
        ConfigManager.MigrationSummary migration = configManager == null
                ? null : configManager.getMigrationSummary();
        lines.add(line("Configuration schema", configManager != null,
                configManager == null ? "not initialised" : "v" + ConfigManager.CURRENT_CONFIG_VERSION));
        if (migration != null && migration.migrated()) {
            lines.add(infoLine("Configuration migration", migration.detail()
                    + (migration.backupDirectory().isBlank() ? "" : "; backup=" + migration.backupDirectory())));
        }
        if (configManager != null && !configManager.getConfigIssues().isEmpty()) {
            lines.add(warnLine("Configuration warnings",
                    configManager.getConfigIssues().size() + " unknown/deprecated item(s)"));
            int shown = 0;
            for (ConfigManager.ConfigIssue issue : configManager.getConfigIssues()) {
                if (shown++ >= 10) {
                    lines.add("§e  ... " + (configManager.getConfigIssues().size() - 10)
                            + " more warning(s) in the console log");
                    break;
                }
                lines.add("§e  " + issue.file() + ": §f" + issue.message());
            }
        }
        ModuleManager modules = plugin.getModuleManager();
        lines.add(line("Modules", modules != null && !modules.hasFailedModules(), modules == null ? "not initialised" : modules.enabledModuleSummary()));
        if (modules != null && modules.hasFailedModules()) lines.add("§c  Failed: " + modules.failedModuleSummary());
        if (modules != null) {
            for (String moduleId : modules.getStatuses().keySet()) {
                addModuleLine(lines, modules, moduleId);
            }
        }
        lines.add(line("LuckPerms", !plugin.getAppConfig().getBoolean("modules.luckperms-sync", false)
                        || plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms"),
                plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms") ? "available" : "not installed"));
        lines.add(line("PlaceholderAPI", !plugin.getAppConfig().getBoolean("modules.placeholderapi", true)
                        || plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"),
                plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") ? "available" : "optional/not installed"));
        inspectSecurityConfiguration(lines);
        inspectDiscord(lines, discord);
        NetworkModule network = modules == null ? null : modules.getModule(NetworkModule.class);
        if (network != null) lines.add(line("Network", network.connected(), network.statusDetail()));
        PythonBotModule python = modules == null ? null : modules.getModule(PythonBotModule.class);
        if (python != null) {
            if (python.workerState() == com.hubertstudios.coredsc.scripting.PythonWorker.State.READY) {
                lines.add(line("Python", true, python.workerDetail()));
            } else {
                lines.add(warnLine("Python", python.workerState() + " - " + python.workerDetail()
                        + "; optional module only"));
            }
            lines.add(line("Python event bridges",
                    python.activeEventBridges() == python.configuredEventBridges(),
                    python.activeEventBridges() + "/" + python.configuredEventBridges() + " active"));
        }
        DeliveryQueueModule queue = modules == null ? null : modules.getModule(DeliveryQueueModule.class);
        java.util.concurrent.CompletableFuture<long[]> queueFuture = queue == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : queue.counts().thenApply(counts -> new long[] {counts[0], counts[1]});
        java.util.concurrent.CompletableFuture<long[]> rewardFuture = plugin.getStorage() == null
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : new RewardClaimRepository(plugin.getStorage()).reviewCounts();
        queueFuture.handle((counts, error) -> new AsyncResult(counts, error))
                .thenCombine(rewardFuture.handle((counts, error) -> new AsyncResult(counts, error)), DoctorResults::new)
                .whenComplete((results, combinationError) -> plugin.runForSender(sender, () -> {
                    if (combinationError != null) {
                        lines.add("§c✘ Asynchronous doctor checks: " + rootMessage(combinationError));
                    } else {
                        if (results.queue().error() != null) {
                            lines.add("§c✘ Delivery queue: " + rootMessage(results.queue().error()));
                        } else if (results.queue().counts() != null) {
                            long[] counts = results.queue().counts();
                            lines.add(line("Delivery queue", counts[1] == 0,
                                    counts[0] + " pending, " + counts[1] + " failed"));
                        }
                        if (results.rewards().error() != null) {
                            lines.add("§c✘ Reward claims: " + rootMessage(results.rewards().error()));
                        } else if (results.rewards().counts() != null) {
                            long[] counts = results.rewards().counts();
                            lines.add(line("Reward claims", counts[0] == 0 && counts[1] == 0,
                                    counts[0] + " manual-review, " + counts[1] + " interrupted in-flight"));
                        }
                    }
                    send(sender, lines);
                }));
    }


    public void setup(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("check")) {
            sender.sendMessage("§8§m--------------- §bCoreDSC Setup §8§m---------------");
            sender.sendMessage("§7Set IDs: §f/coredsc setup set <guild|link-role|chat|events|console|voice-category|voice-lobby|booster-role|tickets|reports|applications|appeals> <id>");
            sender.sendMessage("§7Toggle module: §f/coredsc setup <enable|disable> <module>");
            sender.sendMessage("§7Then run: §f/coredsc reload");
            diagnose(sender);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("set")) {
            if (args.length < 4) {
                sender.sendMessage("§eUsage: /coredsc setup set <target> <discord-id>");
                return;
            }
            String target = args[2].toLowerCase(Locale.ROOT);
            String value = args[3].trim();
            if (!TextUtil.isPositiveSnowflake(value)) {
                sender.sendMessage("§cThe value must be a positive Discord ID.");
                return;
            }
            Map<String, List<String>> paths = Map.ofEntries(
                    Map.entry("guild", List.of("discord.guild-id", "luckperms-sync.guild-id",
                            "nickname-sync.guild-id", "booster-rewards.guild-id", "ban-sync.guild-id", "console.guild-id")),
                    Map.entry("link-role", List.of("discord.link-role-id")),
                    Map.entry("chat", List.of("chat-sync.minecraft-to-discord.channel-id", "chat-sync.discord-to-minecraft.channel-id")),
                    Map.entry("events", List.of("server-events.channel-id")),
                    Map.entry("console", List.of("console.channel-id")),
                    Map.entry("voice-category", List.of("voicechat-sync.discord.category-id")),
                    Map.entry("voice-lobby", List.of("voicechat-sync.discord.lobby-channel-id")),
                    Map.entry("booster-role", List.of("booster-rewards.booster-role-id")),
                    Map.entry("tickets", List.of("tickets.parent-channel-id")),
                    Map.entry("reports", List.of("reports.parent-channel-id")),
                    Map.entry("applications", List.of("applications.parent-channel-id")),
                    Map.entry("appeals", List.of("cases.appeals.parent-channel-id"))
            );
            List<String> selected = paths.get(target);
            if (selected == null) {
                sender.sendMessage("§cUnknown setup target.");
                return;
            }
            try {
                for (String path : selected) plugin.getConfigManager().setValue(path, value);
                sender.sendMessage("§aUpdated " + target + ". Run §f/coredsc reload§a to apply it.");
            } catch (Exception error) {
                sender.sendMessage("§cCould not update configuration: " + rootMessage(error));
            }
            return;
        }
        if (action.equals("enable") || action.equals("disable")) {
            if (args.length < 3) {
                sender.sendMessage("§eUsage: /coredsc setup <enable|disable> <module>");
                return;
            }
            String module = args[2].toLowerCase(Locale.ROOT);
            Set<String> allowed = Set.of("placeholderapi", "delivery-queue", "network", "link",
                    "link-rewards", "nickname-sync", "booster-rewards", "ban-sync", "luckperms-sync",
                    "chat-sync", "console", "server-events", "custom-commands", "status-channels",
                    "cases", "moderation-bridge", "tickets", "reports", "applications", "workflows", "authme",
                    "voicechat-sync", "web-editor", "python-bot");
            if (!allowed.contains(module)) {
                sender.sendMessage("§cUnknown or unsupported setup module.");
                return;
            }
            boolean enabled = action.equals("enable");
            try {
                plugin.getConfigManager().setModuleEnabled(module, enabled);
                sender.sendMessage((enabled ? "§aEnabled " : "§eDisabled ") + module
                        + " in its modular config. Run §f/coredsc reload§r.");
            } catch (Exception error) {
                sender.sendMessage("§cCould not update module config: " + rootMessage(error));
            }
            return;
        }
        sender.sendMessage("§eUsage: /coredsc setup [check|set|enable|disable]");
    }

    public void test(CommandSender sender, String target) {
        String normalized = target == null ? "" : target.toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "chat" -> testChannel(sender, plugin.getAppConfig().getString("chat-sync.minecraft-to-discord.channel-id", ""), "CoreDSC doctor chat test.");
            case "events" -> testChannel(sender, plugin.getAppConfig().getString("server-events.channel-id", ""), "CoreDSC doctor server-events test.");
            case "tickets" -> {
                TicketModule tickets = plugin.getModuleManager() == null ? null : plugin.getModuleManager().getModule(TicketModule.class);
                sender.sendMessage(tickets == null ? "§cTicket module is disabled or failed." : "§aTicket module is enabled. Use /ticket create test <message> with a linked test account.");
            }
            case "console" -> testChannel(sender, plugin.getAppConfig().getString("console.channel-id", ""), "CoreDSC doctor console-channel test. This is not a remote command.");
            case "rolesync" -> sender.sendMessage(plugin.getModuleManager() != null && plugin.getModuleManager().isModuleEnabled("luckperms-sync")
                    ? "§aRoleSync is enabled. Change a mapped test group/role and inspect the console for permission errors."
                    : "§cLuckPerms RoleSync is disabled or failed.");
            case "link" -> sender.sendMessage(moduleState("link"));
            case "link-rewards" -> sender.sendMessage(moduleState("link-rewards"));
            case "nickname" -> sender.sendMessage(moduleState("nickname-sync"));
            case "booster" -> sender.sendMessage(moduleState("booster-rewards"));
            case "bans" -> sender.sendMessage(moduleState("ban-sync"));
            case "voice" -> {
                VoiceChatSyncModule voice = plugin.getModuleManager() == null ? null
                        : plugin.getModuleManager().getModule(VoiceChatSyncModule.class);
                if (voice == null) {
                    sender.sendMessage("§cVoiceChat Sync is disabled or failed.");
                } else {
                    voice.reconnect();
                    sender.sendMessage("§aVoice reconnect requested. Current state: §f" + voice.statusDetail());
                }
            }
            default -> sender.sendMessage("§eUsage: /coredsc doctor test <chat|events|console|tickets|rolesync|link|link-rewards|nickname|booster|bans|voice>");
        }
    }

    public void fix(CommandSender sender) {
        DeliveryQueueModule queue = plugin.getModuleManager() == null ? null
                : plugin.getModuleManager().getModule(DeliveryQueueModule.class);
        if (queue != null) queue.retryFailed().whenComplete((count, error) -> plugin.runForSender(sender, () -> sender.sendMessage(
                error == null ? "§aRequeued " + count + " failed Discord message(s)." : "§cQueue repair failed: " + rootMessage(error))));
        if (plugin.getStorage() != null) new PendingLinkCodeRepository(plugin.getStorage())
                .deleteExpired(System.currentTimeMillis()).whenComplete((count, error) -> plugin.runForSender(sender, () -> sender.sendMessage(
                        error == null ? "§aRemoved " + count + " expired link code(s)." : "§cLink cleanup failed: " + rootMessage(error))));
        if (queue == null && plugin.getStorage() == null) sender.sendMessage("§7No safe automatic fixes are available.");
    }

    private static void addModuleLine(List<String> lines, ModuleManager modules, String id) {
        ModuleManager.ModuleStatus status = modules.getStatuses().get(id);
        if (status == null) return;
        boolean ok = status.state() != ModuleManager.ModuleState.FAILED;
        lines.add(line("Module " + id, ok, status.state() + " - " + status.detail()));
        if (status.state() == ModuleManager.ModuleState.ENABLED) {
            ModuleManager.OperationalStatus operational = modules.getOperationalStatus(id);
            if (operational != null) {
                lines.add(infoLine("  Activity", operationalDetail(operational)));
            }
        }
    }

    private static String operationalDetail(ModuleManager.OperationalStatus status) {
        String success = status.lastSuccessAt() == null
                ? "no successful actions yet"
                : "last success " + relativeTime(status.lastSuccessAt());
        String failure = status.lastFailureAt() == null
                ? "last failure never"
                : "last failure " + relativeTime(status.lastFailureAt()) + " (" + status.lastFailure() + ")";
        return success + ", successful actions=" + status.successfulActions() + ", " + failure;
    }

    private static String relativeTime(Instant instant) {
        long seconds = Math.max(0L, Duration.between(instant, Instant.now()).getSeconds());
        if (seconds < 60L) return seconds + "s ago";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + "m ago";
        long hours = minutes / 60L;
        if (hours < 48L) return hours + "h ago";
        return (hours / 24L) + "d ago";
    }

    private String moduleState(String id) {
        ModuleManager modules = plugin.getModuleManager();
        if (modules == null) return "§cCoreDSC modules are not ready.";
        ModuleManager.ModuleStatus status = modules.getStatuses().get(id);
        if (status == null) return "§cNo status is available for " + id + ".";
        return status.state() == ModuleManager.ModuleState.ENABLED
                ? "§a" + id + " is enabled: " + status.detail()
                : "§c" + id + " is " + status.state() + ": " + status.detail();
    }

    private void inspectSecurityConfiguration(List<String> lines) {
        var config = plugin.getAppConfig();
        if (config.getBoolean("modules.link", true) && config.getBoolean("link.required.enabled", false)) {
            List<String> allowed = config.getStringList("link.required.allowed-commands").stream()
                    .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                    .toList();
            lines.add(line("Required-link command escape", allowed.contains("link"),
                    allowed.contains("link") ? "/link remains available" : "allowed-commands must include link"));
            String bypass = config.getString("link.required.bypass-permission", "");
            lines.add(line("Required-link bypass", bypass != null && !bypass.isBlank(),
                    bypass == null || bypass.isBlank() ? "permission is blank" : bypass));
        }
        if (config.getBoolean("modules.link-rewards", false)) {
            lines.add(line("First-link reward commands", !config.getStringList("link-rewards.commands").isEmpty(),
                    config.getStringList("link-rewards.commands").size() + " configured"));
        }
        if (config.getBoolean("modules.booster-rewards", false)) {
            lines.add(line("Booster reward commands", !config.getStringList("booster-rewards.commands").isEmpty(),
                    config.getStringList("booster-rewards.commands").size() + " configured"));
        }
        if (config.getBoolean("modules.console", false)) {
            String mode = config.getString("console.remote.mode", "OFF");
            mode = mode == null ? "OFF" : mode.trim().toUpperCase(Locale.ROOT);
            boolean rolesPresent = mode.equals("OFF") || !config.getStringList("console.remote.role-ids").isEmpty();
            lines.add(line("Remote-console roles", rolesPresent,
                    mode.equals("OFF") ? "remote execution is OFF" : config.getStringList("console.remote.role-ids").size() + " allowed role(s)"));
            if (!mode.equals("OFF")) {
                String consoleChannel = config.getString("console.channel-id", "");
                String chatChannel = config.getString("chat-sync.discord-to-minecraft.channel-id", "");
                boolean separated = consoleChannel == null || chatChannel == null
                        || consoleChannel.isBlank() || !consoleChannel.equals(chatChannel);
                lines.add(line("Remote-console channel separation", separated, separated
                        ? "console commands are not exposed through the Discord-to-Minecraft bridge"
                        : "shared channel detected; CoreDSC suppresses matching command-prefix messages"));
            }
            if (mode.equals("FULL")) {
                boolean confirmed = config.getBoolean("console.remote.confirm-full-access", false);
                lines.add(line("Remote-console FULL acknowledgement", confirmed,
                        confirmed ? "explicitly confirmed" : "confirm-full-access is false"));
                lines.add(line("Remote-console deny rules", !config.getStringList("console.remote.deny-patterns").isEmpty(),
                        config.getStringList("console.remote.deny-patterns").size() + " configured"));
            }
        }
        if (config.getBoolean("modules.voicechat-sync", false)) {
            boolean simpleVoiceChat = plugin.getVoiceChatBridge().isRegistered();
            lines.add(line("Simple Voice Chat bridge", simpleVoiceChat,
                    plugin.getVoiceChatBridge().statusDetail()));
            VoiceChatSyncModule voice = plugin.getModuleManager() == null ? null
                    : plugin.getModuleManager().getModule(VoiceChatSyncModule.class);
            lines.add(line("Voice chat sync", voice != null && voice.isDiscordVoiceConnected(),
                    voice == null ? "module disabled or failed" : voice.statusDetail()));
        }
    }

    private void inspectDiscord(List<String> lines, DiscordBotService discord) {
        if (discord == null) {
            lines.add(line("Guild resolution", false, "Discord service is not initialised"));
            return;
        }
        long configuredGuildId = discord.getConfiguredGuildId();
        String configured = configuredGuildId <= 0L ? "not configured" : Long.toString(configuredGuildId);
        lines.add(infoLine("Configured guild ID", configured));
        boolean globalCommands = "global".equalsIgnoreCase(
                plugin.getAppConfig().getString("discord.command-registration", "guild"));
        boolean guildHealthy = discord.getGuildResolutionState()
                == DiscordBotService.GuildResolutionState.READY
                || (globalCommands && configuredGuildId <= 0L);
        lines.add(line("Guild resolution", guildHealthy, discord.getGuildResolutionState()
                + " - " + discord.getGuildResolutionDetail()));
        boolean commandsHealthy = discord.getCommandRegistrationState()
                == DiscordBotService.CommandRegistrationState.READY
                || discord.getCommandRegistrationState()
                == DiscordBotService.CommandRegistrationState.DISABLED;
        lines.add(line("Discord command registration", commandsHealthy,
                discord.getCommandRegistrationState() + " - " + discord.getCommandRegistrationDetail()));

        JDA jda = discord.getJda();
        if (jda == null) return;
        Guild guild = configuredGuildId <= 0L ? null : jda.getGuildById(configuredGuildId);
        if (guild == null) return;
        Role selfRole = guild.getSelfMember().getRoles().stream().max(java.util.Comparator.comparingInt(Role::getPosition)).orElse(null);
        boolean needsManageRoles = requiresManageRoles();
        if (needsManageRoles) {
            lines.add(line("Manage Roles", guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES),
                    guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES) ? "granted" : "missing"));
        } else {
            lines.add(infoLine("Manage Roles", "not required by the current configuration"));
        }
        if (plugin.getAppConfig().getBoolean("modules.nickname-sync", false)) {
            lines.add(line("Manage Nicknames", guild.getSelfMember().hasPermission(Permission.NICKNAME_MANAGE),
                    guild.getSelfMember().hasPermission(Permission.NICKNAME_MANAGE) ? "granted" : "missing"));
        }
        if (plugin.getAppConfig().getBoolean("modules.ban-sync", false)) {
            lines.add(line("Ban Members", guild.getSelfMember().hasPermission(Permission.BAN_MEMBERS),
                    guild.getSelfMember().hasPermission(Permission.BAN_MEMBERS) ? "granted" : "missing"));
        }
        if (plugin.getAppConfig().getBoolean("chat-sync.minecraft-to-discord.webhook.enabled", false)) {
            lines.add(line("Manage Webhooks", guild.getSelfMember().hasPermission(Permission.MANAGE_WEBHOOKS),
                    guild.getSelfMember().hasPermission(Permission.MANAGE_WEBHOOKS) ? "granted" : "missing"));
        }
        String[] paths = {"chat-sync.minecraft-to-discord.channel-id", "chat-sync.discord-to-minecraft.channel-id",
                "server-events.channel-id", "console.channel-id", "tickets.parent-channel-id",
                "reports.parent-channel-id", "applications.parent-channel-id"};
        for (String path : paths) {
            if (!plugin.getAppConfig().contains(path)) continue;
            String id = plugin.getAppConfig().getString(path, "");
            if (id.isBlank()) continue;
            TextChannel channel = jda.getTextChannelById(id);
            lines.add(line(path, channel != null, channel == null ? "channel not visible" : channel.getName()));
        }
        if (plugin.getAppConfig().getBoolean("modules.voicechat-sync", false)) {
            long voiceGuildId = TextUtil.parsePositiveLong(plugin.getAppConfig().get(
                    "voicechat-sync.discord.guild-id"));
            Guild voiceGuild = voiceGuildId > 0L ? jda.getGuildById(voiceGuildId) : guild;
            String categoryValue = plugin.getAppConfig().getString(
                    "voicechat-sync.discord.category-id", "");
            String lobbyValue = plugin.getAppConfig().getString(
                    "voicechat-sync.discord.lobby-channel-id", "");
            Category category = voiceGuild != null && TextUtil.isPositiveSnowflake(categoryValue)
                    ? voiceGuild.getCategoryById(categoryValue) : null;
            VoiceChannel lobby = voiceGuild != null && TextUtil.isPositiveSnowflake(lobbyValue)
                    ? voiceGuild.getVoiceChannelById(lobbyValue) : null;
            lines.add(line("Voice category", category != null,
                    category == null ? "not configured or not visible" : category.getName()));
            lines.add(line("Voice lobby", lobby != null,
                    lobby == null ? "not configured or not visible" : lobby.getName()));
            if (category != null && lobby != null) {
                var self = voiceGuild.getSelfMember();
                boolean categoryPermissions = self.hasPermission(category, Permission.VIEW_CHANNEL)
                        && self.hasPermission(category, Permission.MANAGE_CHANNEL)
                        && self.hasPermission(category, Permission.MANAGE_PERMISSIONS)
                        && self.hasPermission(category, Permission.VOICE_MOVE_OTHERS);
                boolean lobbyPermissions = self.hasPermission(lobby, Permission.VIEW_CHANNEL)
                        && self.hasPermission(lobby, Permission.VOICE_MOVE_OTHERS);
                lines.add(line("Proximity-room permissions",
                        categoryPermissions && lobbyPermissions,
                        "category=" + categoryPermissions + ", lobby=" + lobbyPermissions));
            }
            boolean crossPlatformAudioRequested = plugin.getAppConfig().getBoolean(
                    "voicechat-sync.audio.minecraft-to-discord.enabled", false)
                    || plugin.getAppConfig().getBoolean(
                    "voicechat-sync.audio.discord-to-minecraft.enabled", false);
            lines.add(line("Cross-platform voice audio",
                    !crossPlatformAudioRequested,
                    crossPlatformAudioRequested
                            ? "disabled: no verified DAVE provider/native runtime is bundled"
                            : "not requested; Discord-only proximity rooms are supported"));
        }
        if (selfRole != null) lines.add("§7  Highest bot role: §f" + selfRole.getName() + " §8(position " + selfRole.getPosition() + ")");
    }

    private boolean requiresManageRoles() {
        var config = plugin.getAppConfig();
        boolean linkRole = config.getBoolean("modules.link", true)
                && TextUtil.isPositiveSnowflake(config.getString("discord.link-role-id", ""));
        boolean luckPermsSync = config.getBoolean("modules.luckperms-sync", false);
        boolean applicationRole = config.getBoolean("modules.applications", false)
                && TextUtil.isPositiveSnowflake(config.getString(
                "applications.accept-actions.discord-role-id", ""));
        boolean workflowRoleAction = config.getBoolean("modules.workflows", false)
                && hasEnabledRoleAction(config.getMapList("workflows.definitions"));
        boolean customCommandRoleAction = config.getBoolean("modules.custom-commands", false)
                && hasEnabledRoleAction(config.getMapList("custom-commands.commands"));
        return linkRole || luckPermsSync || applicationRole
                || workflowRoleAction || customCommandRoleAction;
    }

    private static boolean hasEnabledRoleAction(List<Map<?, ?>> definitions) {
        for (Map<?, ?> definition : definitions) {
            if (Boolean.FALSE.equals(definition.get("enabled"))) {
                continue;
            }
            Object rawActions = definition.get("actions");
            if (!(rawActions instanceof List<?> actions)) {
                continue;
            }
            for (Object rawAction : actions) {
                if (!(rawAction instanceof Map<?, ?> action)) {
                    continue;
                }
                Object rawType = action.get("type");
                String type = rawType == null ? "" : String.valueOf(rawType);
                if (type.equalsIgnoreCase("ADD_DISCORD_ROLE")
                        || type.equalsIgnoreCase("REMOVE_DISCORD_ROLE")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void testChannel(CommandSender sender, String channelId, String message) {
        if (!TextUtil.isPositiveSnowflake(channelId)) { sender.sendMessage("§cThe channel is not configured."); return; }
        DeliveryQueueModule queue = plugin.getModuleManager() == null ? null : plugin.getModuleManager().getModule(DeliveryQueueModule.class);
        if (queue != null) {
            queue.enqueue(channelId, message, 100, "doctor:" + channelId + ':' + System.currentTimeMillis())
                    .whenComplete((id, error) -> plugin.runForSender(sender, () -> sender.sendMessage(error == null
                            ? "§aTest message queued as #" + id + "." : "§cTest failed: " + rootMessage(error))));
            return;
        }
        JDA jda = plugin.getDiscordService() == null ? null : plugin.getDiscordService().getJda();
        TextChannel channel = jda == null ? null : jda.getTextChannelById(channelId);
        if (channel == null) { sender.sendMessage("§cChannel is not visible to the bot."); return; }
        channel.sendMessage(message).queue(ignored -> plugin.runForSender(sender, () -> sender.sendMessage("§aTest message sent.")),
                error -> plugin.runForSender(sender, () -> sender.sendMessage("§cTest failed: " + rootMessage(error))));
    }

    private record AsyncResult(long[] counts, Throwable error) { }
    private record DoctorResults(AsyncResult queue, AsyncResult rewards) { }

    private static String line(String label, boolean ok, String detail) {
        return (ok ? "§a✔ " : "§c✘ ") + "§7" + label + ": §f" + detail;
    }
    private static String warnLine(String label, String detail) {
        return "§e⚠ §7" + label + ": §f" + detail;
    }
    private static String infoLine(String label, String detail) {
        return "§b• §7" + label + ": §f" + detail;
    }
    private static void send(CommandSender sender, List<String> lines) { lines.forEach(sender::sendMessage); }
    private static String rootMessage(Throwable t) { Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage(); }
}
