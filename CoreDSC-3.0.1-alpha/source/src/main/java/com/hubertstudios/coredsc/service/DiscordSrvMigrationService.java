package com.hubertstudios.coredsc.service;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.scripting.MiniJson;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;


public final class DiscordSrvMigrationService {
    private static final Pattern SNOWFLAKE = Pattern.compile("[1-9][0-9]{16,19}");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final DateTimeFormatter DIRECTORY_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final CoreDSCPlugin plugin;
    private final AtomicBoolean running = new AtomicBoolean();

    public DiscordSrvMigrationService(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void migrate(CommandSender sender, boolean preview) {
        Objects.requireNonNull(sender, "sender");
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage("§eA DiscordSRV migration is already running.");
            return;
        }
        if (plugin.getStorage() == null || plugin.getStartupState() != CoreDSCPlugin.StartupState.READY) {
            running.set(false);
            sender.sendMessage("§cCoreDSC must be fully ready before a migration can run.");
            return;
        }

        Plugin discordSrv = Bukkit.getPluginManager().getPlugin("DiscordSRV");
        File pluginsDirectory = plugin.getDataFolder().getParentFile();
        File sourceDirectory = discordSrv == null
                ? new File(pluginsDirectory, "DiscordSRV")
                : discordSrv.getDataFolder();
        if (!sourceDirectory.isDirectory()) {
            running.set(false);
            sender.sendMessage("§cDiscordSRV was not found at §f" + sourceDirectory.getPath() + "§c.");
            return;
        }

        sender.sendMessage(preview
                ? "§7Scanning DiscordSRV and preparing a migration preview..."
                : "§7Migrating DiscordSRV settings and linked accounts safely...");
        plugin.getCoreScheduler().runAsync(() -> {
            try {
                MigrationResult result = execute(discordSrv, sourceDirectory, preview);
                plugin.runForSender(sender, () -> sendResult(sender, result));
            } catch (Throwable error) {
                plugin.getLogger().severe("DiscordSRV migration failed: " + rootMessage(error));
                plugin.runForSender(sender, () -> sender.sendMessage("§cDiscordSRV migration failed: " + rootMessage(error)));
            } finally {
                running.set(false);
            }
        });
    }

    private MigrationResult execute(Plugin discordSrv, File sourceDirectory, boolean preview) throws Exception {
        List<String> warnings = new ArrayList<>();
        List<String> preserved = new ArrayList<>();
        LiveDiscordSrv live = inspectLiveDiscordSrv(discordSrv, warnings);
        SourceFiles source = loadSourceFiles(sourceDirectory, warnings);
        MigrationWorkspace workspace = new MigrationWorkspace(warnings, preserved);

        migrateGlobal(source, live, workspace);
        migrateChat(source, live, workspace);
        migrateConsole(source, live, workspace);
        migrateServerEvents(source, workspace);
        migrateLinking(source, live, workspace);
        migrateNicknameAndRoles(source, workspace);
        migrateBanSync(source, workspace);
        migrateStatusChannels(source, live, workspace);
        migrateVoice(source, workspace);

        Map<String, UUID> linkedAccounts = readLinkedAccounts(discordSrv, sourceDirectory, warnings);
        AccountImportResult accounts = previewAccounts(linkedAccounts).get(30L, TimeUnit.SECONDS);

        File migrationDirectory = createMigrationDirectory(preview);
        if (!preview) {
            workspace.saveAll(migrationDirectory);
            try {
                accounts = importAccounts(linkedAccounts).get(30L, TimeUnit.SECONDS);
            } catch (Exception error) {
                workspace.restoreAll(migrationDirectory);
                throw error;
            }
        }
        File report = writeReport(migrationDirectory, sourceDirectory, preview, workspace, accounts, warnings, preserved);

        return new MigrationResult(preview, workspace.changedPathCount(), workspace.changedFileCount(), accounts,
                List.copyOf(warnings), List.copyOf(preserved), migrationDirectory, report);
    }

    private void migrateGlobal(SourceFiles source, LiveDiscordSrv live, MigrationWorkspace workspace) throws Exception {
        String token = text(source.config.getString("BotToken"));
        String currentTokenSource = text(workspace.current("config.yml", "discord.token-source"));
        String currentEnvName = text(workspace.current("config.yml", "discord.token-env-name"));
        String currentSecret = text(workspace.current("secrets.yml", "discord-token"));
        boolean supportedTokenSource = currentTokenSource.equalsIgnoreCase("ENV")
                || currentTokenSource.equalsIgnoreCase("SECRETS.YML")
                || currentTokenSource.isBlank();
        boolean currentTokenConfigured = currentTokenSource.equalsIgnoreCase("ENV")
                ? isUsableToken(System.getenv(currentEnvName))
                : currentTokenSource.equalsIgnoreCase("SECRETS.YML") && isUsableToken(currentSecret);
        if (isUsableToken(token) && !supportedTokenSource) {
            workspace.warn("CoreDSC uses a custom Discord token source ('" + currentTokenSource
                    + "'); the DiscordSRV token was not copied.");
        } else if (isUsableToken(token) && !currentTokenConfigured) {
            workspace.set("secrets.yml", "discord-token", token, true);
            workspace.set("config.yml", "discord.token-source", "SECRETS.YML", false);
        } else if (isUsableToken(token)) {
            workspace.warn("CoreDSC already has a Discord token configured; the DiscordSRV token was not copied.");
        }
        if (isSnowflake(live.guildId)) {
            workspace.set("config.yml", "discord.guild-id", live.guildId, false);
        } else {
            workspace.warn("Discord guild ID could not be determined. Set discord.guild-id manually.");
        }
        workspace.set("config.yml", "debug", !source.config.getStringList("Debug").isEmpty(), false);
    }

