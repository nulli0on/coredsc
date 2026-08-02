package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.event.AccountLinkedEvent;
import com.hubertstudios.coredsc.event.AccountUnlinkedEvent;
import com.hubertstudios.coredsc.event.ReportCreateEvent;
import com.hubertstudios.coredsc.event.TicketCloseEvent;
import com.hubertstudios.coredsc.event.TicketCreateEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import com.hubertstudios.coredsc.scheduler.CoreTask;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public final class ServerEventsModule implements CoreModule {
    private final CoreDSCPlugin plugin;
    private final ArrayDeque<Long> deliveryWindow = new ArrayDeque<>();
    private final AtomicLong lastWarning = new AtomicLong();
    private final Set<UUID> kickedPlayers = ConcurrentHashMap.newKeySet();
    private final List<String> pendingJoins = new ArrayList<>();
    private final List<String> pendingQuits = new ArrayList<>();

    private Listener bukkitListener;
    private ListenerAdapter discordListener;
    private CoreTask batchTask;
    private String channelId;
    private int maxMessagesPerMinute;
    private boolean suppressQuitAfterKick;
    private boolean useDeliveryQueue;
    private boolean embedsEnabled;
    private int embedColor;
    private String embedTitle;
    private String embedFooter;
    private boolean batchingEnabled;
    private int batchMaximumNames;
    private String joinBatchFormat;
    private String quitBatchFormat;
    private EventFormat startup;
    private EventFormat shutdown;
    private EventFormat join;
    private EventFormat quit;
    private EventFormat firstJoin;
    private EventFormat world;
    private EventFormat death;
    private EventFormat advancement;
    private EventFormat kick;
    private EventFormat accountLink;
    private EventFormat accountUnlink;
    private EventFormat ticketCreate;
    private EventFormat ticketClose;
    private EventFormat reportCreate;
    private volatile boolean active;

    public ServerEventsModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "server-events";
    }

    @Override
    public void enable() {
        DiscordBotService discord = requireDiscord();
        FileConfiguration config = plugin.getAppConfig();
        channelId = value(config, "server-events.channel-id", "");
        if (!TextUtil.isPositiveSnowflake(channelId)) {
            throw new IllegalArgumentException("server-events.channel-id must be a positive Discord channel ID");
        }
        maxMessagesPerMinute = (int) clamp(config.getLong(
                "server-events.max-messages-per-minute", 60L), 1L, 600L);
        suppressQuitAfterKick = config.getBoolean("server-events.suppress-quit-after-kick", true);
        useDeliveryQueue = config.getBoolean("server-events.use-delivery-queue", true);
        embedsEnabled = config.getBoolean("server-events.embeds.enabled", true);
        embedColor = parseColor(value(config, "server-events.embeds.color", "#5865F2"));
        embedTitle = value(config, "server-events.embeds.title", "CoreDSC • %server_name%");
        embedFooter = value(config, "server-events.embeds.footer", "");
        batchingEnabled = config.getBoolean("server-events.batching.enabled", true);
        batchMaximumNames = (int) clamp(config.getLong("server-events.batching.maximum-names", 20L), 1L, 100L);
        joinBatchFormat = value(config, "server-events.batching.join-format", "🟢 **%count%** joined: %players%");
        quitBatchFormat = value(config, "server-events.batching.quit-format", "🔴 **%count%** left: %players%");

        startup = read(config, "startup", true, "🟢 **%server_name%** is online.");
        shutdown = read(config, "shutdown", true, "🔴 **%server_name%** is stopping.");
        join = read(config, "join", true, "🟢 **%player%** joined the server.");
        quit = read(config, "quit", true, "🔴 **%player%** left the server.");
        firstJoin = read(config, "first-join", true, "✨ **%player%** joined for the first time.");
        world = read(config, "world", false, "🌍 **%player%** moved from `%from_world%` to `%to_world%`.");
        death = read(config, "death", true, "☠️ %death_message%");
        advancement = read(config, "advancement", false, "🏆 **%player%** completed `%advancement%`.");
        kick = read(config, "kick", false, "⚠️ **%player%** was kicked: %reason%");
        accountLink = read(config, "account-link", true, "🔗 **%player%** linked Discord account `%discord_id%`.");
        accountUnlink = read(config, "account-unlink", true, "⛓️‍💥 **%player%** unlinked Discord account `%discord_id%`.");
        ticketCreate = read(config, "ticket-create", false, "🎫 Ticket **#%ticket_id%** created: %reason%");
        ticketClose = read(config, "ticket-close", false, "✅ Ticket **#%ticket_id%** closed by **%closed_by%**.");
        reportCreate = read(config, "report-create", false,
                "🚩 Report **#%report_id%**: **%reporter%** reported **%target%** for %reason%.");

        active = true;
        bukkitListener = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onJoin(PlayerJoinEvent event) {
                String name = event.getPlayer().getName();
                if (!event.getPlayer().hasPlayedBefore()) {
                    send(firstJoin, event.getPlayer(), Map.of("player", name));
                }
                if (batchingEnabled && join.enabled()) queueBatch(pendingJoins, name);
                else send(join, event.getPlayer(), Map.of("player", name));
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onQuit(PlayerQuitEvent event) {
                if (suppressQuitAfterKick && kickedPlayers.remove(event.getPlayer().getUniqueId())) return;
                String name = event.getPlayer().getName();
                if (batchingEnabled && quit.enabled()) queueBatch(pendingQuits, name);
                else send(quit, event.getPlayer(), Map.of("player", name));
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onWorldChange(PlayerChangedWorldEvent event) {
                send(world, event.getPlayer(), Map.of(
                        "player", event.getPlayer().getName(),
                        "from_world", event.getFrom().getName(),
                        "to_world", event.getPlayer().getWorld().getName()));
            }

            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onDeath(PlayerDeathEvent event) {
                String message = event.deathMessage() == null
                        ? event.getEntity().getName() + " died"
                        : PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
                send(death, event.getEntity(), Map.of("player", event.getEntity().getName(), "death_message", message));
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onAdvancement(PlayerAdvancementDoneEvent event) {
                Advancement completed = event.getAdvancement();
                String key = completed.getKey().toString();
                if (!key.startsWith("minecraft:recipes/")) {
                    send(advancement, event.getPlayer(), Map.of("player", event.getPlayer().getName(), "advancement", key));
                }
            }

            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onKick(PlayerKickEvent event) {
                if (suppressQuitAfterKick) kickedPlayers.add(event.getPlayer().getUniqueId());
                String reason = PlainTextComponentSerializer.plainText().serialize(event.reason());
                send(kick, event.getPlayer(), Map.of("player", event.getPlayer().getName(), "reason", reason));
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onAccountLinked(AccountLinkedEvent event) {
                if (event.minecraftUuid() == null) {
                    plugin.getLogger().warning("[ServerEvents] Ignored AccountLinkedEvent without a Minecraft UUID.");
                    return;
                }
                send(accountLink, Bukkit.getOfflinePlayer(event.minecraftUuid()), Map.of(
                        "player", safe(event.minecraftName()), "discord_id", safe(event.discordUserId())));
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onAccountUnlinked(AccountUnlinkedEvent event) {
                if (event.minecraftUuid() == null) {
                    plugin.getLogger().warning("[ServerEvents] Ignored AccountUnlinkedEvent without a Minecraft UUID.");
                    return;
                }
                send(accountUnlink, Bukkit.getOfflinePlayer(event.minecraftUuid()), Map.of(
                        "player", safe(event.minecraftName()), "discord_id", safe(event.discordUserId())));
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onTicketCreated(TicketCreateEvent event) {
                if (event.minecraftUuid() == null) {
                    plugin.getLogger().warning("[ServerEvents] Ignored TicketCreateEvent without a Minecraft UUID.");
                    return;
                }
                send(ticketCreate, Bukkit.getOfflinePlayer(event.minecraftUuid()), Map.of(
                        "ticket_id", Long.toString(event.ticketId()),
                        "discord_id", safe(event.discordUserId()),
                        "reason", safe(event.reason())));
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onTicketClosed(TicketCloseEvent event) {
                send(ticketClose, null, Map.of(
                        "ticket_id", Long.toString(event.ticketId()), "closed_by", safe(event.closedBy())));
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onReportCreated(ReportCreateEvent event) {
                if (event.reporterUuid() == null || event.targetUuid() == null) {
                    plugin.getLogger().warning("[ServerEvents] Ignored ReportCreateEvent without both Minecraft UUIDs.");
                    return;
                }
                OfflinePlayer reporter = Bukkit.getOfflinePlayer(event.reporterUuid());
                OfflinePlayer target = Bukkit.getOfflinePlayer(event.targetUuid());
                send(reportCreate, reporter, Map.of(
                        "report_id", Long.toString(event.reportId()),
                        "reporter", safeName(reporter, event.reporterUuid()),
                        "target", safeName(target, event.targetUuid()),
                        "reason", safe(event.reason())));
            }
        };
        plugin.getServer().getPluginManager().registerEvents(bukkitListener, plugin);

        if (batchingEnabled) {
            long interval = clamp(config.getLong("server-events.batching.interval-ticks", 100L), 20L, 1200L);
            batchTask = plugin.getCoreScheduler().runGlobalTimer(this::flushBatches, interval, interval);
        }

        discordListener = new ListenerAdapter() {
            @Override public void onReady(ReadyEvent event) { sendStartupOnce(); }
        };
        discord.addEventListener(discordListener);
        if (discord.isReady()) sendStartupOnce();
    }

    @Override
    public void disable() {
        if (active) flushBatches();
        active = false;
        if (batchTask != null) {
            batchTask.cancel();
            batchTask = null;
        }
        if (bukkitListener != null) {
            HandlerList.unregisterAll(bukkitListener);
            bukkitListener = null;
        }
        DiscordBotService discord = plugin.getDiscordService();
        if (discordListener != null && discord != null) discord.removeEventListener(discordListener);
        discordListener = null;
        synchronized (deliveryWindow) { deliveryWindow.clear(); }
        synchronized (pendingJoins) { pendingJoins.clear(); }
        synchronized (pendingQuits) { pendingQuits.clear(); }
        kickedPlayers.clear();
    }

    @Override
    public String statusDetail() {
        return "channel " + channelId + (embedsEnabled ? ", embeds" : ", text") + (batchingEnabled ? ", batching" : "");
    }

    private void queueBatch(List<String> queue, String name) {
        if (!active) return;
        synchronized (queue) {
            if (queue.size() < 1000) queue.add(name);
        }
    }

    private void flushBatches() {
        if (!active) return;
        flushBatch(pendingJoins, joinBatchFormat);
        flushBatch(pendingQuits, quitBatchFormat);
    }

    private void flushBatch(List<String> queue, String format) {
        List<String> names;
        synchronized (queue) {
            if (queue.isEmpty()) return;
            names = List.copyOf(queue);
            queue.clear();
        }
        int shown = Math.min(names.size(), batchMaximumNames);
        String players = String.join(", ", names.subList(0, shown));
        if (shown < names.size()) players += " and " + (names.size() - shown) + " more";
        EventFormat batch = new EventFormat(true, format, null);
        send(batch, null, Map.of("count", Integer.toString(names.size()), "players", players));
    }

    private void sendStartupOnce() {
        if (startup != null && startup.enabled() && plugin.claimServerStartupAnnouncement()) send(startup, null, Map.of());
    }

    public CompletableFuture<Void> sendShutdownEvent() {
        if (!plugin.wasServerStartupAnnouncementSent() || shutdown == null || !shutdown.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        
        
        try {
            return deliverShutdownMessage(renderEvent(shutdown, null, Map.of()));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletableFuture<Void> deliverShutdownMessage(RenderedEvent rendered) {
        if (rendered == null || rendered.message() == null || rendered.message().isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        DeliveryQueueModule queue = useDeliveryQueue && plugin.getModuleManager() != null
                ? plugin.getModuleManager().getModule(DeliveryQueueModule.class) : null;
        DiscordBotService discord = plugin.getDiscordService();
        JDA jda = discord == null ? null : discord.getJda();
        TextChannel channel = jda == null ? null : jda.getTextChannelById(channelId);

        if (embedsEnabled && discord != null && discord.isReady() && channel != null) {
            try {
                return channel.sendMessageEmbeds(buildEmbed(rendered))
                        .setAllowedMentions(java.util.Collections.emptyList())
                        .submit()
                        .thenAccept(ignored -> plugin.recordFeatureUse("server_event"))
                        .exceptionallyCompose(error -> queueShutdownFallback(queue, rendered.message(), error));
            } catch (RuntimeException error) {
                return queueShutdownFallback(queue, rendered.message(), error);
            }
        }
        if (queue != null) {
            return queue.enqueue(channelId, TextUtil.truncate(rendered.message(), 2000), 100,
                            "server-shutdown:" + System.currentTimeMillis())
                    .thenAccept(ignored -> plugin.recordFeatureUse("server_event"));
        }
        if (discord == null || !discord.isReady() || channel == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return channel.sendMessage(rendered.message())
                    .setAllowedMentions(java.util.Collections.emptyList())
                    .submit().thenAccept(ignored -> plugin.recordFeatureUse("server_event"));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletableFuture<Void> queueShutdownFallback(
            DeliveryQueueModule queue,
            String message,
            Throwable deliveryError
    ) {
        if (queue == null) {
            return CompletableFuture.failedFuture(deliveryError);
        }
        return queue.enqueue(channelId, TextUtil.truncate(message, 2000), 100,
                        "server-shutdown:" + System.currentTimeMillis())
                .thenAccept(ignored -> { });
    }

    private void send(EventFormat eventFormat, OfflinePlayer player, Map<String, String> eventValues) {
        if (!active || eventFormat == null || !eventFormat.enabled()) return;
        CompletableFuture<RenderedEvent> rendering = player instanceof org.bukkit.entity.Player online
                ? plugin.callForEntity(online, () -> renderEvent(eventFormat, player, eventValues))
                : plugin.callSync(() -> renderEvent(eventFormat, player, eventValues));
        rendering
                .whenComplete((rendered, error) -> {
                    if (!active) return;
                    if (error != null) warnRateLimited("Could not render event message: " + rootMessage(error));
                    else deliverMessage(rendered);
                });
    }

    private RenderedEvent renderEvent(EventFormat eventFormat, OfflinePlayer player, Map<String, String> eventValues) {
        Map<String, Object> values = baseValues();
        values.putAll(eventValues);
        String message = renderTemplate(eventFormat.format(), player, values, embedsEnabled ? 4096 : 2000);
        EventEmbed configured = eventFormat.embed();
        if (configured == null) {
            String title = renderTemplate(embedTitle, player, values, 256);
            String footer = renderTemplate(embedFooter, player, values, 2048);
            return new RenderedEvent(message, title, message, footer, embedColor, "", "");
        }
        String title = renderTemplate(configured.title(), player, values, 256);
        String description = renderTemplate(configured.description(), player, values, 4096);
        if (description.isBlank()) description = message;
        String footer = renderTemplate(configured.footer(), player, values, 2048);
        String thumbnailUrl = renderUrl(configured.thumbnailUrl(), player, values);
        String imageUrl = renderUrl(configured.imageUrl(), player, values);
        return new RenderedEvent(message, title, description, footer,
                configured.color(), thumbnailUrl, imageUrl);
    }

    private Map<String, Object> baseValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("server_name", plugin.getServer().getName());
        values.put("online_players", plugin.getServer().getOnlinePlayers().size());
        values.put("max_players", plugin.getServer().getMaxPlayers());
        values.put("server_version", plugin.getServer().getVersion());
        values.put("uptime", formatUptime(ManagementFactory.getRuntimeMXBean().getUptime() / 1000L));
        return values;
    }

    private void deliverMessage(RenderedEvent rendered) {
        if (!active || rendered == null || rendered.message() == null || rendered.message().isBlank() || !allowDelivery()) return;
        DeliveryQueueModule queue = useDeliveryQueue && plugin.getModuleManager() != null
                ? plugin.getModuleManager().getModule(DeliveryQueueModule.class) : null;
        DiscordBotService discord = plugin.getDiscordService();
        JDA jda = discord == null ? null : discord.getJda();
        TextChannel channel = jda == null ? null : jda.getTextChannelById(channelId);

        if (embedsEnabled && discord != null && discord.isReady() && channel != null) {
            try {
                MessageEmbed embed = buildEmbed(rendered);
                channel.sendMessageEmbeds(embed).setAllowedMentions(java.util.Collections.emptyList())
                        .queue(ignored -> plugin.recordFeatureUse("server_event"), error -> {
                            warnRateLimited("Could not deliver event embed: " + rootMessage(error));
                            enqueueFallback(queue, rendered.message());
                        });
            } catch (RuntimeException error) {
                warnRateLimited("Could not submit event embed: " + rootMessage(error));
                enqueueFallback(queue, rendered.message());
            }
            return;
        }
        if (queue != null) {
            enqueueFallback(queue, rendered.message());
            return;
        }
        if (discord == null || !discord.isReady() || channel == null) return;
        channel.sendMessage(rendered.message()).setAllowedMentions(java.util.Collections.emptyList())
                .queue(ignored -> plugin.recordFeatureUse("server_event"),
                        error -> warnRateLimited("Could not deliver event message: " + rootMessage(error)));
    }

    private void enqueueFallback(DeliveryQueueModule queue, String message) {
        if (!active || queue == null) return;
        queue.enqueue(channelId, TextUtil.truncate(message, 2000), 2, "").exceptionally(error -> {
            warnRateLimited("Could not queue event message: " + rootMessage(error));
            return null;
        });
    }

    private MessageEmbed buildEmbed(RenderedEvent rendered) {
        EmbedBuilder builder = new EmbedBuilder().setDescription(rendered.description())
                .setColor(rendered.color()).setTimestamp(Instant.now());
        if (!rendered.title().isBlank()) builder.setTitle(rendered.title());
        if (!rendered.footer().isBlank()) builder.setFooter(rendered.footer());
        if (!rendered.thumbnailUrl().isBlank()) builder.setThumbnail(rendered.thumbnailUrl());
        if (!rendered.imageUrl().isBlank()) builder.setImage(rendered.imageUrl());
        return builder.build();
    }

    private boolean allowDelivery() {
        if (!active) return false;
        long now = System.currentTimeMillis();
        long cutoff = now - 60_000L;
        synchronized (deliveryWindow) {
            while (!deliveryWindow.isEmpty() && deliveryWindow.peekFirst() < cutoff) deliveryWindow.removeFirst();
            if (deliveryWindow.size() >= maxMessagesPerMinute) {
                warnRateLimited("Event rate limit reached; additional messages are being dropped.");
                return false;
            }
            deliveryWindow.addLast(now);
            return true;
        }
    }

    private EventFormat read(FileConfiguration config, String eventName, boolean defaultEnabled, String defaultFormat) {
        ConfigurationSection section = config.getConfigurationSection("server-events.events." + eventName);
        if (section == null) return new EventFormat(defaultEnabled, defaultFormat, null);
        String format = section.getString("format", defaultFormat);
        ConfigurationSection embed = section.getConfigurationSection("embed");
        EventEmbed eventEmbed = null;
        if (embed != null) {
            eventEmbed = new EventEmbed(
                    safe(embed.getString("title", "")),
                    safe(embed.getString("description", format == null ? defaultFormat : format)),
                    parseColor(safe(embed.getString("color", "#5865F2")),
                            "server-events.events." + eventName + ".embed.color"),
                    safe(embed.getString("thumbnail-url", "")),
                    safe(embed.getString("image-url", "")),
                    safe(embed.getString("footer", "")));
        }
        return new EventFormat(section.getBoolean("enabled", defaultEnabled),
                format == null ? defaultFormat : format, eventEmbed);
    }

    private String renderTemplate(
            String template,
            OfflinePlayer player,
            Map<String, Object> values,
            int maximumLength
    ) {
        String rendered = TextUtil.replace(template == null ? "" : template, values);
        rendered = plugin.getPlaceholderService().apply(player, rendered);
        return TextUtil.truncate(TextUtil.sanitizeMassMentions(rendered), maximumLength);
    }

    private String renderUrl(String template, OfflinePlayer player, Map<String, Object> values) {
        String rendered = renderTemplate(template, player, values, 2048).trim();
        if (rendered.isBlank()) return "";
        if (!validHttpsUrl(rendered)) {
            warnRateLimited("Ignored a non-HTTPS or invalid event embed image URL. Fix the URL in "
                    + "modules/server-events.yml or in the WebEditor.");
            return "";
        }
        return rendered;
    }

    private DiscordBotService requireDiscord() {
        DiscordBotService service = plugin.getDiscordService();
        if (service == null) throw new IllegalStateException("Discord service is not initialised");
        return service;
    }

    private void warnRateLimited(String message) {
        plugin.recordModuleFailure("server-events", message);
        long now = System.currentTimeMillis();
        long previous = lastWarning.get();
        if (now - previous >= 60_000L && lastWarning.compareAndSet(previous, now)) plugin.getLogger().warning("[ServerEvents] " + message);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeName(OfflinePlayer player, UUID fallback) {
        String name = player.getName();
        return name == null || name.isBlank() ? fallback.toString() : name;
    }

    private static int parseColor(String value) {
        return parseColor(value, "server-events.embeds.color");
    }

    private static int parseColor(String value, String path) {
        String clean = value.startsWith("#") ? value.substring(1) : value;
        if (!clean.matches("[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException(path + " must be a six-digit hex color");
        }
        return Integer.parseInt(clean, 16);
    }

    private static boolean validHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static String formatUptime(long totalSeconds) {
        long days = totalSeconds / 86_400L;
        long hours = totalSeconds % 86_400L / 3_600L;
        long minutes = totalSeconds % 3_600L / 60L;
        return days > 0L ? days + "d " + hours + "h " + minutes + "m" : hours + "h " + minutes + "m";
    }

    private static String value(FileConfiguration config, String path, String fallback) {
        String configured = config.getString(path, fallback);
        return configured == null ? fallback : configured.trim();
    }

    private static long clamp(long value, long minimum, long maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record EventFormat(boolean enabled, String format, EventEmbed embed) { }
    private record EventEmbed(
            String title,
            String description,
            int color,
            String thumbnailUrl,
            String imageUrl,
            String footer
    ) { }
    private record RenderedEvent(
            String message,
            String title,
            String description,
            String footer,
            int color,
            String thumbnailUrl,
            String imageUrl
    ) { }
}
