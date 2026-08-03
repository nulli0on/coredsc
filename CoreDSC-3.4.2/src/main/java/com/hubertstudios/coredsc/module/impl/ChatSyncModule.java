package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository.LinkedAccount;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bidirectional Minecraft/Discord chat bridge with account-aware reverse chat
 * and optional per-player Discord webhooks.
 */
public final class ChatSyncModule implements CoreModule {
    private static final Pattern EVERYONE_MENTION = Pattern.compile("@everyone", Pattern.CASE_INSENSITIVE);
    private static final Pattern HERE_MENTION = Pattern.compile("@here", Pattern.CASE_INSENSITIVE);

    private final CoreDSCPlugin plugin;
    private final AtomicLong lastSendWarning = new AtomicLong();
    private final Map<String, Long> cannedCooldowns = new ConcurrentHashMap<>();
    private Listener bukkitListener;
    private ListenerAdapter discordListener;
    private LinkedAccountRepository links;
    private volatile CompletableFuture<Webhook> webhookFuture;
    private volatile boolean active;
    private volatile boolean operationsPaused;
    private volatile long coreWebhookId;
    private volatile String policySummary = "not configured";

    public ChatSyncModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "chat-sync";
    }

    @Override
    public void enable() {
        active = true;
        DiscordBotService discord = requireDiscordService();
        if (plugin.getStorage() == null) {
            throw new IllegalStateException("SQLite storage is not initialised");
        }
        links = new LinkedAccountRepository(plugin.getStorage());
        FileConfiguration config = plugin.getAppConfig();

        configureMinecraftToDiscord(discord, config);
        configureDiscordToMinecraft(discord, config);
    }

    private void configureMinecraftToDiscord(DiscordBotService discord, FileConfiguration config) {
        String channelId = value(config, "chat-sync.minecraft-to-discord.channel-id", "");
        String botFormat = value(config, "chat-sync.minecraft-to-discord.format", "%player%: %message%");
        validateSnowflake("chat-sync.minecraft-to-discord.channel-id", channelId);

        boolean webhookEnabled = config.getBoolean("chat-sync.minecraft-to-discord.webhook.enabled", false);
        boolean webhookAutoCreate = config.getBoolean("chat-sync.minecraft-to-discord.webhook.auto-create", true);
        boolean webhookFallbackToBot = config.getBoolean(
                "chat-sync.minecraft-to-discord.webhook.fallback-to-bot", true);
        String webhookName = value(config, "chat-sync.minecraft-to-discord.webhook.name", "CoreDSC Chat");
        String webhookUsername = value(config,
                "chat-sync.minecraft-to-discord.webhook.username-format", "%displayname%");
        String webhookAvatar = value(config, "chat-sync.minecraft-to-discord.webhook.avatar-url",
                "https://mc-heads.net/avatar/%uuid%/128");
        String webhookMessageFormat = value(config,
                "chat-sync.minecraft-to-discord.webhook.message-format", "%message%");
        if (webhookEnabled
                && (webhookName.length() < 2 || webhookName.length() > 100 || containsControl(webhookName))) {
            throw new IllegalArgumentException(
                    "chat-sync.minecraft-to-discord.webhook.name must contain 2-100 printable characters");
        }

        if (channelId.isBlank()) {
            return;
        }

        bukkitListener = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onPlayerChat(AsyncChatEvent event) {
                if (operationsPaused) return;
                Player player = event.getPlayer();
                String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
                String playerName = player.getName();
                UUID playerId = player.getUniqueId();

                plugin.callForPlayer(playerId, currentPlayer -> {
                    String displayName = PlainTextComponentSerializer.plainText()
                            .serialize(currentPlayer.displayName());
                    String world = currentPlayer.getWorld().getName();
                    String server = plugin.getServer().getName();
                    String bodyTemplate = webhookEnabled ? webhookMessageFormat : botFormat;
                    String body = replaceMinecraftPlaceholders(bodyTemplate, playerName, displayName,
                            playerId, world, server, rawMessage);
                    body = plugin.getPlaceholderService().apply(currentPlayer, body);
                    body = truncateDiscord(sanitizeMassMentions(body));

                    String username = sanitizeWebhookUsername(replaceMinecraftPlaceholders(
                            webhookUsername, playerName, displayName, playerId, world, server, rawMessage));
                    String avatar = replaceMinecraftPlaceholders(
                            webhookAvatar, playerName, displayName, playerId, world, server, rawMessage);
                    return new MinecraftDiscordMessage(body, username, avatar);
                }).thenApply(rendered -> rendered.orElse(null)).whenComplete((rendered, renderError) -> {
                    if (renderError != null) {
                        warnRateLimited("Could not render Minecraft chat: " + rootMessage(renderError));
                        return;
                    }
                    if (!active || operationsPaused || rendered == null || rendered.body().isBlank()) {
                        return;
                    }
                    JDA jda = discord.getJda();
                    if (!discord.isReady() || jda == null) {
                        return;
                    }
                    TextChannel channel = jda.getTextChannelById(channelId);
                    if (channel == null) {
                        warnRateLimited("Minecraft-to-Discord channel " + channelId
                                + " is not visible to the bot.");
                        return;
                    }
                    if (!webhookEnabled) {
                        sendBotMessage(channel, rendered.body());
                        return;
                    }
                    sendWebhook(channel, webhookName, webhookAutoCreate, webhookFallbackToBot,
                            rendered.body(), rendered.username(), rendered.avatarUrl());
                });
            }
        };
        plugin.getServer().getPluginManager().registerEvents(bukkitListener, plugin);
    }

    private void configureDiscordToMinecraft(DiscordBotService discord, FileConfiguration config) {
        String channelId = value(config, "chat-sync.discord-to-minecraft.channel-id", "");
        String legacyFormat = value(config, "chat-sync.discord-to-minecraft.format",
                "&9[Discord] &f%user%: %message%");
        String linkedFormat = value(config, "chat-sync.discord-to-minecraft.linked-format", legacyFormat);
        String unlinkedFormat = value(config, "chat-sync.discord-to-minecraft.unlinked-format", legacyFormat);
        validateSnowflake("chat-sync.discord-to-minecraft.channel-id", channelId);

        boolean allowLinked = config.getBoolean(
                "chat-sync.discord-to-minecraft.allow-linked-users", true);
        boolean allowUnlinked = config.getBoolean(
                "chat-sync.discord-to-minecraft.allow-unlinked-users", true);
        boolean ignoreBots = config.getBoolean("chat-sync.discord-to-minecraft.ignore-bots", true);
        boolean ignoreWebhooks = config.getBoolean("chat-sync.discord-to-minecraft.ignore-webhooks", true);
        boolean blockMassMentions = config.getBoolean(
                "chat-sync.discord-to-minecraft.block-everyone-mentions", true);
        boolean includeAttachments = config.getBoolean(
                "chat-sync.discord-to-minecraft.attachments.enabled", true);
        int maximumAttachments = (int) clamp(config.getLong(
                "chat-sync.discord-to-minecraft.attachments.maximum", 3L), 0L, 10L);
        String attachmentFormat = value(config,
                "chat-sync.discord-to-minecraft.attachments.format", " &7[%name%: %url%]");
        boolean includeReplies = config.getBoolean(
                "chat-sync.discord-to-minecraft.replies.enabled", true);
        String replyFormat = value(config,
                "chat-sync.discord-to-minecraft.replies.format", " &8(reply to %reply_user%: %reply_message%)");
        boolean sendDeniedNotice = config.getBoolean(
                "chat-sync.discord-to-minecraft.denied-notice.enabled", false);
        String deniedNotice = value(config,
                "chat-sync.discord-to-minecraft.denied-notice.message",
                "Your message was not forwarded to Minecraft by this server's chat policy.");
        Set<Long> allowedRoles = snowflakeSet(config.getStringList(
                "chat-sync.discord-to-minecraft.allowed-role-ids"));
        Set<Long> blockedRoles = snowflakeSet(config.getStringList(
                "chat-sync.discord-to-minecraft.blocked-role-ids"));

        policySummary = "Discord→MC linked=" + allowLinked + ", unlinked=" + allowUnlinked
                + (allowedRoles.isEmpty() ? "" : ", role allowlist=" + allowedRoles.size())
                + (blockedRoles.isEmpty() ? "" : ", blocked roles=" + blockedRoles.size());

        String remoteConsoleMode = value(config, "console.remote.mode", "OFF");
        String remoteConsoleChannel = value(config, "console.channel-id", "");
        String remoteConsolePrefix = config.getString("console.remote.prefix", "!console ");
        if (remoteConsolePrefix == null) {
            remoteConsolePrefix = "!console ";
        }
        boolean sharedRemoteConsoleChannel = config.getBoolean("modules.console", false)
                && !"OFF".equalsIgnoreCase(remoteConsoleMode)
                && channelId.equals(remoteConsoleChannel);
        String protectedRemoteConsolePrefix = remoteConsolePrefix;
        List<CannedResponse> cannedResponses = parseCannedResponses(config);

        if (channelId.isBlank()) {
            if (bukkitListener == null) {
                plugin.getLogger().info("[ChatSync] No channel IDs are configured; the module is idle.");
            }
            return;
        }

        discordListener = new ListenerAdapter() {
            @Override
            public void onMessageReceived(MessageReceivedEvent event) {
                if (!active || operationsPaused || !Objects.equals(event.getChannel().getId(), channelId)) {
                    return;
                }
                Message message = event.getMessage();
                if (event.getAuthor().getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
                    return;
                }
                if (message.isWebhookMessage()) {
                    boolean ownWebhook = coreWebhookId > 0L
                            && event.getAuthor().getIdLong() == coreWebhookId;
                    if (ignoreWebhooks || ownWebhook) {
                        return;
                    }
                }
                if (ignoreBots && event.getAuthor().isBot()) {
                    return;
                }
                if (sharedRemoteConsoleChannel
                        && message.getContentRaw().startsWith(protectedRemoteConsolePrefix)) {
                    return;
                }
                if (!rolePolicyAllows(event.getMember(), allowedRoles, blockedRoles)) {
                    sendDeniedNotice(event, sendDeniedNotice, deniedNotice);
                    return;
                }
                if (applyCannedResponse(event, cannedResponses)) {
                    return;
                }

                DiscordMessageSnapshot snapshot = snapshotDiscordMessage(
                        event, includeAttachments, maximumAttachments, attachmentFormat,
                        includeReplies, replyFormat, blockMassMentions);
                if (snapshot.message().isBlank() && snapshot.attachments().isBlank()) {
                    return;
                }

                LinkedAccountRepository repository = links;
                if (repository == null) {
                    warnRateLimited("Account repository is unavailable; Discord message was not forwarded.");
                    return;
                }
                repository.findByDiscordUserId(snapshot.discordId())
                        .whenComplete((linkedAccount, lookupError) -> {
                            if (!active) {
                                return;
                            }
                            if (lookupError != null) {
                                warnRateLimited("Could not resolve Discord account link: "
                                        + rootMessage(lookupError));
                                return;
                            }
                            boolean linked = linkedAccount.isPresent();
                            if ((linked && !allowLinked) || (!linked && !allowUnlinked)) {
                                sendDeniedNotice(event, sendDeniedNotice, deniedNotice);
                                return;
                            }
                            broadcastDiscordMessage(snapshot, linkedAccount,
                                    linked ? linkedFormat : unlinkedFormat);
                        });
            }
        };
        discord.addEventListener(discordListener);
    }

    private DiscordMessageSnapshot snapshotDiscordMessage(
            MessageReceivedEvent event,
            boolean includeAttachments,
            int maximumAttachments,
            String attachmentFormat,
            boolean includeReplies,
            String replyFormat,
            boolean blockMassMentions
    ) {
        Message message = event.getMessage();
        String content = sanitizeMinecraftUserText(message.getContentDisplay());
        if (blockMassMentions) {
            content = sanitizeMassMentions(content);
        }
        String displayName = event.getMember() == null
                ? event.getAuthor().getEffectiveName()
                : event.getMember().getEffectiveName();
        displayName = sanitizeMinecraftUserText(displayName);

        String attachments = includeAttachments
                ? renderAttachments(message, maximumAttachments, attachmentFormat)
                : "";
        String reply = includeReplies ? renderReply(message, replyFormat) : "";
        String topRole = "";
        if (event.getMember() != null && !event.getMember().getRoles().isEmpty()) {
            topRole = sanitizeMinecraftUserText(event.getMember().getRoles().get(0).getName());
        }
        return new DiscordMessageSnapshot(
                event.getAuthor().getId(),
                sanitizeMinecraftUserText(event.getAuthor().getName()),
                displayName,
                topRole,
                content,
                attachments,
                reply
        );
    }

    private void broadcastDiscordMessage(
            DiscordMessageSnapshot snapshot,
            Optional<LinkedAccount> linkedAccount,
            String format
    ) {
        if (!active) return;
        LinkedAccount account = linkedAccount.orElse(null);
        UUID placeholderPlayerId = null;
        if (account != null) {
            try {
                String storedMinecraftUuid = account.minecraftUuid();
                if (storedMinecraftUuid == null || storedMinecraftUuid.isBlank()) {
                    throw new IllegalArgumentException("blank UUID");
                }
                placeholderPlayerId = UUID.fromString(storedMinecraftUuid);
            } catch (RuntimeException error) {
                warnRateLimited("Linked account contains an invalid Minecraft UUID for Discord user "
                        + snapshot.discordId() + "; placeholders were skipped.");
            }
        }

        CompletableFuture<String> rendering;
        if (placeholderPlayerId == null) {
            rendering = plugin.callSync(() -> renderDiscordMessage(snapshot, account, format, null));
        } else {
            UUID playerId = placeholderPlayerId;
            rendering = plugin.callForPlayer(playerId,
                            player -> renderDiscordMessage(snapshot, account, format, player))
                    .thenCompose(rendered -> rendered.isPresent()
                            ? CompletableFuture.completedFuture(rendered.get())
                            : plugin.callSync(() -> renderDiscordMessage(
                                    snapshot, account, format, Bukkit.getOfflinePlayer(playerId))));
        }
        rendering.thenCompose(output -> active
                        ? broadcastFoliaSafe(output)
                        : CompletableFuture.completedFuture(null))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        warnRateLimited("Could not broadcast Discord chat in Minecraft: "
                                + rootMessage(error));
                    } else if (active) {
                        plugin.recordFeatureUse("chat_discord_to_mc");
                    }
                });
    }

    private String renderDiscordMessage(
            DiscordMessageSnapshot snapshot,
            LinkedAccount account,
            String format,
            OfflinePlayer placeholderPlayer
    ) {
        String minecraftName = account == null || account.minecraftName() == null
                || account.minecraftName().isBlank()
                ? snapshot.displayName() : sanitizeMinecraftUserText(account.minecraftName());
        String minecraftUuid = account == null
                ? "" : sanitizeMinecraftUserText(account.minecraftUuid());
        String formatted = format
                .replace("%user%", snapshot.displayName())
                .replace("%discord_user%", snapshot.displayName())
                .replace("%discord_name%", snapshot.username())
                .replace("%discord_id%", snapshot.discordId())
                .replace("%top_role%", snapshot.topRole())
                .replace("%minecraft_player%", minecraftName)
                .replace("%minecraft_uuid%", minecraftUuid)
                .replace("%linked%", Boolean.toString(account != null))
                .replace("%message%", snapshot.message())
                .replace("%attachments%", snapshot.attachments())
                .replace("%reply%", snapshot.reply());
        formatted = plugin.getPlaceholderService().apply(placeholderPlayer, formatted);
        return ChatColor.translateAlternateColorCodes('&', truncate(formatted, 8_192));
    }

    private CompletableFuture<Void> broadcastFoliaSafe(String message) {
        return plugin.callSync(() -> {
            Bukkit.getConsoleSender().sendMessage(message);
            return Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        }).thenCompose(playerIds -> {
            List<CompletableFuture<Boolean>> deliveries = playerIds.stream()
                    .map(playerId -> plugin.runForPlayer(playerId,
                            player -> player.sendMessage(message)))
                    .toList();
            return CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new));
        });
    }

    @Override
    public String statusDetail() {
        return policySummary + (coreWebhookId > 0L ? "; webhook=" + coreWebhookId : "")
                + (operationsPaused ? "; PAUSED by operations" : "");
    }

    /** Incident/maintenance pause which does not mutate configuration or reload listeners. */
    public void setOperationsPaused(boolean paused) {
        operationsPaused = paused;
    }

    public boolean isOperationsPaused() {
        return operationsPaused;
    }

    @Override
    public void disable() {
        active = false;
        operationsPaused = false;
        webhookFuture = null;
        coreWebhookId = 0L;
        links = null;
        cannedCooldowns.clear();
        if (bukkitListener != null) {
            HandlerList.unregisterAll(bukkitListener);
            bukkitListener = null;
        }
        DiscordBotService discord = plugin.getDiscordService();
        if (discordListener != null && discord != null) {
            discord.removeEventListener(discordListener);
            discordListener = null;
        }
    }

    private void sendWebhook(
            TextChannel channel,
            String webhookName,
            boolean autoCreate,
            boolean fallbackToBot,
            String content,
            String username,
            String avatarUrl
    ) {
        CompletableFuture<Webhook> resolved;
        try {
            resolved = resolveWebhook(channel, webhookName, autoCreate);
        } catch (RuntimeException error) {
            warnRateLimited("Webhook resolution failed"
                    + (fallbackToBot ? "; using the bot fallback: " : ": ")
                    + rootMessage(error));
            if (fallbackToBot) {
                sendBotMessage(channel, content);
            }
            return;
        }
        resolved.whenComplete((webhook, error) -> {
            if (!active) {
                return;
            }
            if (error != null || webhook == null) {
                warnRateLimited("Webhook unavailable"
                        + (fallbackToBot ? "; using the bot fallback: " : ": ")
                        + rootMessage(error));
                if (fallbackToBot) {
                    sendBotMessage(channel, content);
                }
                return;
            }
            try {
                var action = webhook.sendMessage(content)
                        .setUsername(username.isBlank() ? "Minecraft" : username)
                        .setAllowedMentions(Collections.emptyList());
                if (validHttpsUrl(avatarUrl)) {
                    action.setAvatarUrl(avatarUrl);
                }
                action.queue(ignored -> plugin.recordFeatureUse("chat_mc_to_discord"), sendError -> {
                    webhookFuture = null;
                    // The request may have reached Discord before the callback failed.
                    // Retrying through the normal bot here could duplicate player chat.
                    warnRateLimited("Webhook send failed after submission; bot fallback was not used "
                            + "to avoid a possible duplicate: " + rootMessage(sendError));
                });
            } catch (RuntimeException sendError) {
                webhookFuture = null;
                warnRateLimited("Webhook send failed"
                        + (fallbackToBot ? "; using the bot fallback: " : ": ")
                        + rootMessage(sendError));
                if (fallbackToBot) {
                    sendBotMessage(channel, content);
                }
            }
        });
    }

    private CompletableFuture<Webhook> resolveWebhook(TextChannel channel, String name, boolean autoCreate) {
        CompletableFuture<Webhook> existing = webhookFuture;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (webhookFuture != null) {
                return webhookFuture;
            }
            if (!active) {
                return CompletableFuture.failedFuture(new IllegalStateException("Chat sync is disabled"));
            }
            webhookFuture = channel.retrieveWebhooks().submit().thenCompose(webhooks -> {
                if (!active) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Chat sync is disabled"));
                }
                long selfId = channel.getJDA().getSelfUser().getIdLong();
                Webhook selected = webhooks.stream()
                        .filter(webhook -> Objects.equals(webhook.getName(), name))
                        .filter(webhook -> webhook.getOwnerAsUser() != null
                                && webhook.getOwnerAsUser().getIdLong() == selfId)
                        .filter(webhook -> webhook.getToken() != null && !webhook.getToken().isBlank())
                        .findFirst().orElse(null);
                if (selected != null) {
                    return CompletableFuture.completedFuture(selected);
                }
                if (!autoCreate) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "No reusable webhook named '" + name + "' exists and auto-create is disabled"));
                }
                if (!active) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Chat sync is disabled"));
                }
                return channel.createWebhook(name).submit();
            }).thenApply(webhook -> {
                if (!active) {
                    throw new IllegalStateException("Chat sync was disabled while resolving its webhook");
                }
                coreWebhookId = webhook.getIdLong();
                return webhook;
            }).whenComplete((ignored, error) -> {
                if (error != null) {
                    webhookFuture = null;
                }
            });
            return webhookFuture;
        }
    }

    private void sendBotMessage(TextChannel channel, String output) {
        try {
            channel.sendMessage(output)
                    .setAllowedMentions(Collections.emptyList())
                    .queue(ignored -> plugin.recordFeatureUse("chat_mc_to_discord"), error -> warnRateLimited(
                            "Could not forward Minecraft chat to Discord: " + rootMessage(error)));
        } catch (RuntimeException error) {
            warnRateLimited("Could not forward Minecraft chat to Discord: " + rootMessage(error));
        }
    }

    private void sendDeniedNotice(MessageReceivedEvent event, boolean enabled, String notice) {
        if (!enabled || notice.isBlank()) {
            return;
        }
        try {
            event.getMessage().reply(truncateDiscord(sanitizeMassMentions(notice)))
                    .setAllowedMentions(Collections.emptyList())
                    .queue(ignored -> { }, error -> warnRateLimited(
                            "Could not send reverse-chat policy notice: " + rootMessage(error)));
        } catch (RuntimeException error) {
            warnRateLimited("Could not send reverse-chat policy notice: " + rootMessage(error));
        }
    }

    private boolean applyCannedResponse(MessageReceivedEvent event, List<CannedResponse> responses) {
        if (responses.isEmpty()) {
            return false;
        }
        String content = event.getMessage().getContentRaw().trim();
        for (CannedResponse response : responses) {
            if (!response.matches(content)) {
                continue;
            }
            String cooldownKey = response.id() + ':' + event.getAuthor().getId();
            long now = System.currentTimeMillis();
            Long previous = cannedCooldowns.putIfAbsent(cooldownKey, now);
            if (previous != null && now - previous < response.cooldownMillis()) {
                return response.stopForwarding();
            }
            cannedCooldowns.put(cooldownKey, now);
            pruneCannedCooldowns(now);
            String output = truncateDiscord(sanitizeMassMentions(response.response()
                    .replace("%user%", event.getAuthor().getName())
                    .replace("%mention%", event.getAuthor().getAsMention())));
            try {
                event.getChannel().sendMessage(output)
                        .setAllowedMentions(Collections.emptyList())
                        .queue(ignored -> { }, error -> warnRateLimited(
                                "Could not send canned response: " + rootMessage(error)));
            } catch (RuntimeException error) {
                warnRateLimited("Could not send canned response: " + rootMessage(error));
            }
            return response.stopForwarding();
        }
        return false;
    }

    private void pruneCannedCooldowns(long now) {
        if (cannedCooldowns.size() <= 10_000) {
            return;
        }
        long cutoff = now - 86_400_000L;
        cannedCooldowns.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        if (cannedCooldowns.size() > 20_000) {
            cannedCooldowns.clear();
        }
    }

    private static List<CannedResponse> parseCannedResponses(FileConfiguration config) {
        List<CannedResponse> responses = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> raw : config.getMapList("chat-sync.canned-responses")) {
            String id = text(raw.get("id"), "response-" + index++);
            String trigger = text(raw.get("trigger"), "");
            String reply = text(raw.get("response"), "");
            if (trigger.isBlank() || reply.isBlank()) {
                continue;
            }
            MatchMode mode;
            try {
                mode = MatchMode.valueOf(text(raw.get("match"), "EXACT").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown canned response match mode for " + id, exception);
            }
            boolean caseSensitive = bool(raw.get("case-sensitive"), false);
            long cooldown = clamp(number(raw.get("cooldown-seconds"), 30L), 0L, 86_400L) * 1000L;
            boolean stop = bool(raw.get("stop-forwarding"), false);
            Pattern regex = null;
            if (mode == MatchMode.REGEX) {
                try {
                    regex = Pattern.compile(trigger, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException exception) {
                    throw new IllegalArgumentException("Invalid canned response regex for " + id, exception);
                }
            }
            responses.add(new CannedResponse(id, trigger, reply, mode, caseSensitive, cooldown, stop, regex));
        }
        return List.copyOf(responses);
    }

    private static String renderAttachments(Message message, int maximum, String format) {
        if (maximum <= 0 || message.getAttachments().isEmpty()) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        int count = Math.min(maximum, message.getAttachments().size());
        for (int index = 0; index < count; index++) {
            Message.Attachment attachment = message.getAttachments().get(index);
            output.append(format
                    .replace("%name%", sanitizeMinecraftUserText(attachment.getFileName()))
                    .replace("%url%", sanitizeMinecraftUserText(attachment.getUrl()))
                    .replace("%index%", Integer.toString(index + 1)));
        }
        int remaining = message.getAttachments().size() - count;
        if (remaining > 0) {
            output.append(" &7(+").append(remaining).append(" more)");
        }
        return output.toString();
    }

    private static String renderReply(Message message, String format) {
        Message referenced = message.getReferencedMessage();
        if (referenced == null) {
            return "";
        }
        String replyUser = sanitizeMinecraftUserText(referenced.getAuthor().getEffectiveName());
        String replyMessage = sanitizeMinecraftUserText(referenced.getContentDisplay());
        return format
                .replace("%reply_user%", replyUser)
                .replace("%reply_message%", truncate(replyMessage, 160));
    }

    private static boolean rolePolicyAllows(Member member, Set<Long> allowedRoles, Set<Long> blockedRoles) {
        if (member == null) {
            return allowedRoles.isEmpty();
        }
        for (Role role : member.getRoles()) {
            if (blockedRoles.contains(role.getIdLong())) {
                return false;
            }
        }
        if (allowedRoles.isEmpty()) {
            return true;
        }
        for (Role role : member.getRoles()) {
            if (allowedRoles.contains(role.getIdLong())) {
                return true;
            }
        }
        return false;
    }

    private static Set<Long> snowflakeSet(List<String> configured) {
        Set<Long> values = new LinkedHashSet<>();
        for (String value : configured) {
            try {
                long parsed = Long.parseUnsignedLong(value == null ? "" : value.trim());
                if (parsed != 0L) {
                    values.add(parsed);
                }
            } catch (NumberFormatException ignored) {
                // Config validation and doctor output identify invalid IDs separately.
            }
        }
        return Set.copyOf(values);
    }

    private static String replaceMinecraftPlaceholders(
            String template,
            String playerName,
            String displayName,
            UUID playerId,
            String world,
            String server,
            String message
    ) {
        return template
                .replace("%player%", playerName)
                .replace("%displayname%", displayName)
                .replace("%uuid%", playerId.toString())
                .replace("%world%", world)
                .replace("%server%", server)
                .replace("%message%", message);
    }

    private DiscordBotService requireDiscordService() {
        DiscordBotService service = plugin.getDiscordService();
        if (service == null) {
            throw new IllegalStateException("Discord service is not initialised");
        }
        return service;
    }

    private void warnRateLimited(String message) {
        plugin.recordModuleFailure("chat-sync", message);
        long now = System.currentTimeMillis();
        long previous = lastSendWarning.get();
        if (now - previous >= 60_000L && lastSendWarning.compareAndSet(previous, now)) {
            plugin.getLogger().warning("[ChatSync] " + message);
        }
    }

    private static String sanitizeMinecraftUserText(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder clean = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '&') {
                clean.append('＆');
            } else if (character == '§') {
                clean.append('＃');
            } else if (Character.isISOControl(character)) {
                clean.append(' ');
            } else {
                clean.append(character);
            }
        }
        return clean.toString();
    }

    private static String sanitizeMassMentions(String input) {
        String withoutEveryone = EVERYONE_MENTION.matcher(input).replaceAll("@\u200Beveryone");
        return HERE_MENTION.matcher(withoutEveryone).replaceAll("@\u200Bhere");
    }

    private static String truncateDiscord(String input) {
        return truncate(input, 2000);
    }

    private static String truncate(String input, int maximum) {
        if (input.length() <= maximum) {
            return input;
        }
        int end = Math.max(0, maximum - 3);
        if (end > 0 && Character.isHighSurrogate(input.charAt(end - 1))
                && end < input.length() && Character.isLowSurrogate(input.charAt(end))) {
            end--;
        }
        return input.substring(0, end) + "...";
    }

    private static String sanitizeWebhookUsername(String input) {
        String clean = sanitizeMinecraftUserText(input).trim();
        if (clean.isBlank()) {
            clean = "Minecraft";
        }
        return truncate(clean, 80);
    }

    private static boolean validHttpsUrl(String input) {
        if (input == null || input.isBlank() || input.length() > 2048 || containsControl(input)) {
            return false;
        }
        try {
            URI uri = URI.create(input);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean containsControl(String input) {
        for (int index = 0; index < input.length(); index++) {
            if (Character.isISOControl(input.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static void validateSnowflake(String path, String value) {
        if (value.isBlank()) {
            return;
        }
        try {
            if (Long.parseUnsignedLong(value) == 0L) {
                throw new NumberFormatException("zero");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(path + " must be a positive Discord ID", exception);
        }
    }

    private static String value(FileConfiguration config, String path, String fallback) {
        String value = config.getString(path, fallback);
        return value == null ? fallback : value.trim();
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool
                : value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private enum MatchMode { EXACT, PREFIX, CONTAINS, REGEX }

    private record MinecraftDiscordMessage(String body, String username, String avatarUrl) { }

    private record DiscordMessageSnapshot(
            String discordId,
            String username,
            String displayName,
            String topRole,
            String message,
            String attachments,
            String reply
    ) { }

    private record CannedResponse(
            String id,
            String trigger,
            String response,
            MatchMode mode,
            boolean caseSensitive,
            long cooldownMillis,
            boolean stopForwarding,
            Pattern regex
    ) {
        private boolean matches(String content) {
            if (mode == MatchMode.REGEX) {
                return regex.matcher(content).matches();
            }
            String left = caseSensitive ? content : content.toLowerCase(Locale.ROOT);
            String right = caseSensitive ? trigger : trigger.toLowerCase(Locale.ROOT);
            return switch (mode) {
                case EXACT -> left.equals(right);
                case PREFIX -> left.startsWith(right);
                case CONTAINS -> left.contains(right);
                case REGEX -> false;
            };
        }
    }
}
