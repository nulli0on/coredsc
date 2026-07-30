package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.storage.ConsoleAuditRepository;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import com.hubertstudios.coredsc.scheduler.CoreTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Batched Discord console feed and tightly controlled remote command bridge. */
public final class ConsoleModule implements CoreModule {
    private enum RemoteMode { OFF, ALLOWLIST, FULL }

    private final CoreDSCPlugin plugin;
    private final Queue<String> lines = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedLines = new AtomicInteger();
    private final AtomicLong droppedLines = new AtomicLong();
    private final ConcurrentHashMap<String, Long> commandCooldowns = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> globalCommandWindow = new ArrayDeque<>();
    private final AtomicLong commandInFlight = new AtomicLong();
    private final AtomicLong commandSequence = new AtomicLong();
    private ConsoleAuditRepository audits;
    private Handler logHandler;
    private ListenerAdapter discordListener;
    private CoreTask flushTask;
    private CoreTask auditCleanupTask;
    private volatile boolean active;
    private String channelId;
    private String guildId;
    private int maximumQueue;
    private int maximumBatchLines;
    private List<Level> levels = List.of(Level.INFO, Level.WARNING, Level.SEVERE);
    private List<Pattern> includePatterns = List.of();
    private List<Pattern> excludePatterns = List.of();
    private List<Pattern> redactionPatterns = List.of();
    private RemoteMode remoteMode = RemoteMode.OFF;
    private String commandPrefix = "!console ";
    private Set<String> roleIds = Set.of();
    private Set<String> allowlistedRoots = Set.of();
    private List<Pattern> deniedCommands = List.of();
    private long commandCooldownMillis;
    private int maximumCommandLength;
    private int maximumCommandsPerMinute;
    private long auditRetentionMillis;