    private void migrateChat(SourceFiles source, LiveDiscordSrv live, MigrationWorkspace workspace) throws Exception {
        Map<String, String> channels = stringMap(source.config.getConfigurationSection("Channels"));
        String globalChannel = firstValidChannel(channels, "global");
        long validChannelCount = channels.values().stream().filter(DiscordSrvMigrationService::isSnowflake).count();
        if (validChannelCount > 1L) {
            workspace.warn("DiscordSRV has multiple linked chat channels. CoreDSC currently imports only the global/first valid channel; review modules/chat-sync.yml and server-events.yml.");
        }
        boolean minecraftToDiscord = source.config.getBoolean("DiscordChatChannelMinecraftToDiscord", true);
        boolean discordToMinecraft = source.config.getBoolean("DiscordChatChannelDiscordToMinecraft", true);

        workspace.set("modules/chat-sync.yml", "enabled",
                (minecraftToDiscord || discordToMinecraft) && isSnowflake(globalChannel), false);
        workspace.set("modules/chat-sync.yml", "minecraft-to-discord.channel-id",
                minecraftToDiscord && isSnowflake(globalChannel) ? globalChannel : "", false);
        workspace.set("modules/chat-sync.yml", "discord-to-minecraft.channel-id",
                discordToMinecraft && isSnowflake(globalChannel) ? globalChannel : "", false);

        String mcFormat = source.messages.getString("MinecraftChatToDiscordMessageFormat",
                source.messages.getString("MinecraftChatToDiscordMessageFormatNoPrimaryGroup", ""));
        if (!text(mcFormat).isBlank()) {
            workspace.set("modules/chat-sync.yml", "minecraft-to-discord.format",
                    convertMinecraftToDiscordFormat(mcFormat), false);
        }
        String discordFormat = source.messages.getString("DiscordToMinecraftChatMessageFormatNoRole",
                source.messages.getString("DiscordToMinecraftChatMessageFormat", ""));
        if (!text(discordFormat).isBlank()) {
            String converted = convertDiscordToMinecraftFormat(discordFormat);
            workspace.set("modules/chat-sync.yml", "discord-to-minecraft.format", converted, false);
            workspace.set("modules/chat-sync.yml", "discord-to-minecraft.linked-format", converted, false);
            workspace.set("modules/chat-sync.yml", "discord-to-minecraft.unlinked-format", converted, false);
        }

        boolean webhook = source.config.getBoolean("Experiment_WebhookChatMessageDelivery", false);
        workspace.set("modules/chat-sync.yml", "minecraft-to-discord.webhook.enabled", webhook, false);
        String webhookUsername = source.config.getString("Experiment_WebhookChatMessageUsernameFormat", "");
        if (!text(webhookUsername).isBlank()) {
            workspace.set("modules/chat-sync.yml", "minecraft-to-discord.webhook.username-format",
                    convertMinecraftToDiscordFormat(webhookUsername), false);
        }
        String webhookMessage = source.config.getString("Experiment_WebhookChatMessageFormat", "");
        if (!text(webhookMessage).isBlank()) {
            workspace.set("modules/chat-sync.yml", "minecraft-to-discord.webhook.message-format",
                    convertMinecraftToDiscordFormat(webhookMessage), false);
        }
        String avatar = convertAvatarUrl(source.config.getString("AvatarUrl", ""), workspace);
        if (!avatar.isBlank()) {
            workspace.set("modules/chat-sync.yml", "minecraft-to-discord.webhook.avatar-url", avatar, false);
        }

        boolean requireLinked = source.config.getBoolean("DiscordChatChannelRequireLinkedAccount", false);
        workspace.set("modules/chat-sync.yml", "discord-to-minecraft.allow-linked-users", true, false);
        workspace.set("modules/chat-sync.yml", "discord-to-minecraft.allow-unlinked-users", !requireLinked, false);
        workspace.set("modules/chat-sync.yml", "discord-to-minecraft.ignore-bots",
                source.config.getBoolean("DiscordChatChannelBlockBots", false), false);
        workspace.set("modules/chat-sync.yml", "discord-to-minecraft.ignore-webhooks",
                source.config.getBoolean("DiscordChatChannelBlockWebhooks", true), false);

        Set<String> allowedRoles = new LinkedHashSet<>();
        Set<String> blockedRoles = new LinkedHashSet<>();
        distributeRoleIds(source.config.getStringList("DiscordChatChannelBlockedRolesIds"),
                source.config.getBoolean("DiscordChatChannelBlockedRolesAsWhitelist", false),
                allowedRoles, blockedRoles, live);
        if (!source.config.getStringList("DiscordChatChannelRolesSelection").isEmpty()) {
            workspace.warn("DiscordSRV role-selection display filters are not access-control rules and were not converted into CoreDSC role allow/block lists.");
        }
        if (!allowedRoles.isEmpty()) {
            workspace.set("modules/chat-sync.yml", "discord-to-minecraft.allowed-role-ids",
                    new ArrayList<>(allowedRoles), false);
        }
        if (!blockedRoles.isEmpty()) {
            workspace.set("modules/chat-sync.yml", "discord-to-minecraft.blocked-role-ids",
                    new ArrayList<>(blockedRoles), false);
        }
        if (!source.config.getStringList("DiscordChatChannelBlockedIds").stream()
                .filter(DiscordSrvMigrationService::isSnowflake).toList().isEmpty()) {
            workspace.warn("DiscordSRV blocked user IDs are not migrated because CoreDSC intentionally has no user-ID blacklist setting.");
        }

        Map<String, Object> canned = sectionMap(source.config.getConfigurationSection("DiscordCannedResponses"));
        if (!canned.isEmpty()) {
            List<Map<String, Object>> responses = new ArrayList<>();
            int index = 1;
            for (Map.Entry<String, Object> entry : canned.entrySet()) {
                String trigger = text(entry.getKey());
                String response = text(entry.getValue());
                if (trigger.isBlank() || response.isBlank()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", "discordsrv-" + index++);
                row.put("trigger", trigger);
                row.put("match", "EXACT");
                row.put("response", response);
                row.put("cooldown-seconds", 5);
                row.put("stop-forwarding", false);
                responses.add(row);
            }
            if (!responses.isEmpty()) {
                workspace.set("modules/chat-sync.yml", "canned-responses", responses, false);
            }
        }
    }

    private void migrateConsole(SourceFiles source, LiveDiscordSrv live, MigrationWorkspace workspace) throws Exception {
        String feedChannel = text(source.config.getString("DiscordConsoleChannelId"));
        Map<String, String> chatChannels = stringMap(source.config.getConfigurationSection("Channels"));
        String remoteChannel = firstValidChannel(chatChannels, "global");
        boolean feedEnabled = isSnowflake(feedChannel);
        boolean remoteRequested = source.config.getBoolean("DiscordChatChannelConsoleCommandEnabled", false);
        boolean remoteEnabled = remoteRequested && isSnowflake(remoteChannel);

        if (!feedEnabled && !remoteEnabled) {
            if (remoteRequested) {
                workspace.warn("DiscordSRV remote console commands are enabled, but no valid linked chat channel could be resolved. CoreDSC remote console was kept OFF.");
            }
            return;
        }

        String targetChannel = feedEnabled ? feedChannel : remoteChannel;
        if (feedEnabled && remoteEnabled && !feedChannel.equals(remoteChannel)) {
            workspace.warn("DiscordSRV uses separate channels for console output and chat-channel commands. CoreDSC's console module uses one channel for both, so the feed channel was imported and remote console was kept OFF.");
            remoteEnabled = false;
        }

        workspace.set("modules/console.yml", "enabled", true, false);
        workspace.set("modules/console.yml", "channel-id", targetChannel, false);
        workspace.set("modules/console.yml", "feed.enabled", feedEnabled, false);
        if (isSnowflake(live.guildId)) {
            workspace.set("modules/console.yml", "guild-id", live.guildId, false);
        }
        if (feedEnabled) {
            List<String> levels = source.config.getStringList("DiscordConsoleChannelLevels").stream()
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .map(value -> value.equals("WARN") ? "WARNING" : value)
                    .filter(value -> Set.of("INFO", "WARNING", "SEVERE").contains(value))
                    .distinct().toList();
            if (!levels.isEmpty()) {
                workspace.set("modules/console.yml", "feed.levels", levels, false);
            }
            int seconds = Math.max(1, source.config.getInt("DiscordConsoleChannelLogRefreshRateInSeconds", 5));
            workspace.set("modules/console.yml", "feed.batch-interval-ticks", Math.min(1200, seconds * 20), false);
        }

        if (!remoteEnabled) return;
        if (source.config.getBoolean("DiscordChatChannelConsoleCommandWhitelistActsAsBlacklist", false)) {
            workspace.warn("DiscordSRV console commands use blacklist mode. CoreDSC kept remote console OFF instead of converting this to unsafe FULL access.");
            return;
        }
        workspace.set("modules/console.yml", "remote.mode", "ALLOWLIST", false);
        String prefix = text(source.config.getString("DiscordChatChannelConsoleCommandPrefix", "!c"));
        if (!prefix.isBlank()) {
            workspace.set("modules/console.yml", "remote.prefix", prefix.endsWith(" ") ? prefix : prefix + " ", false);
        }
        List<String> allowlist = source.config.getStringList("DiscordChatChannelConsoleCommandWhitelist").stream()
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        if (!allowlist.isEmpty()) {
            workspace.set("modules/console.yml", "remote.allowlisted-commands", allowlist, false);
        }
        LinkedHashSet<String> configuredRoles = new LinkedHashSet<>(
                source.config.getStringList("DiscordChatChannelConsoleCommandRolesAllowed"));
        List<String> bypassRoles = source.config.getStringList("DiscordChatChannelConsoleCommandWhitelistBypassRoles");
        configuredRoles.addAll(bypassRoles);
        if (!bypassRoles.isEmpty()) {
            workspace.warn("DiscordSRV whitelist-bypass roles were imported as normal authorized roles; CoreDSC does not bypass its command allowlist per role.");
        }
        Set<String> roles = resolveRoles(configuredRoles, live);
        if (!roles.isEmpty()) {
            workspace.set("modules/console.yml", "remote.role-ids", new ArrayList<>(roles), false);
        } else {
            workspace.warn("Remote-console role names could not be resolved. CoreDSC ALLOWLIST mode has no authorized role until configured.");
        }
    }

    private void migrateServerEvents(SourceFiles source, MigrationWorkspace workspace) throws Exception {
        Map<String, String> channels = stringMap(source.config.getConfigurationSection("Channels"));
        String channel = firstValidChannel(channels, "status", "global");
        if (!isSnowflake(channel)) return;
        workspace.set("modules/server-events.yml", "enabled", true, false);
        workspace.set("modules/server-events.yml", "channel-id", channel, false);
        workspace.set("modules/server-events.yml", "events.startup.enabled", hasMessage(source.messages, "ServerStartupMessage"), false);
        workspace.set("modules/server-events.yml", "events.shutdown.enabled", hasMessage(source.messages, "ServerShutdownMessage"), false);
        workspace.set("modules/server-events.yml", "events.join.enabled", hasMessage(source.messages, "MinecraftPlayerJoinMessage"), false);
        workspace.set("modules/server-events.yml", "events.first-join.enabled", hasMessage(source.messages, "MinecraftPlayerFirstJoinMessage"), false);
        workspace.set("modules/server-events.yml", "events.quit.enabled", hasMessage(source.messages, "MinecraftPlayerLeaveMessage"), false);
        workspace.set("modules/server-events.yml", "events.death.enabled", hasMessage(source.messages, "MinecraftPlayerDeathMessage"), false);
        workspace.set("modules/server-events.yml", "events.advancement.enabled", hasMessage(source.messages, "MinecraftPlayerAchievementMessage"), false);
        if (channels.values().stream().filter(DiscordSrvMigrationService::isSnowflake).distinct().count() > 1) {
            workspace.warn("DiscordSRV routes event types to multiple channels. CoreDSC server-events currently uses one channel; 'status' or the main channel was selected.");
        }
    }

    private void migrateLinking(SourceFiles source, LiveDiscordSrv live, MigrationWorkspace workspace) throws Exception {
        workspace.set("modules/link.yml", "enabled", true, false);
        boolean required = source.linking.getBoolean("Require linked account to play.Enabled", false);
        workspace.set("modules/link.yml", "required.enabled", required, false);
        String invite = text(source.config.getString("DiscordInviteLink"));
        if (invite.startsWith("https://") && !invite.contains("changethis")) {
            workspace.set("modules/link.yml", "browser-url", invite, false);
        }
        String role = text(source.config.getString("MinecraftDiscordAccountLinkedRoleNameToAddUserTo"));
        String roleId = resolveRole(role, live);
        if (isSnowflake(roleId)) {
            workspace.set("config.yml", "discord.link-role-id", roleId, false);
        } else if (!role.isBlank()) {
            workspace.warn("The DiscordSRV linked-role name could not be resolved. Set discord.link-role-id manually.");
        }
        if (required) {
            String message = text(source.linking.getString("Require linked account to play.Not linked message"));
            if (!message.isBlank()) {
                workspace.set("modules/link.yml", "required.message",
                        message.replace("{INVITE}", invite).replace("{BOT}", "the Discord bot")
                                .replace("{CODE}", "/link"), false);
            }
        }
    }

    private void migrateNicknameAndRoles(SourceFiles source, MigrationWorkspace workspace) throws Exception {
        boolean nickname = source.synchronization.getBoolean("NicknameSynchronizationEnabled", false);
        workspace.set("modules/nickname-sync.yml", "enabled", nickname, false);
        if (nickname) {
            String format = text(source.synchronization.getString("NicknameSynchronizationFormat", "%displayname%"));
            workspace.set("modules/nickname-sync.yml", "format",
                    format.replace("%displayname%", "%player%").replace("%username%", "%player%"), false);
        }

        Map<String, Object> mappings = sectionMap(source.synchronization.getConfigurationSection("GroupRoleSynchronizationGroupsAndRolesToSync"));
        List<Map<String, Object>> converted = new ArrayList<>();
        boolean minecraftAuthority = source.synchronization.getBoolean("GroupRoleSynchronizationMinecraftIsAuthoritative", true);
        boolean oneWay = source.synchronization.getBoolean("GroupRoleSynchronizationOneWay", false);
        String direction = oneWay
                ? (minecraftAuthority ? "minecraft-to-discord" : "discord-to-minecraft")
                : "bidirectional";
        for (Map.Entry<String, Object> entry : mappings.entrySet()) {
            String group = text(entry.getKey());
            String roleId = text(entry.getValue());
            if (group.isBlank() || !isSnowflake(roleId)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("enabled", true);
            row.put("group", group);
            row.put("role-id", roleId);
            row.put("direction", direction);
            converted.add(row);
        }
        if (!converted.isEmpty()) {
            workspace.set("modules/luckperms-sync.yml", "enabled", true, false);
            workspace.set("modules/luckperms-sync.yml", "initial-authority",
                    minecraftAuthority ? "minecraft" : "discord", false);
            workspace.set("modules/luckperms-sync.yml", "mappings", converted, false);
        }
    }

    private void migrateBanSync(SourceFiles source, MigrationWorkspace workspace) throws Exception {
        boolean discordToMinecraft = source.synchronization.getBoolean("BanSynchronizationDiscordToMinecraft", false);
        boolean minecraftToDiscord = source.synchronization.getBoolean("BanSynchronizationMinecraftToDiscord", false);
        workspace.set("modules/ban-sync.yml", "enabled", discordToMinecraft && minecraftToDiscord, false);
        String reason = text(source.synchronization.getString("BanSynchronizationDiscordToMinecraftReason"));
        if (!reason.isBlank()) {
            workspace.set("modules/ban-sync.yml", "minecraft-ban-reason", reason, false);
        }
        if (discordToMinecraft != minecraftToDiscord) {
            workspace.warn("DiscordSRV enables ban synchronization in only one direction. CoreDSC ban-sync was kept disabled because it is bidirectional; review modules/ban-sync.yml manually.");
        }
    }

    private void migrateStatusChannels(SourceFiles source, LiveDiscordSrv live, MigrationWorkspace workspace) throws Exception {
        List<Map<?, ?>> updater = source.config.getMapList("ChannelUpdater");
        List<Map<String, Object>> channels = new ArrayList<>();
        int minimumInterval = Integer.MAX_VALUE;
        for (Map<?, ?> raw : updater) {
            String id = text(raw.get("ChannelId"));
            String online = text(raw.get("Format"));
            if (!isSnowflake(id) || online.isBlank()) continue;
            String offline = text(raw.get("ShutdownFormat"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            String channelType = live.channelTypes.get(id);
            if (channelType == null) {
                channelType = "voice";
                workspace.warn("Status channel " + id + " type could not be resolved because DiscordSRV/JDA was unavailable; it was imported as voice and must be reviewed.");
            }
            row.put("type", channelType);
            row.put("online-name", convertStatusFormat(online));
            row.put("offline-name", offline.isBlank() ? "Server is offline" : convertStatusFormat(offline));
            channels.add(row);
            int minutes = integer(raw.get("UpdateInterval"), 10);
            minimumInterval = Math.min(minimumInterval, Math.max(5, minutes) * 60);
        }
        if (channels.isEmpty()) return;
        workspace.set("modules/status-channels.yml", "enabled", true, false);
        workspace.set("modules/status-channels.yml", "channels", channels, false);
        workspace.set("modules/status-channels.yml", "update-interval-seconds",
                minimumInterval == Integer.MAX_VALUE ? 600 : Math.max(300, minimumInterval), false);
    }

    private void migrateVoice(SourceFiles source, MigrationWorkspace workspace) throws Exception {
        boolean enabled = source.voice.getBoolean("Voice enabled", false);
        workspace.set("modules/voicechat-sync.yml", "enabled", enabled, false);
        if (!enabled) return;
        String category = text(source.voice.getString("Voice category"));
        String lobby = text(source.voice.getString("Lobby channel"));
        if (isSnowflake(category)) workspace.set("modules/voicechat-sync.yml", "discord.category-id", category, false);
        if (isSnowflake(lobby)) workspace.set("modules/voicechat-sync.yml", "discord.lobby-channel-id", lobby, false);
        workspace.set("modules/voicechat-sync.yml", "proximity.horizontal-distance",
                source.voice.getDouble("Network.Horizontal Strength", 80.0), false);
        workspace.set("modules/voicechat-sync.yml", "proximity.vertical-distance",
                source.voice.getDouble("Network.Vertical Strength", 40.0), false);
        workspace.set("modules/voicechat-sync.yml", "proximity.falloff",
                source.voice.getDouble("Network.Falloff", 5.0), false);
        workspace.set("modules/voicechat-sync.yml", "proximity.update-ticks",
                Math.max(1, source.voice.getInt("Tick speed", 5)), false);
        workspace.set("modules/voicechat-sync.yml", "rooms.channels-visible",
                source.voice.getBoolean("Network.Channels are visible", false), false);
    }

    private CompletableFuture<AccountImportResult> previewAccounts(Map<String, UUID> accounts) {
        return inspectAccountConflicts(accounts, false);
    }

    private CompletableFuture<AccountImportResult> importAccounts(Map<String, UUID> accounts) {
        return inspectAccountConflicts(accounts, true);
    }

    private CompletableFuture<AccountImportResult> inspectAccountConflicts(Map<String, UUID> accounts, boolean insert) {
        if (accounts.isEmpty()) {
            return CompletableFuture.completedFuture(new AccountImportResult(0, 0, 0, 0));
        }
        return plugin.getStorage().transaction(connection -> {
            int imported = 0;
            int identical = 0;
            int conflicts = 0;
            int invalid = 0;
            long now = System.currentTimeMillis();
            try (PreparedStatement byMinecraft = connection.prepareStatement(
                    "SELECT discord_user_id FROM linked_accounts WHERE minecraft_uuid=?");
                 PreparedStatement byDiscord = connection.prepareStatement(
                         "SELECT minecraft_uuid FROM linked_accounts WHERE discord_user_id=?");
                 PreparedStatement create = connection.prepareStatement(
                         "INSERT INTO linked_accounts (minecraft_uuid,discord_user_id,minecraft_name,linked_at) VALUES (?,?,?,?)")) {
                for (Map.Entry<String, UUID> entry : accounts.entrySet()) {
                    String discordId = text(entry.getKey());
                    UUID uuid = entry.getValue();
                    if (!isSnowflake(discordId) || uuid == null) {
                        invalid++;
                        continue;
                    }
                    String uuidText = uuid.toString();
                    String existingDiscord = queryOne(byMinecraft, uuidText);
                    String existingMinecraft = queryOne(byDiscord, discordId);
                    if (discordId.equals(existingDiscord) && uuidText.equals(existingMinecraft)) {
                        identical++;
                        continue;
                    }
                    if (existingDiscord != null || existingMinecraft != null) {
                        conflicts++;
                        continue;
                    }
                    if (insert) {
                        create.setString(1, uuidText);
                        create.setString(2, discordId);
                        create.setString(3, "");
                        create.setLong(4, now);
                        create.executeUpdate();
                    }
                    imported++;
                }
            }
            return new AccountImportResult(imported, identical, conflicts, invalid);
        });
    }

    private static String queryOne(PreparedStatement statement, String value) throws Exception {
        statement.clearParameters();
        statement.setString(1, value);
        try (ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private Map<String, UUID> readLinkedAccounts(Plugin discordSrv, File directory, List<String> warnings) {
        if (discordSrv != null && discordSrv.isEnabled()) {
            try {
                Method managerMethod = discordSrv.getClass().getMethod("getAccountLinkManager");
                Object manager = managerMethod.invoke(discordSrv);
                if (manager != null) {
                    Method accountsMethod = manager.getClass().getMethod("getLinkedAccounts");
                    Object raw = accountsMethod.invoke(manager);
                    Map<String, UUID> copied = accountMap(raw, warnings);
                    if (!copied.isEmpty()) return copied;
                }
            } catch (Throwable error) {
                warnings.add("Could not read linked accounts through the running DiscordSRV API: " + rootMessage(error));
            }
        }

        LinkedHashMap<String, UUID> accounts = new LinkedHashMap<>();
        File aof = new File(directory, "accounts.aof");
        if (aof.isFile()) {
            try {
                for (String line : Files.readAllLines(aof.toPath(), StandardCharsets.UTF_8)) {
                    applyAofLine(accounts, line);
                }
            } catch (Exception error) {
                warnings.add("Could not read DiscordSRV accounts.aof: " + rootMessage(error));
            }
        }
        File json = new File(directory, "linkedaccounts.json");
        if (json.isFile()) {
            try {
                Object parsed = MiniJson.parse(Files.readString(json.toPath(), StandardCharsets.UTF_8));
                Map<String, UUID> legacy = accountMap(parsed, warnings);
                legacy.forEach((discordId, uuid) -> {
                    if (!accounts.containsKey(discordId) && !accounts.containsValue(uuid)) {
                        accounts.put(discordId, uuid);
                    }
                });
            } catch (Exception error) {
                warnings.add("Could not read DiscordSRV linkedaccounts.json: " + rootMessage(error));
            }
        }
        if (accounts.isEmpty()) {
            String jdbc = "";
            try {
                YamlConfiguration config = loadYaml(new File(directory, "config.yml"), false);
                jdbc = text(config.getString("Experiment_JdbcAccountLinkBackend"));
            } catch (Exception ignored) {
                
            }
            if (jdbc.startsWith("jdbc:") && !jdbc.contains("HOST") && discordSrv == null) {
                warnings.add("DiscordSRV appears to use JDBC account linking. Keep DiscordSRV running during migration so CoreDSC can read links through its public API.");
            }
        }
        return accounts;
    }

    private static void applyAofLine(Map<String, UUID> accounts, String rawLine) {
        String line = text(rawLine);
        if (line.isBlank()) return;
        if (line.charAt(0) == '-') {
            String[] tokens = line.substring(1).trim().split("\\s+");
            for (String token : tokens) {
                if (isSnowflake(token)) accounts.remove(token);
                else if (UUID_PATTERN.matcher(token).matches()) {
                    UUID uuid = UUID.fromString(token);
                    accounts.entrySet().removeIf(entry -> entry.getValue().equals(uuid));
                }
            }
            return;
        }
        String[] tokens = line.split("\\s+");
        if (tokens.length < 2 || !isSnowflake(tokens[0]) || !UUID_PATTERN.matcher(tokens[1]).matches()) return;
        UUID uuid = UUID.fromString(tokens[1]);
        accounts.entrySet().removeIf(entry -> entry.getValue().equals(uuid));
        accounts.put(tokens[0], uuid);
    }

    private static Map<String, UUID> accountMap(Object raw, List<String> warnings) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        LinkedHashMap<String, UUID> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String first = text(entry.getKey());
            String second = text(entry.getValue());
            String discordId;
            String uuidText;
            if (isSnowflake(first) && UUID_PATTERN.matcher(second).matches()) {
                discordId = first;
                uuidText = second;
            } else if (isSnowflake(second) && UUID_PATTERN.matcher(first).matches()) {
                discordId = second;
                uuidText = first;
            } else {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(uuidText);
                if (!result.containsKey(discordId) && !result.containsValue(uuid)) result.put(discordId, uuid);
            } catch (IllegalArgumentException ignored) {
                
            }
        }
        return result;
    }

    private LiveDiscordSrv inspectLiveDiscordSrv(Plugin discordSrv, List<String> warnings) {
        if (discordSrv == null || !discordSrv.isEnabled()) return new LiveDiscordSrv("", Map.of(), Map.of());
        String guildId = "";
        Map<String, String> roles = new HashMap<>();
        Map<String, String> channelTypes = new HashMap<>();
        try {
            Object guild = discordSrv.getClass().getMethod("getMainGuild").invoke(discordSrv);
            if (guild != null) {
                guildId = text(guild.getClass().getMethod("getId").invoke(guild));
                Object roleCollection = guild.getClass().getMethod("getRoles").invoke(guild);
                if (roleCollection instanceof Collection<?> collection) {
                    for (Object role : collection) {
                        String name = text(role.getClass().getMethod("getName").invoke(role));
                        String id = text(role.getClass().getMethod("getId").invoke(role));
                        if (!name.isBlank() && isSnowflake(id)) roles.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
                    }
                }
                Object channelCollection = guild.getClass().getMethod("getChannels").invoke(guild);
                if (channelCollection instanceof Collection<?> collection) {
                    for (Object channel : collection) {
                        String id = text(channel.getClass().getMethod("getId").invoke(channel));
                        Object typeObject = channel.getClass().getMethod("getType").invoke(channel);
                        String typeName = text(typeObject).toUpperCase(Locale.ROOT);
                        if (isSnowflake(id)) {
                            channelTypes.put(id, typeName.contains("VOICE") || typeName.contains("STAGE") ? "voice" : "text");
                        }
                    }
                }
            }
        } catch (Throwable error) {
            warnings.add("Could not inspect the running DiscordSRV guild for role/channel resolution: " + rootMessage(error));
        }
        return new LiveDiscordSrv(guildId, Map.copyOf(roles), Map.copyOf(channelTypes));
    }

    private SourceFiles loadSourceFiles(File directory, List<String> warnings) throws IOException, InvalidConfigurationException {
        return new SourceFiles(
                sourceYaml(directory, "config.yml", true, warnings),
                sourceYaml(directory, "messages.yml", false, warnings),
                sourceYaml(directory, "synchronization.yml", false, warnings),
                sourceYaml(directory, "linking.yml", false, warnings),
                sourceYaml(directory, "voice.yml", false, warnings));
    }

    private static YamlConfiguration sourceYaml(File directory, String name, boolean required, List<String> warnings)
            throws IOException, InvalidConfigurationException {
        File file = new File(directory, name);
        if (!file.isFile()) {
            if (required) throw new IOException("Missing DiscordSRV " + file.getPath());
            warnings.add("DiscordSRV " + name + " was not found; related settings were skipped.");
            return new YamlConfiguration();
        }
        return loadYaml(file, true);
    }

    private File createMigrationDirectory(boolean preview) throws IOException {
        File root = new File(plugin.getDataFolder(), "migrations");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Could not create " + root);
        String suffix = preview ? "-preview" : "";
        File directory = new File(root, "discordsrv-" + DIRECTORY_TIME.format(Instant.now()) + suffix);
        int attempt = 1;
        while (directory.exists()) {
            directory = new File(root, "discordsrv-" + DIRECTORY_TIME.format(Instant.now()) + '-' + attempt++ + suffix);
        }
        if (!directory.mkdirs()) throw new IOException("Could not create " + directory);
        return directory;
    }

    private File writeReport(File directory, File sourceDirectory, boolean preview, MigrationWorkspace workspace,
                             AccountImportResult accounts, List<String> warnings, List<String> preserved) throws IOException {
        File report = new File(directory, "report.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(report.toPath(), StandardCharsets.UTF_8)) {
            writer.write("CoreDSC DiscordSRV migration report\n");
            writer.write("Mode: " + (preview ? "PREVIEW" : "APPLIED") + "\n");
            writer.write("Source: " + sourceDirectory.getPath() + "\n");
            writer.write("DiscordSRV source files changed: NO\n");
            writer.write("DiscordSRV disabled or deleted: NO\n\n");
            writer.write("CoreDSC files affected: " + workspace.changedFileCount() + "\n");
            writer.write("CoreDSC paths affected: " + workspace.changedPathCount() + "\n");
            for (TargetFile target : workspace.targets.values().stream()
                    .filter(value -> !value.changedPaths.isEmpty())
                    .sorted(Comparator.comparing(value -> value.relativePath)).toList()) {
                writer.write("- " + target.relativePath + ": " + String.join(", ", target.changedPaths) + "\n");
            }
            writer.write("\nLinked accounts:\n");
            writer.write("- " + (preview ? "would import" : "imported") + ": " + accounts.imported + "\n");
            writer.write("- already identical: " + accounts.identical + "\n");
            writer.write("- conflicts skipped: " + accounts.conflicts + "\n");
            writer.write("- invalid skipped: " + accounts.invalid + "\n");
            if (!preserved.isEmpty()) {
                writer.write("\nExisting CoreDSC custom values preserved:\n");
                for (String item : preserved) writer.write("- " + item + "\n");
            }
            if (!warnings.isEmpty()) {
                writer.write("\nWarnings/manual review:\n");
                for (String warning : warnings) writer.write("- " + warning + "\n");
            }
            writer.write("\nNext steps:\n");
            writer.write("1. Review this report and the backup/ directory.\n");
            writer.write("2. Stop Paper cleanly.\n");
            writer.write("3. Remove or disable DiscordSRV only after confirming the migration.\n");
            writer.write("4. Start Paper and run /coredsc doctor.\n");
            writer.write("5. Do not run DiscordSRV and CoreDSC with the same bot token after restart.\n");
        }
        return report;
    }

    private void sendResult(CommandSender sender, MigrationResult result) {
        sender.sendMessage("§8§m------------- §bDiscordSRV Migration §8§m-------------");
        sender.sendMessage(result.preview ? "§ePreview completed." : "§aMigration completed.");
        sender.sendMessage("§7CoreDSC files: §f" + result.changedFiles + " §7files / §f" + result.changedPaths + " §7settings");
        sender.sendMessage("§7Linked accounts: §f" + result.accounts.imported
                + (result.preview ? " would be imported" : " imported") + ", §f"
                + result.accounts.identical + " §7already identical, §f"
                + result.accounts.conflicts + " §7conflicts skipped");
        if (!result.preserved.isEmpty()) {
            sender.sendMessage("§ePreserved " + result.preserved.size() + " existing CoreDSC custom value(s).");
        }
        if (!result.warnings.isEmpty()) {
            sender.sendMessage("§eManual review warnings: §f" + result.warnings.size());
        }
        sender.sendMessage("§7Report: §f" + result.report.getPath());
        if (!result.preview) {
            sender.sendMessage("§eDiscordSRV was not modified or disabled. Stop Paper and review the migration before removing it.");
            sender.sendMessage("§eDo not restart both plugins with the same Discord bot token.");
        }
    }

    private static void distributeRoleIds(List<String> configured, boolean whitelist, Set<String> allowed,
                                          Set<String> blocked, LiveDiscordSrv live) {
        Set<String> resolved = resolveRoles(configured, live);
        (whitelist ? allowed : blocked).addAll(resolved);
    }

    private static Set<String> resolveRoles(Collection<String> configured, LiveDiscordSrv live) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : configured) {
            String resolved = resolveRole(value, live);
            if (isSnowflake(resolved)) result.add(resolved);
        }
        return result;
    }

    private static String resolveRole(String value, LiveDiscordSrv live) {
        String role = text(value);
        if (isSnowflake(role)) return role;
        return live.rolesByLowerName.getOrDefault(role.toLowerCase(Locale.ROOT), "");
    }

    private static String firstValidChannel(Map<String, String> channels, String... preferred) {
        for (String key : preferred) {
            String value = channels.get(key);
            if (isSnowflake(value)) return value;
        }
        return channels.values().stream().filter(DiscordSrvMigrationService::isSnowflake).findFirst().orElse("");
    }

    private static boolean hasMessage(YamlConfiguration yaml, String path) {
        Object value = yaml.get(path);
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    private static String convertMinecraftToDiscordFormat(String value) {
        return text(value)
                .replace("%username%", "%player%")
                .replace("%displayname%", "%displayname%")
                .replace("%primarygroup%", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static String convertDiscordToMinecraftFormat(String value) {
        return legacyColors(text(value))
                .replace("%name%", "%discord_user%")
                .replace("%username%", "%discord_user%")
                .replace("%userid%", "%discord_id%")
                .replace("%toprolealias%", "%top_role%")
                .replace("%toprolename%", "%top_role%")
                .replace("%toprolecolor%", "")
                .replace("%reply%", "%reply%")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static String legacyColors(String value) {
        return value.replace("<aqua>", "&b").replace("</aqua>", "")
                .replace("<blue>", "&9").replace("</blue>", "")
                .replace("<gray>", "&7").replace("</gray>", "")
                .replace("<dark_gray>", "&8").replace("</dark_gray>", "")
                .replace("<white>", "&f").replace("</white>", "")
                .replace("<reset>", "&r").replaceAll("</?[A-Za-z_]+>", "");
    }

    private static String convertAvatarUrl(String value, MigrationWorkspace workspace) {
        String avatar = text(value);
        if (avatar.isBlank()) return "";
        if (avatar.contains("{texture}")) {
            workspace.warn("DiscordSRV AvatarUrl uses {texture}, which CoreDSC cannot resolve. The CoreDSC default player-head provider was kept.");
            return "";
        }
        if (avatar.contains("{uuid-nodashes}")) {
            workspace.warn("DiscordSRV AvatarUrl requires a UUID without dashes, but CoreDSC exposes %uuid% with dashes. The CoreDSC default player-head provider was kept.");
            return "";
        }
        return avatar.replace("{uuid}", "%uuid%")
                .replace("{username}", "%player%")
                .replace("{size}", "128");
    }

    private static String convertStatusFormat(String value) {
        return text(value)
                .replace("%playercount%", "%online_players%")
                .replace("%playermax%", "%max_players%")
                .replace("%totalplayers%", "%unique_players%")
                .replace("%uptimemins%", "%uptime%")
                .replace("%uptimehours%", "%uptime%")
                .replace("%serverversion%", "%server_version%")
                .replace("%usedmemory%", "%ram_used%")
                .replace("%usedmemorygb%", "%ram_used%")
                .replace("%maxmemory%", "%ram_max%")
                .replace("%maxmemorygb%", "%ram_max%");
    }

    private static Map<String, String> stringMap(org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) return Map.of();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) result.put(key, text(section.get(key)));
        return result;
    }

    private static Map<String, Object> sectionMap(org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) return Map.of();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) result.put(key, section.get(key));
        return result;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(text(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean isUsableToken(String token) {
        String clean = text(token);
        return !clean.isBlank() && !clean.equalsIgnoreCase("BOTTOKEN")
                && !clean.toLowerCase(Locale.ROOT).contains("changeme") && clean.length() >= 20;
    }

    private static boolean isSnowflake(String value) {
        String text = text(value);
        return SNOWFLAKE.matcher(text).matches() && !text.chars().allMatch(character -> character == '0');
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static YamlConfiguration loadYaml(File file, boolean comments)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(comments);
        yaml.load(file);
        return yaml;
    }

    private final class MigrationWorkspace {
        private final List<String> warnings;
        private final List<String> preserved;
        private final Map<String, TargetFile> targets = new LinkedHashMap<>();

        private MigrationWorkspace(List<String> warnings, List<String> preserved) {
            this.warnings = warnings;
            this.preserved = preserved;
        }

        private void warn(String message) { if (!warnings.contains(message)) warnings.add(message); }

        private void set(String relativePath, String path, Object value, boolean sensitive) throws Exception {
            if (value == null) return;
            TargetFile target = targets.computeIfAbsent(relativePath, key -> {
                try { return loadTarget(key); }
                catch (Exception error) { throw new TargetLoadException(error); }
            });
            Object current = target.yaml.get(path);
            Object defaultValue = target.defaults.get(path);
            if (equivalent(current, value)) return;
            if (!replaceable(current, defaultValue)) {
                String label = relativePath + ':' + path;
                if (!preserved.contains(label)) preserved.add(label);
                return;
            }
            target.yaml.set(path, value);
            target.changedPaths.add(path + (sensitive ? " (secret value redacted)" : ""));
        }

        private TargetFile loadTarget(String relativePath) throws Exception {
            File file = new File(plugin.getDataFolder(), relativePath);
            if (!file.isFile()) throw new IOException("Missing CoreDSC target file " + file);
            YamlConfiguration yaml = loadYaml(file, true);
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.options().parseComments(true);
            try (InputStream stream = plugin.getResource(relativePath)) {
                if (stream == null) throw new IOException("Missing bundled CoreDSC resource " + relativePath);
                defaults.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
            return new TargetFile(relativePath, file, yaml, defaults);
        }

        private Object current(String relativePath, String path) throws Exception {
            TargetFile target;
            try {
                target = targets.computeIfAbsent(relativePath, key -> {
                    try { return loadTarget(key); }
                    catch (Exception error) { throw new TargetLoadException(error); }
                });
            } catch (TargetLoadException error) {
                if (error.getCause() instanceof Exception exception) throw exception;
                throw error;
            }
            return target.yaml.get(path);
        }

        private void saveAll(File migrationDirectory) throws IOException {
            List<TargetFile> changed = targets.values().stream()
                    .filter(value -> !value.changedPaths.isEmpty()).toList();
            if (changed.isEmpty()) return;
            File backupRoot = new File(migrationDirectory, "backup");
            File stagedRoot = new File(migrationDirectory, ".staged");
            for (TargetFile target : changed) {
                File staged = new File(stagedRoot, target.relativePath);
                if (staged.toPath().getParent() != null) Files.createDirectories(staged.toPath().getParent());
                Files.writeString(staged.toPath(), target.yaml.saveToString(), StandardCharsets.UTF_8);

                File backup = new File(backupRoot, target.relativePath);
                if (backup.toPath().getParent() != null) Files.createDirectories(backup.toPath().getParent());
                Files.copy(target.file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            }
            try {
                for (TargetFile target : changed) {
                    moveReplace(new File(stagedRoot, target.relativePath).toPath(), target.file.toPath());
                }
            } catch (IOException error) {
                restoreAll(migrationDirectory);
                throw error;
            } finally {
                deleteTree(stagedRoot.toPath());
            }
        }

        private void restoreAll(File migrationDirectory) throws IOException {
            File backupRoot = new File(migrationDirectory, "backup");
            if (!backupRoot.isDirectory()) return;
            IOException failure = null;
            for (TargetFile target : targets.values()) {
                File backup = new File(backupRoot, target.relativePath);
                if (!backup.isFile()) continue;
                try {
                    Files.copy(backup.toPath(), target.file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }

        private int changedPathCount() {
            return targets.values().stream().mapToInt(value -> value.changedPaths.size()).sum();
        }

        private int changedFileCount() {
            return (int) targets.values().stream().filter(value -> !value.changedPaths.isEmpty()).count();
        }

        private boolean replaceable(Object current, Object defaultValue) {
            if (current == null) return true;
            if (equivalent(current, defaultValue)) return true;
            if (current instanceof String string) {
                String clean = string.trim();
                return clean.isBlank() || clean.equals("0") || clean.matches("0{16,20}");
            }
            if (current instanceof Collection<?> collection) return collection.isEmpty();
            if (current instanceof Map<?, ?> map) return map.isEmpty();
            return false;
        }

        private boolean equivalent(Object left, Object right) {
            if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
                return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue()) == 0;
            }
            return Objects.equals(left, right);
        }
    }

    private static void moveReplace(java.nio.file.Path source, java.nio.file.Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(java.nio.file.Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static final class TargetLoadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private TargetLoadException(Throwable cause) { super(cause); }
    }

    private record TargetFile(String relativePath, File file, YamlConfiguration yaml,
                              YamlConfiguration defaults, Set<String> changedPaths) {
        private TargetFile(String relativePath, File file, YamlConfiguration yaml, YamlConfiguration defaults) {
            this(relativePath, file, yaml, defaults, new LinkedHashSet<>());
        }
    }

    private record SourceFiles(YamlConfiguration config, YamlConfiguration messages,
                               YamlConfiguration synchronization, YamlConfiguration linking,
                               YamlConfiguration voice) { }

    private record LiveDiscordSrv(String guildId, Map<String, String> rolesByLowerName,
                                  Map<String, String> channelTypes) { }

    private record AccountImportResult(int imported, int identical, int conflicts, int invalid) { }

    private record MigrationResult(boolean preview, int changedPaths, int changedFiles,
                                   AccountImportResult accounts, List<String> warnings,
                                   List<String> preserved, File directory, File report) { }
}
