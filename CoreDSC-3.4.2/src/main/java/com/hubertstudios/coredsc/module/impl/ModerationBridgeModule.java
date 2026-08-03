package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.api.ModerationAuditService;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.ServicePriority;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Generic moderation audit bridge. It integrates with command-driven moderation
 * plugins through configurable regular expressions instead of unsafe hardcoded
 * reflection against every plugin implementation.
 */
public final class ModerationBridgeModule implements CoreModule, ModerationAuditService {
    private final CoreDSCPlugin plugin;
    private volatile List<CommandRule> rules = List.of();
    private final ArrayDeque<Long> deliveryWindow = new ArrayDeque<>();
    private final AtomicLong lastWarning = new AtomicLong();

    private Listener listener;
    private String channelId;
    private int maxMessagesPerMinute;
    private boolean includeRawCommand;
    private boolean logPlayerCommands;
    private boolean logConsoleCommands;
    private boolean observeCancelledPlayerCommands;
    private boolean logKickEvents;
    private boolean useDeliveryQueue;
    private String kickFormat;

    public ModerationBridgeModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "moderation-bridge";
    }

    @Override
    public void enable() {
        requireDiscord();
        FileConfiguration config = plugin.getAppConfig();
        channelId = value(config, "moderation-bridge.channel-id", "");
        if (!TextUtil.isPositiveSnowflake(channelId)) {
            throw new IllegalArgumentException(
                    "moderation-bridge.channel-id must be a positive Discord channel ID");
        }
        maxMessagesPerMinute = (int) clamp(config.getLong(
                "moderation-bridge.max-messages-per-minute", 30L), 1L, 600L);
        includeRawCommand = config.getBoolean("moderation-bridge.include-raw-command", false);
        logPlayerCommands = config.getBoolean("moderation-bridge.sources.player", true);
        logConsoleCommands = config.getBoolean("moderation-bridge.sources.console", true);
        observeCancelledPlayerCommands = config.getBoolean(
                "moderation-bridge.observe-cancelled-player-commands", true);
        logKickEvents = config.getBoolean("moderation-bridge.kick-events.enabled", false);
        useDeliveryQueue = config.getBoolean("moderation-bridge.use-delivery-queue", true);
        kickFormat = value(config, "moderation-bridge.kick-events.format",
                "⚠️ **%target%** was kicked by **%executor%**: %reason%");
        loadRules(config.getMapList("moderation-bridge.command-rules"));

        listener = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
                if (logPlayerCommands
                        && (observeCancelledPlayerCommands || !event.isCancelled())) {
                    inspect(event.getPlayer(), event.getPlayer().getName(),
                            stripSlash(event.getMessage()), "player");
                }
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onConsoleCommand(ServerCommandEvent event) {
                if (logConsoleCommands) {
                    inspect(event.getSender(), displaySender(event.getSender()),
                            stripSlash(event.getCommand()), "console");
                }
            }

            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onKick(PlayerKickEvent event) {
                if (!logKickEvents) {
                    return;
                }
                String reason = PlainTextComponentSerializer.plainText().serialize(event.reason());
                UUID playerId = event.getPlayer().getUniqueId();
                String playerName = event.getPlayer().getName();
                report("KICK", "SERVER/PLUGIN", playerName, reason, "", playerId);
            }
        };
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        plugin.getServer().getServicesManager().register(
                ModerationAuditService.class, this, plugin, ServicePriority.Normal);
    }

    @Override
    public void disable() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        plugin.getServer().getServicesManager().unregister(ModerationAuditService.class, this);
        rules = List.of();
        synchronized (deliveryWindow) {
            deliveryWindow.clear();
        }
    }

    @Override
    public String statusDetail() {
        return rules.size() + " command rule(s)";
    }

    /** Public adapter entry point for direct moderation integrations. */
    @Override
    public void report(ModerationAction action) {
        if (action == null) return;
        plugin.runSync(() -> reportGlobal(action));
    }

    private void reportGlobal(ModerationAction action) {
        String actionName = action.action() == null || action.action().isBlank()
                ? "MODERATION" : action.action().toUpperCase(Locale.ROOT);
        String format = kickFormat;
        for (CommandRule rule : rules) {
            if (rule.action().equalsIgnoreCase(actionName)) {
                format = rule.format();
                break;
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("action", actionName);
        values.put("executor", action.executor() == null ? "unknown" : action.executor());
        values.put("target", action.target() == null || action.target().isBlank() ? "unknown" : action.target());
        values.put("reason", action.reason() == null || action.reason().isBlank() ? "No reason provided" : action.reason());
        values.put("duration", action.duration() == null ? "" : action.duration());
        values.put("command", includeRawCommand ? (action.rawCommand() == null ? "" : action.rawCommand()) : "hidden");
        values.put("source", action.source() == null || action.source().isBlank() ? "adapter" : action.source());
        send(format, values, action.placeholderPlayerId());
        CaseModule cases = plugin.getModuleManager() == null ? null
                : plugin.getModuleManager().getModule(CaseModule.class);
        if (cases != null) {
            String targetUuid = action.targetUuid() == null ? "" : action.targetUuid();
            if (targetUuid.isBlank() && action.placeholderPlayerId() != null) {
                targetUuid = action.placeholderPlayerId().toString();
            }
            cases.recordModerationAction(actionName, targetUuid,
                    action.target() == null ? "" : action.target(),
                    action.executor() == null ? "unknown" : action.executor(),
                    action.reason() == null ? "" : action.reason(),
                    action.duration() == null ? "" : action.duration(),
                    action.source() == null || action.source().isBlank() ? "adapter" : action.source(),
                    action.externalId() == null ? "" : action.externalId(), action.confirmed())
                    .exceptionally(error -> {
                        plugin.getLogger().warning("[ModerationBridge] Could not create case: " + rootMessage(error));
                        return null;
                    });
        }
    }

    private void inspect(CommandSender sender, String executor, String command, String source) {
        for (CommandRule rule : rules) {
            if (!rule.sources().contains(source)) {
                continue;
            }
            Matcher matcher = rule.pattern().matcher(command);
            if (!matcher.matches()) {
                continue;
            }
            String target = namedGroup(matcher, "target");
            String reason = namedGroup(matcher, "reason");
            String duration = namedGroup(matcher, "duration");
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("action", rule.action());
            values.put("executor", executor);
            values.put("target", target.isBlank() ? "unknown" : target);
            values.put("reason", reason.isBlank() ? "No reason provided" : reason);
            values.put("duration", duration);
            values.put("command", includeRawCommand ? command : "hidden");
            values.put("source", source);

            UUID contextPlayerId = null;
            if (!target.isBlank()) {
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(target);
                contextPlayerId = cached == null ? null : cached.getUniqueId();
            }
            if (contextPlayerId == null && sender instanceof Player player) {
                contextPlayerId = player.getUniqueId();
            }
            send(rule.format(), values, contextPlayerId);
            recordCase(rule.action(), target, contextPlayerId, executor, reason, duration,
                    "command-" + source, "", false);
            return;
        }
    }

    private void send(String format, Map<String, Object> values, UUID contextPlayerId) {
        Map<String, Object> snapshot = Map.copyOf(values);
        java.util.function.Function<OfflinePlayer, String> render = context -> {
            String message = TextUtil.replace(format, snapshot);
            message = plugin.getPlaceholderService().apply(context, message);
            return TextUtil.truncate(TextUtil.sanitizeMassMentions(message), 2000);
        };
        CompletableFuture<String> rendering;
        if (contextPlayerId == null) {
            rendering = plugin.callSync(() -> render.apply(null));
        } else {
            rendering = plugin.callForPlayer(contextPlayerId, render::apply)
                    .thenCompose(message -> message.isPresent()
                            ? CompletableFuture.completedFuture(message.get())
                            : plugin.callSync(() -> render.apply(
                                    Bukkit.getOfflinePlayer(contextPlayerId))));
        }
        rendering.whenComplete((message, error) -> {
            if (error != null) {
                warnRateLimited("Could not render moderation audit: " + rootMessage(error));
                return;
            }
            deliver(message);
        });
    }

    private void deliver(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (!allowDelivery()) return;
        DeliveryQueueModule queue = useDeliveryQueue && plugin.getModuleManager() != null
                ? plugin.getModuleManager().getModule(DeliveryQueueModule.class) : null;
        if (queue != null) {
            queue.enqueue(channelId, message, 20, "").exceptionally(error -> {
                warnRateLimited("Could not queue moderation audit: " + rootMessage(error));
                return null;
            });
            return;
        }
        DiscordBotService discord = plugin.getDiscordService();
        JDA jda = discord == null ? null : discord.getJda();
        if (discord == null || !discord.isReady() || jda == null) return;
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            warnRateLimited("Configured audit channel " + channelId + " is not visible to the bot.");
            return;
        }
        channel.sendMessage(message)
                .setAllowedMentions(java.util.Collections.emptyList())
                .queue(ignored -> { }, error -> warnRateLimited(
                        "Could not send moderation audit: " + rootMessage(error)));
    }

    private void loadRules(List<Map<?, ?>> configured) {
        List<CommandRule> loaded = new ArrayList<>();
        for (Map<?, ?> raw : configured) {
            if (!booleanValue(raw.get("enabled"), true)) {
                continue;
            }
            String action = string(raw.get("action")).toUpperCase(Locale.ROOT);
            String expression = string(raw.get("pattern"));
            String format = string(raw.get("format"));
            if (action.isBlank() || expression.isBlank() || format.isBlank()) {
                throw new IllegalArgumentException(
                        "Each moderation command rule requires action, pattern and format");
            }
            Pattern pattern;
            try {
                pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException(
                        "Invalid moderation regex for action " + action + ": " + exception.getMessage(),
                        exception);
            }
            List<String> sources = new ArrayList<>();
            Object rawSources = raw.get("sources");
            if (rawSources instanceof List<?> list) {
                for (Object item : list) {
                    String source = string(item).toLowerCase(Locale.ROOT);
                    if (!source.equals("player") && !source.equals("console")) {
                        throw new IllegalArgumentException(
                                "Moderation rule source must be player or console: " + source);
                    }
                    sources.add(source);
                }
            }
            if (sources.isEmpty()) {
                sources.add("player");
                sources.add("console");
            }
            loaded.add(new CommandRule(action, pattern, format, List.copyOf(sources)));
        }
        rules = List.copyOf(loaded);
    }

    private void recordCase(
            String action,
            String target,
            UUID contextPlayerId,
            String executor,
            String reason,
            String duration,
            String source,
            String externalId,
            boolean confirmed
    ) {
        CaseModule cases = plugin.getModuleManager() == null
                ? null
                : plugin.getModuleManager().getModule(CaseModule.class);
        if (cases == null) {
            return;
        }
        String uuid = contextPlayerId == null ? "" : contextPlayerId.toString();
        cases.recordModerationAction(action, uuid, target, executor, reason, duration, source, externalId, confirmed)
                .exceptionally(error -> {
                    warnRateLimited("Could not create moderation case: " + rootMessage(error));
                    return null;
                });
    }

    private boolean allowDelivery() {
        long now = System.currentTimeMillis();
        long cutoff = now - 60_000L;
        synchronized (deliveryWindow) {
            while (!deliveryWindow.isEmpty() && deliveryWindow.peekFirst() < cutoff) {
                deliveryWindow.removeFirst();
            }
            if (deliveryWindow.size() >= maxMessagesPerMinute) {
                warnRateLimited("Moderation audit rate limit reached; extra messages are being dropped.");
                return false;
            }
            deliveryWindow.addLast(now);
            return true;
        }
    }

    private DiscordBotService requireDiscord() {
        DiscordBotService service = plugin.getDiscordService();
        if (service == null) {
            throw new IllegalStateException("Discord service is not initialised");
        }
        return service;
    }

    private void warnRateLimited(String message) {
        plugin.recordModuleFailure("moderation-bridge", message);
        long now = System.currentTimeMillis();
        long previous = lastWarning.get();
        if (now - previous >= 60_000L && lastWarning.compareAndSet(previous, now)) {
            plugin.getLogger().warning("[ModerationBridge] " + message);
        }
    }

    private static String namedGroup(Matcher matcher, String name) {
        try {
            String value = matcher.group(name);
            return value == null ? "" : value.trim();
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static String displaySender(CommandSender sender) {
        String name = sender.getName();
        return name == null || name.isBlank() ? "CONSOLE" : name;
    }

    private static String stripSlash(String command) {
        String value = command == null ? "" : command.trim();
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static String value(FileConfiguration config, String path, String fallback) {
        String configured = config.getString(path, fallback);
        return configured == null ? fallback : configured;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record CommandRule(
            String action,
            Pattern pattern,
            String format,
            List<String> sources
    ) { }
}