    public ConsoleModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "console";
    }

    @Override
    public void enable() {
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null) throw new IllegalStateException("Discord service is not initialised");
        FileConfiguration config = plugin.getAppConfig();
        channelId = text(config.getString("console.channel-id", ""));
        guildId = text(config.getString("console.guild-id", config.getString("discord.guild-id", "")));
        validateSnowflake("console.channel-id", channelId, true);
        validateSnowflake("console.guild-id", guildId, true);
        if (channelId.isBlank()) throw new IllegalArgumentException("console.channel-id is required when the module is enabled");

        audits = new ConsoleAuditRepository(plugin.getStorage());
        maximumQueue = clamp(config.getInt("console.feed.maximum-queued-lines", 500), 50, 10_000);
        maximumBatchLines = clamp(config.getInt("console.feed.maximum-lines-per-batch", 40), 1, 200);
        levels = parseLevels(config.getStringList("console.feed.levels"));
        includePatterns = compilePatterns(config.getStringList("console.feed.include-patterns"), "include-patterns");
        excludePatterns = compilePatterns(config.getStringList("console.feed.exclude-patterns"), "exclude-patterns");
        redactionPatterns = compilePatterns(config.getStringList("console.feed.redact-patterns"), "redact-patterns");

        try {
            remoteMode = RemoteMode.valueOf(text(config.getString("console.remote.mode", "OFF")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("console.remote.mode must be OFF, ALLOWLIST or FULL", exception);
        }
        commandPrefix = config.getString("console.remote.prefix", "!console ");
        if (commandPrefix == null || commandPrefix.isBlank() || commandPrefix.length() > 40
                || commandPrefix.indexOf('\0') >= 0 || commandPrefix.indexOf('\n') >= 0 || commandPrefix.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("console.remote.prefix must contain 1-40 printable characters");
        }
        roleIds = parseSnowflakes(config.getStringList("console.remote.role-ids"), "console.remote.role-ids");
        allowlistedRoots = config.getStringList("console.remote.allowlisted-commands").stream()
                .map(ConsoleModule::commandRoot).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        deniedCommands = compilePatterns(config.getStringList("console.remote.deny-patterns"), "remote.deny-patterns");
        commandCooldownMillis = clamp(config.getLong("console.remote.cooldown-seconds", 3L), 0L, 3600L) * 1000L;
        maximumCommandLength = clamp(config.getInt("console.remote.maximum-command-length", 256), 16, 1000);
        maximumCommandsPerMinute = clamp(config.getInt("console.remote.maximum-commands-per-minute", 20), 1, 300);
        auditRetentionMillis = clamp(config.getLong("console.remote.audit-retention-days", 90L), 1L, 3650L)
                * 86_400_000L;
        if (remoteMode != RemoteMode.OFF && guildId.isBlank()) {
            throw new IllegalArgumentException("console.guild-id or discord.guild-id is required for remote execution");
        }
        if (remoteMode != RemoteMode.OFF && roleIds.isEmpty()) {
            throw new IllegalArgumentException("console.remote.role-ids must not be empty when remote execution is enabled");
        }
        if (remoteMode == RemoteMode.ALLOWLIST && allowlistedRoots.isEmpty()) {
            throw new IllegalArgumentException("console.remote.allowlisted-commands is empty");
        }
        if (remoteMode == RemoteMode.FULL && deniedCommands.isEmpty()) {
            throw new IllegalArgumentException("FULL remote mode requires at least one deny-pattern");
        }
        if (remoteMode == RemoteMode.FULL && !config.getBoolean("console.remote.confirm-full-access", false)) {
            throw new IllegalArgumentException("FULL remote mode requires console.remote.confirm-full-access: true");
        }

        active = true;
        if (config.getBoolean("console.feed.enabled", true)) {
            installLogHandler();
            long interval = clamp(config.getLong("console.feed.batch-interval-ticks", 40L), 20L, 1200L);
            flushTask = plugin.getCoreScheduler().runGlobalTimer(this::flush, interval, interval);
        }

        auditCleanupTask = plugin.getCoreScheduler().runGlobalTimer(this::cleanupAudit,
                20L, 20L * 60L * 60L * 24L);

        discordListener = new ListenerAdapter() {
            @Override
            public void onMessageReceived(MessageReceivedEvent event) {
                if (!active || remoteMode == RemoteMode.OFF || !event.isFromGuild()) return;
                if (!event.getGuild().getId().equals(guildId)) return;
                if (!event.getChannel().getId().equals(channelId)) return;
                if (event.getAuthor().isBot() || event.getMessage().isWebhookMessage()) return;
                String raw = event.getMessage().getContentRaw();
                if (!raw.startsWith(commandPrefix)) return;
                handleRemoteCommand(event, raw.substring(commandPrefix.length()).trim());
            }
        };
        discord.addEventListener(discordListener);
    }

    @Override
    public void disable() {
        active = false;
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (auditCleanupTask != null) {
            auditCleanupTask.cancel();
            auditCleanupTask = null;
        }
        if (logHandler != null) {
            Bukkit.getLogger().removeHandler(logHandler);
            logHandler.close();
            logHandler = null;
        }
        DiscordBotService discord = plugin.getDiscordService();
        if (discordListener != null && discord != null) {
            discord.removeEventListener(discordListener);
            discordListener = null;
        }
        lines.clear();
        queuedLines.set(0);
        commandCooldowns.clear();
        synchronized (globalCommandWindow) { globalCommandWindow.clear(); }
        audits = null;
    }

    @Override
    public String statusDetail() {
        return "channel=" + channelId + ", remote=" + remoteMode + ", queued=" + queuedLines.get();
    }

    private void installLogHandler() {
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (!active || record == null || !isLoggable(record)) return;
                String message = record.getMessage();
                if (message == null || message.contains("[Console]")) return;
                if (!acceptedLevel(record.getLevel())) return;
                String line = "[" + record.getLevel().getName() + "] " + message;
                if (!matchesFilters(line)) return;
                line = redact(line).replace('\0', ' ').replace('\r', ' ').replace('\n', ' ');
                enqueue(line);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logHandler.setLevel(Level.ALL);
        Bukkit.getLogger().addHandler(logHandler);
    }

    private void enqueue(String line) {
        while (queuedLines.get() >= maximumQueue) {
            String removed = lines.poll();
            if (removed == null) break;
            queuedLines.decrementAndGet();
            droppedLines.incrementAndGet();
        }
        lines.offer(line.length() <= 1500 ? line : line.substring(0, 1497) + "...");
        queuedLines.incrementAndGet();
    }

    private void flush() {
        if (!active) return;
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null || !discord.isReady() || discord.getJda() == null) return;
        TextChannel channel = discord.getJda().getTextChannelById(channelId);
        if (channel == null) return;

        List<String> batch = new ArrayList<>();
        long dropped = droppedLines.getAndSet(0L);
        if (dropped > 0L) batch.add("[CoreDSC] " + dropped + " console line(s) were dropped because the queue was full.");
        while (batch.size() < maximumBatchLines) {
            String line = lines.poll();
            if (line == null) break;
            queuedLines.decrementAndGet();
            batch.add(line);
        }
        if (batch.isEmpty()) return;

        for (String chunk : chunks(batch, 1900)) sendConsoleChunk(chunk, true);
    }

    private void sendConsoleChunk(String chunk, boolean retry) {
        if (!active) return;
        DiscordBotService discord = plugin.getDiscordService();
        TextChannel channel = discord == null || !discord.isReady() || discord.getJda() == null
                ? null : discord.getJda().getTextChannelById(channelId);
        if (channel == null) return;
        channel.sendMessage("```log\n" + escapeCodeFence(chunk) + "\n```")
                .setAllowedMentions(Collections.emptyList())
                .queue(ignored -> { }, error -> {
                    if (retry && active) {
                        plugin.getCoreScheduler().runGlobalLater(
                                () -> sendConsoleChunk(chunk, false), 100L);
                    } else {
                        plugin.getLogger().fine("[Console] Discord console batch failed: " + rootMessage(error));
                    }
                });
    }

    private void cleanupAudit() {
        ConsoleAuditRepository repository = audits;
        if (!active || repository == null) return;
        repository.deleteOlderThan(System.currentTimeMillis() - auditRetentionMillis)
                .exceptionally(error -> {
                    plugin.getLogger().warning("[Console] Could not purge expired audit rows: " + rootMessage(error));
                    return 0;
                });
    }

    private void handleRemoteCommand(MessageReceivedEvent event, String command) {
        ConsoleAuditRepository auditRepository = audits;
        String auditMode = remoteMode.name();
        String userId = event.getAuthor().getId();
        String userName = event.getAuthor().getName();
        Member member = event.getMember();
        if (member == null || member.getRoles().stream().noneMatch(role -> roleIds.contains(role.getId()))) {
            audit(auditRepository, auditMode, userId, userName, command, "DENIED_ROLE", "Missing an allowed Discord role");
            reply(event, "Remote console access denied.");
            return;
        }
        String validation = validateRemoteCommand(command);
        if (validation != null) {
            audit(auditRepository, auditMode, userId, userName, command, "DENIED_POLICY", validation);
            reply(event, "Command denied: " + validation);
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = commandCooldowns.get(userId);
        if (previous != null && now - previous < commandCooldownMillis) {
            audit(auditRepository, auditMode, userId, userName, command, "RATE_LIMITED", "Per-user cooldown");
            reply(event, "Command rate limit active.");
            return;
        }
        long commandId = commandSequence.incrementAndGet();
        if (!commandInFlight.compareAndSet(0L, commandId)) {
            audit(auditRepository, auditMode, userId, userName, command, "RATE_LIMITED", "Another remote command is still in flight");
            reply(event, "Another remote command is still being processed.");
            return;
        }
        if (!claimGlobalCommandSlot(now)) {
            commandInFlight.compareAndSet(commandId, 0L);
            audit(auditRepository, auditMode, userId, userName, command, "RATE_LIMITED", "Global commands-per-minute limit");
            reply(event, "Global command rate limit active.");
            return;
        }
        commandCooldowns.put(userId, now);
        pruneCommandCooldowns(now);
        audit(auditRepository, auditMode, userId, userName, command, "ACCEPTED", "Queued on the Minecraft main thread");
        plugin.callSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command))
                .whenComplete((accepted, error) -> {
                    commandInFlight.compareAndSet(commandId, 0L);
                    if (error != null) {
                        audit(auditRepository, auditMode, userId, userName, command, "FAILED", rootMessage(error));
                        reply(event, "Command execution failed.");
                    } else if (!Boolean.TRUE.equals(accepted)) {
                        audit(auditRepository, auditMode, userId, userName, command, "REJECTED_BY_SERVER", "Bukkit dispatch returned false");
                        reply(event, "The server did not accept that command.");
                    } else {
                        audit(auditRepository, auditMode, userId, userName, command, "EXECUTED", "Bukkit dispatch returned true");
                        reply(event, "Command executed.");
                    }
                });
    }

    private String validateRemoteCommand(String command) {
        if (command == null || command.isBlank()) return "empty command";
        if (command.length() > maximumCommandLength) return "command is too long";
        if (command.startsWith("/") || command.indexOf('\0') >= 0 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return "invalid command characters";
        }
        for (Pattern denied : deniedCommands) {
            if (denied.matcher(command).find()) return "matched a deny rule";
        }
        if (remoteMode == RemoteMode.ALLOWLIST && !allowlistedRoots.contains(commandRoot(command))) {
            return "command is not allowlisted";
        }
        return null;
    }

    private void audit(ConsoleAuditRepository repository, String mode, String userId, String userName,
                       String command, String outcome, String detail) {
        if (repository == null) return;
        repository.append(userId, userName, redact(command), mode, outcome, redact(detail), System.currentTimeMillis())
                .exceptionally(error -> {
                    plugin.getLogger().warning("[Console] Could not persist remote command audit: " + rootMessage(error));
                    return null;
                });
    }

    private boolean claimGlobalCommandSlot(long now) {
        synchronized (globalCommandWindow) {
            long cutoff = now - 60_000L;
            while (!globalCommandWindow.isEmpty() && globalCommandWindow.peekFirst() < cutoff) {
                globalCommandWindow.removeFirst();
            }
            if (globalCommandWindow.size() >= maximumCommandsPerMinute) return false;
            globalCommandWindow.addLast(now);
            return true;
        }
    }

    private static void reply(MessageReceivedEvent event, String message) {
        event.getMessage().reply(message).setAllowedMentions(Collections.emptyList()).queue();
    }

    private void pruneCommandCooldowns(long now) {
        if (commandCooldowns.size() <= 10_000) return;
        long cutoff = now - Math.max(commandCooldownMillis, 3_600_000L);
        commandCooldowns.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private boolean acceptedLevel(Level level) {
        for (Level accepted : levels) if (level.intValue() >= accepted.intValue()) return true;
        return false;
    }

    private boolean matchesFilters(String line) {
        if (!includePatterns.isEmpty() && includePatterns.stream().noneMatch(pattern -> pattern.matcher(line).find())) return false;
        return excludePatterns.stream().noneMatch(pattern -> pattern.matcher(line).find());
    }

    private String redact(String line) {
        String output = line;
        for (Pattern pattern : redactionPatterns) output = pattern.matcher(output).replaceAll("[REDACTED]");
        return output;
    }

    private static List<String> chunks(List<String> lines, int maximum) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (current.length() > 0 && current.length() + line.length() + 1 > maximum) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (line.length() > maximum) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.add(line.substring(0, maximum));
            } else {
                if (current.length() > 0) current.append('\n');
                current.append(line);
            }
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    private static String escapeCodeFence(String input) {
        return input.replace("```", "`\u200B``").replace("@everyone", "@\u200Beveryone").replace("@here", "@\u200Bhere");
    }

    private static List<Level> parseLevels(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of(Level.INFO, Level.WARNING, Level.SEVERE);
        List<Level> result = new ArrayList<>();
        for (String value : raw) {
            try { result.add(Level.parse(value.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid console feed level: " + value, exception); }
        }
        return List.copyOf(result);
    }

    private static List<Pattern> compilePatterns(List<String> values, String path) {
        List<Pattern> patterns = new ArrayList<>();
        if (values == null) return List.of();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            try { patterns.add(Pattern.compile(value, Pattern.CASE_INSENSITIVE)); }
            catch (PatternSyntaxException exception) { throw new IllegalArgumentException("Invalid regex in console." + path + ": " + value, exception); }
        }
        return List.copyOf(patterns);
    }

    private static Set<String> parseSnowflakes(List<String> values, String path) {
        Set<String> result = new HashSet<>();
        if (values == null) return Set.of();
        for (String value : values) {
            String clean = text(value);
            validateSnowflake(path, clean, false);
            result.add(clean);
        }
        return Set.copyOf(result);
    }

    private static void validateSnowflake(String path, String value, boolean blankAllowed) {
        if (value.isBlank() && blankAllowed) return;
        try {
            if (Long.parseUnsignedLong(value) == 0L) throw new NumberFormatException("zero");
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(path + " contains an invalid Discord ID", exception);
        }
    }

    private static String commandRoot(String input) {
        String value = text(input).toLowerCase(Locale.ROOT);
        while (value.startsWith("/")) value = value.substring(1);
        int space = value.indexOf(' ');
        if (space >= 0) value = value.substring(0, space);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(colon + 1);
        return value.replaceAll("[^a-z0-9_-]", "");
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static long clamp(long value, long minimum, long maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static String rootMessage(Throwable throwable) {
        Throwable current = Objects.requireNonNullElseGet(throwable, () -> new IllegalStateException("unknown error"));
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
