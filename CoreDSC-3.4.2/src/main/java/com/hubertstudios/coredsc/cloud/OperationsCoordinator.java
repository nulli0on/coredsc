package com.hubertstudios.coredsc.cloud;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.module.impl.ChatSyncModule;
import com.hubertstudios.coredsc.scheduler.CoreTask;
import com.hubertstudios.coredsc.scripting.MiniJson;
import com.hubertstudios.coredsc.storage.CloudOperationRepository;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.awt.Color;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Restart-safe incident, maintenance, and scheduled-event coordination. */
public final class OperationsCoordinator implements Listener {
    private record MaintenanceState(
            String id, long startsAt, String message, boolean gateJoins,
            boolean pauseChat, boolean createDiscordEvent, String actor
    ) { }

    private record IncidentState(
            String id, String type, String reason, String actor,
            List<String> channelIds, boolean pauseChat, long startedAt
    ) { }

    private final CoreDSCPlugin plugin;
    private final CloudOperationRepository repository;
    private final ChannelOperationService channels;
    private final Supplier<Set<UUID>> onlinePlayers;
    private final AtomicReference<MaintenanceState> maintenance = new AtomicReference<>();
    private final AtomicReference<IncidentState> incident = new AtomicReference<>();
    private final CopyOnWriteArrayList<CoreTask> scheduledTasks = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, List<CoreTask>> eventTasks = new ConcurrentHashMap<>();
    private volatile boolean active;

    public OperationsCoordinator(
            CoreDSCPlugin plugin,
            CloudOperationRepository repository,
            ChannelOperationService channels,
            Supplier<Set<UUID>> onlinePlayers
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.channels = Objects.requireNonNull(channels, "channels");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
    }

    public void start() {
        active = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        restoreRuntimeState();
    }

    public void stop() {
        active = false;
        HandlerList.unregisterAll(this);
        cancelTasks();
        cancelAllEventTasks();
        setChatPaused(false);
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        MaintenanceState state = maintenance.get();
        if (!active || state == null || !state.gateJoins() || System.currentTimeMillis() < state.startsAt()) return;
        // AsyncPlayerPreLoginEvent contains detached identity primitives and is
        // intentionally cancelled on its own asynchronous login thread.
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "§6CoreDSC maintenance is active.\n§7" + state.message());
    }

    public CompletableFuture<Map<String, Object>> incident(
            String operation,
            Map<String, Object> payload,
            String operationId,
            String actor,
            String reason
    ) {
        return switch (operation) {
            case "incident.status" -> CompletableFuture.completedFuture(incidentStatus());
            case "incident.start" -> startIncident(payload, operationId, actor, reason);
            case "incident.resolve" -> resolveIncident(payload, reason);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Unsupported incident operation " + operation));
        };
    }

    public CompletableFuture<Map<String, Object>> maintenance(
            String operation,
            Map<String, Object> payload,
            String operationId,
            String actor,
            String reason
    ) {
        return switch (operation) {
            case "maintenance.status" -> CompletableFuture.completedFuture(maintenanceStatus());
            case "maintenance.start" -> startMaintenance(payload, operationId, actor, reason);
            case "maintenance.cancel" -> cancelMaintenance(reason, true);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Unsupported maintenance operation " + operation));
        };
    }

    public CompletableFuture<Map<String, Object>> event(
            String operation,
            Map<String, Object> payload,
            String actor,
            String reason
    ) {
        return switch (operation) {
            case "event.list" -> listEvents();
            case "event.create" -> createEvent(payload, actor, reason);
            case "event.cancel" -> cancelEvent(payload, actor, reason);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Unsupported event operation " + operation));
        };
    }

    private CompletableFuture<Map<String, Object>> startIncident(
            Map<String, Object> payload,
            String operationId,
            String actor,
            String reason
    ) {
        if (incident.get() != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "An incident is already active; resolve it before starting another"));
        }
        String type = boundedText(payload.get("type"), 32, "type", true).toLowerCase(Locale.ROOT);
        List<String> channelIds = configuredChannelIds("cloud-control.incident.channel-ids");
        if (payload.get("channelIds") instanceof List<?> requested) {
            List<String> safe = requested.stream().map(OperationsCoordinator::text)
                    .filter(value -> value.matches("[0-9]{15,22}"))
                    .distinct().limit(25).toList();
            if (!safe.isEmpty()) channelIds = safe;
        }
        boolean pauseChat = booleanValue(payload.get("pauseChatBridge"), true);
        IncidentState created = new IncidentState(operationId, type, reason, actor,
                List.copyOf(channelIds), pauseChat, System.currentTimeMillis());
        List<String> immutableChannels = channelIds;
        return repository.putState("incident:active", MiniJson.write(incidentMap(created)),
                        System.currentTimeMillis())
                .thenCompose(ignored -> channels.lockConfiguredChannels(
                        immutableChannels, operationId, actor, "Incident " + type + ": " + reason))
                .thenApply(changed -> {
                    incident.set(created);
                    refreshChatPause();
                    notifyDiscord("🚨 Incident Mode · " + type.toUpperCase(Locale.ROOT),
                            reason + "\n\nCoreDSC locked " + changed.size()
                                    + " configured channel(s) and opened a recoverable incident timeline.",
                            new Color(0xED4245));
                    return Map.of("active", true, "incidentId", operationId,
                            "type", type, "channels", changed, "startedAt", created.startedAt());
                }).exceptionallyCompose(error -> rollbackFailedIncidentStart(
                        created, immutableChannels, operationId, error));
    }


    private <T> CompletableFuture<T> rollbackFailedIncidentStart(
            IncidentState created,
            List<String> channelIds,
            String operationId,
            Throwable originalError
    ) {
        return channels.restoreConfiguredChannelsStrict(
                        channelIds,
                        "Incident start failed; restoring operation-owned channel state",
                        operationId)
                .handle((ignored, rollbackError) -> rollbackError)
                .thenCompose(rollbackError -> {
                    if (rollbackError != null) {
                        return preserveFailedIncident(created, originalError, rollbackError);
                    }
                    return repository.removeState("incident:active")
                            .handle((ignored, cleanupError) -> cleanupError)
                            .thenCompose(cleanupError -> {
                                if (cleanupError != null) {
                                    return preserveFailedIncident(created, originalError, cleanupError);
                                }
                                return CompletableFuture.failedFuture(originalError);
                            });
                });
    }

    private <T> CompletableFuture<T> preserveFailedIncident(
            IncidentState created,
            Throwable originalError,
            Throwable recoveryError
    ) {
        originalError.addSuppressed(unwrap(recoveryError));
        incident.compareAndSet(null, created);
        refreshChatPause();
        plugin.getLogger().severe("[Cloud] Incident startup failed and rollback was incomplete; "
                + "the persisted incident remains active for an authorized restoration retry.");
        return CompletableFuture.failedFuture(originalError);
    }

    private CompletableFuture<Map<String, Object>> resolveIncident(
            Map<String, Object> payload,
            String reason
    ) {
        IncidentState current = incident.get();
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No incident is active"));
        }
        String summary = boundedText(payload.get("summary"), 1_000, "summary", false);
        if (summary.isBlank()) summary = reason;
        String finalSummary = summary;
        return channels.restoreConfiguredChannelsStrict(current.channelIds(),
                        "Incident resolved: " + finalSummary, current.id())
                .thenCompose(restored -> repository.removeState("incident:active")
                        .thenApply(ignored -> restored))
                .thenApply(restored -> {
                    incident.compareAndSet(current, null);
                    refreshChatPause();
                    notifyDiscord("✅ Incident resolved", finalSummary
                                    + "\n\nCoreDSC attempted exact restoration for " + restored.size() + " channel(s).",
                            new Color(0x35D08A));
                    return Map.of("active", false, "incidentId", current.id(),
                            "summary", finalSummary, "restoration", restored);
                });
    }

    private CompletableFuture<Map<String, Object>> startMaintenance(
            Map<String, Object> payload,
            String operationId,
            String actor,
            String reason
    ) {
        if (maintenance.get() != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Maintenance is already scheduled or active"));
        }
        int minutes = integer(payload.get("startsInMinutes"), 1, 1_440, "startsInMinutes");
        String message = boundedText(payload.get("message"), 500, "message", false);
        if (message.isBlank()) message = reason;
        long startsAt = System.currentTimeMillis() + minutes * 60_000L;
        MaintenanceState created = new MaintenanceState(operationId, startsAt, message,
                booleanValue(payload.get("blockNewJoins"), true),
                booleanValue(payload.get("pauseChatBridge"), true),
                booleanValue(payload.get("createDiscordEvent"), true), actor);
        final String scheduledMessage = message;
        final long scheduledAt = startsAt;
        return repository.putState("maintenance:active", MiniJson.write(maintenanceMap(created)),
                        System.currentTimeMillis())
                .thenCompose(ignored -> createMaintenanceEvent(created).handle((eventId, error) -> eventId))
                .thenApply(eventId -> {
                    maintenance.set(created);
                    scheduleMaintenance(created);
                    notifyDiscord("🔧 Maintenance scheduled",
                            scheduledMessage + "\n\nStarts <t:" + scheduledAt / 1_000L + ":R>.",
                            new Color(0xF7B84B));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("maintenanceId", created.id());
                    result.put("startsAt", scheduledAt);
                    result.put("message", scheduledMessage);
                    result.put("discordEventId", eventId == null ? "" : eventId);
                    return Map.copyOf(result);
                }).exceptionallyCompose(error -> repository.removeState("maintenance:active")
                        .thenCompose(ignored -> CompletableFuture.failedFuture(error)));
    }

    private CompletableFuture<Map<String, Object>> cancelMaintenance(String reason, boolean announce) {
        MaintenanceState current = maintenance.get();
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No maintenance is scheduled"));
        }
        return repository.removeState("maintenance:active").thenApply(ignored -> {
            if (!maintenance.compareAndSet(current, null)) {
                throw new IllegalStateException("Maintenance state changed while cancellation was being persisted");
            }
            cancelTasks();
            refreshChatPause();
            if (announce) notifyDiscord("✅ Maintenance complete",
                    reason, new Color(0x35D08A));
            broadcast("§aMaintenance ended. §7The server is accepting players normally.");
            return Map.of("cancelled", true, "maintenanceId", current.id());
        });
    }

    private void scheduleMaintenance(MaintenanceState state) {
        cancelTasks();
        long remainingMillis = state.startsAt() - System.currentTimeMillis();
        int[] reminders = {60, 15, 5, 1};
        for (int minutes : reminders) {
            long delay = remainingMillis - minutes * 60_000L;
            if (delay <= 0L) continue;
            scheduledTasks.add(plugin.getCoreScheduler().runGlobalLater(() -> broadcast(
                    "§6Maintenance starts in §e" + minutes + " minute(s)§6. §7" + state.message()),
                    millisToTicks(delay)));
        }
        scheduledTasks.add(plugin.getCoreScheduler().runGlobalLater(() -> activateMaintenance(state),
                millisToTicks(Math.max(0L, remainingMillis))));
    }

    private void activateMaintenance(MaintenanceState state) {
        if (!active || maintenance.get() != state) return;
        refreshChatPause();
        broadcast("§6Maintenance is now active. §7" + state.message());
        notifyDiscord("🔧 Maintenance started", state.message(), new Color(0xF7B84B));
    }

    private CompletableFuture<String> createMaintenanceEvent(MaintenanceState state) {
        if (!state.createDiscordEvent()) return CompletableFuture.completedFuture("");
        Guild guild = guild();
        if (guild == null || !guild.getSelfMember().hasPermission(Permission.MANAGE_EVENTS)) {
            return CompletableFuture.completedFuture("");
        }
        OffsetDateTime start = OffsetDateTime.ofInstant(Instant.ofEpochMilli(state.startsAt()), ZoneOffset.UTC);
        OffsetDateTime end = start.plusHours(2);
        return guild.createScheduledEvent("Server maintenance", "Minecraft server", start, end)
                .setDescription(state.message())
                .submit().thenApply(event -> event.getId());
    }

    private CompletableFuture<Map<String, Object>> createEvent(
            Map<String, Object> payload,
            String actor,
            String reason
    ) {
        Guild guild = requireGuild();
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_EVENTS)) {
            return CompletableFuture.failedFuture(new SecurityException(
                    "The Discord bot needs Manage Events"));
        }
        String title = boundedText(payload.get("title"), 100, "title", true);
        String description = boundedText(payload.get("description"), 1_000, "description", true);
        String startText = boundedText(payload.get("startsAt"), 80, "startsAt", true);
        OffsetDateTime start;
        try {
            start = java.time.LocalDateTime.parse(startText).atOffset(ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException error) {
            try {
                start = OffsetDateTime.parse(startText);
            } catch (java.time.format.DateTimeParseException second) {
                throw new IllegalArgumentException("startsAt must be an ISO-8601 timestamp", second);
            }
        }
        if (start.toInstant().isBefore(Instant.now().plusSeconds(60))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Scheduled event must start at least one minute in the future"));
        }
        OffsetDateTime end = start.plusHours(2);
        long startsAtMillis = start.toInstant().toEpochMilli();
        String auditReason = truncate("CoreDSC · " + actor + " · " + reason, 500);
        final String eventTitle = title;
        final String eventDescription = description;
        final OffsetDateTime scheduledStart = start;
        final long scheduledAtMillis = startsAtMillis;
        return guild.createScheduledEvent(eventTitle, "Minecraft server", scheduledStart, end)
                .setDescription(eventDescription)
                .reason(auditReason)
                .submit().thenCompose(event -> repository.putState("event:" + event.getId(), MiniJson.write(Map.of(
                        "id", event.getId(), "title", eventTitle, "startsAt", scheduledStart.toString(),
                        "startsAtEpochMillis", scheduledAtMillis,
                        "description", eventDescription)), System.currentTimeMillis())
                        .thenApply(ignored -> {
                            scheduleEventReminders(event.getId(), eventTitle, eventDescription, scheduledAtMillis);
                            return Map.<String, Object>of("eventId", event.getId(), "title", eventTitle,
                                    "startsAt", scheduledStart.toString(), "minecraftReminders", true);
                        })
                        .exceptionallyCompose(error -> event.delete()
                                .reason("CoreDSC rollback: local event persistence failed")
                                .submit()
                                .handle((ignored, rollbackError) -> {
                                    if (rollbackError != null) error.addSuppressed(unwrap(rollbackError));
                                    return null;
                                })
                                .thenCompose(ignored -> CompletableFuture.<Map<String, Object>>failedFuture(error))));
    }

    private CompletableFuture<Map<String, Object>> cancelEvent(
            Map<String, Object> payload,
            String actor,
            String reason
    ) {
        Guild guild = requireGuild();
        String eventId = boundedText(payload.get("eventId"), 22, "eventId", true);
        if (!eventId.matches("[0-9]{15,22}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("eventId must be a Discord event ID"));
        }
        String auditReason = truncate("CoreDSC · " + actor + " · " + reason, 500);
        return guild.retrieveScheduledEventById(eventId).submit()
                .thenCompose(event -> event.delete().reason(auditReason).submit())
                .thenCompose(ignored -> repository.removeState("event:" + eventId))
                .thenApply(ignored -> {
                    cancelEventTasks(eventId);
                    return Map.of("eventId", eventId, "cancelled", true);
                });
    }

    private CompletableFuture<Map<String, Object>> listEvents() {
        Guild guild = requireGuild();
        return guild.retrieveScheduledEvents().submit().thenApply(events -> Map.of("events", events.stream()
                .map(event -> Map.<String, Object>of(
                        "id", event.getId(),
                        "name", event.getName(),
                        "status", event.getStatus().name(),
                        "start", event.getStartTime().toString()))
                .toList()));
    }

    private void restoreRuntimeState() {
        repository.getState("incident:active").thenAccept(stored -> stored.ifPresent(json -> {
            try {
                Map<String, Object> value = MiniJson.parseObject(json);
                IncidentState restored = new IncidentState(
                        text(value.get("id")), text(value.get("type")), text(value.get("reason")),
                        text(value.get("actor")), strings(value.get("channelIds")),
                        booleanValue(value.get("pauseChat"), true), number(value.get("startedAt")));
                incident.set(restored);
                refreshChatPause();
                plugin.getLogger().warning("[Cloud] Restored active incident " + restored.id()
                        + "; use the dashboard to resolve and restore channel permissions.");
            } catch (RuntimeException error) {
                plugin.getLogger().severe("[Cloud] Active incident state is unreadable: " + rootMessage(error));
            }
        })).exceptionally(error -> {
            plugin.getLogger().warning("[Cloud] Could not restore incident state: " + rootMessage(error));
            return null;
        });
        repository.getState("maintenance:active").thenAccept(stored -> stored.ifPresent(json -> {
            try {
                Map<String, Object> value = MiniJson.parseObject(json);
                MaintenanceState restored = new MaintenanceState(
                        text(value.get("id")), number(value.get("startsAt")), text(value.get("message")),
                        booleanValue(value.get("gateJoins"), true), booleanValue(value.get("pauseChat"), true),
                        booleanValue(value.get("createDiscordEvent"), false), text(value.get("actor")));
                if (restored.startsAt() <= System.currentTimeMillis()) {
                    maintenance.set(restored);
                    activateMaintenance(restored);
                    plugin.getLogger().warning("[Cloud] Restored active maintenance " + restored.id()
                            + "; joins and chat policy remain enforced until an authorized cancellation.");
                } else {
                    maintenance.set(restored);
                    scheduleMaintenance(restored);
                }
            } catch (RuntimeException error) {
                plugin.getLogger().severe("[Cloud] Maintenance state is unreadable: " + rootMessage(error));
            }
        })).exceptionally(error -> {
            plugin.getLogger().warning("[Cloud] Could not restore maintenance state: " + rootMessage(error));
            return null;
        });
        repository.listStates("event:").thenAccept(states -> states.forEach((key, json) -> {
            try {
                Map<String, Object> value = MiniJson.parseObject(json);
                String eventId = text(value.get("id"));
                String title = text(value.get("title"));
                String description = text(value.get("description"));
                long startsAt = number(value.get("startsAtEpochMillis"));
                if (startsAt <= 0L) {
                    startsAt = OffsetDateTime.parse(text(value.get("startsAt"))).toInstant().toEpochMilli();
                }
                if (eventId.matches("[0-9]{15,22}") && startsAt > System.currentTimeMillis()) {
                    scheduleEventReminders(eventId, title, description, startsAt);
                } else {
                    repository.removeState(key);
                }
            } catch (RuntimeException error) {
                plugin.getLogger().warning("[Cloud] Scheduled event state '" + key
                        + "' is unreadable and was not restored: " + rootMessage(error));
            }
        })).exceptionally(error -> {
            plugin.getLogger().warning("[Cloud] Could not restore synchronized event reminders: "
                    + rootMessage(error));
            return null;
        });
    }

    private void scheduleEventReminders(
            String eventId,
            String title,
            String description,
            long startsAtMillis
    ) {
        cancelEventTasks(eventId);
        long remainingMillis = startsAtMillis - System.currentTimeMillis();
        if (remainingMillis <= 0L) return;
        List<CoreTask> tasks = new CopyOnWriteArrayList<>();
        for (int minutes : new int[]{60, 15, 5, 1}) {
            long delay = remainingMillis - minutes * 60_000L;
            if (delay <= 0L) continue;
            tasks.add(plugin.getCoreScheduler().runGlobalLater(() -> broadcast(
                    "§bEvent §f" + title + " §bstarts in §f" + minutes + " minute(s)§b. §7"
                            + description), millisToTicks(delay)));
        }
        tasks.add(plugin.getCoreScheduler().runGlobalLater(() -> {
            broadcast("§bEvent starting now: §f" + title + "§7 — " + description);
            eventTasks.remove(eventId);
            repository.removeState("event:" + eventId).exceptionally(error -> {
                plugin.getLogger().warning("[Cloud] Could not retire synchronized event state "
                        + eventId + ": " + rootMessage(error));
                return null;
            });
        }, millisToTicks(remainingMillis)));
        eventTasks.put(eventId, List.copyOf(tasks));
    }

    private void cancelEventTasks(String eventId) {
        List<CoreTask> tasks = eventTasks.remove(eventId);
        if (tasks == null) return;
        tasks.forEach(CoreTask::cancel);
    }

    private void cancelAllEventTasks() {
        eventTasks.values().forEach(tasks -> tasks.forEach(CoreTask::cancel));
        eventTasks.clear();
    }

    private void broadcast(String message) {
        for (UUID playerId : onlinePlayers.get()) {
            plugin.runForPlayer(playerId, player -> player.sendMessage(message));
        }
        plugin.runSync(() -> plugin.getServer().getConsoleSender().sendMessage(message));
    }

    private void notifyDiscord(String title, String description, Color color) {
        String channelId = plugin.getAppConfig().getString("cloud-control.operations-channel-id", "");
        if (channelId == null || !channelId.matches("[0-9]{15,22}")) {
            channelId = plugin.getAppConfig().getString("server-events.channel-id", "");
        }
        if (channelId == null || !channelId.matches("[0-9]{15,22}")) return;
        var discord = plugin.getDiscordService();
        TextChannel channel = discord == null || discord.getJda() == null
                ? null : discord.getJda().getTextChannelById(channelId);
        if (channel == null || !channel.canTalk()) return;
        channel.sendMessageEmbeds(new EmbedBuilder()
                .setTitle(truncate(title, 256))
                .setDescription(truncate(description, 4_096))
                .setColor(color)
                .setTimestamp(Instant.now())
                .setFooter("CoreDSC Operations")
                .build()).queue(ignored -> { }, error -> plugin.getLogger().warning(
                        "[Cloud] Operations announcement failed: " + rootMessage(error)));
    }

    private Map<String, Object> incidentStatus() {
        IncidentState state = incident.get();
        return state == null ? Map.of("active", false) : Map.of(
                "active", true, "incidentId", state.id(), "type", state.type(),
                "reason", state.reason(), "actor", state.actor(),
                "startedAt", state.startedAt(), "channelIds", state.channelIds(),
                "pauseChat", state.pauseChat());
    }

    private Map<String, Object> maintenanceStatus() {
        MaintenanceState state = maintenance.get();
        return state == null ? Map.of("active", false) : Map.of(
                "active", System.currentTimeMillis() >= state.startsAt(),
                "scheduled", true, "maintenanceId", state.id(),
                "startsAt", state.startsAt(), "message", state.message());
    }

    private Map<String, Object> incidentMap(IncidentState state) {
        return Map.of("id", state.id(), "type", state.type(), "reason", state.reason(),
                "actor", state.actor(), "channelIds", state.channelIds(),
                "pauseChat", state.pauseChat(), "startedAt", state.startedAt());
    }

    private Map<String, Object> maintenanceMap(MaintenanceState state) {
        return Map.of("id", state.id(), "startsAt", state.startsAt(), "message", state.message(),
                "gateJoins", state.gateJoins(), "pauseChat", state.pauseChat(),
                "createDiscordEvent", state.createDiscordEvent(), "actor", state.actor());
    }

    private void refreshChatPause() {
        IncidentState incidentState = incident.get();
        MaintenanceState maintenanceState = maintenance.get();
        boolean incidentOwnsPause = incidentState != null && incidentState.pauseChat();
        boolean maintenanceOwnsPause = maintenanceState != null && maintenanceState.pauseChat()
                && System.currentTimeMillis() >= maintenanceState.startsAt();
        setChatPaused(incidentOwnsPause || maintenanceOwnsPause);
    }

    private void setChatPaused(boolean paused) {
        ChatSyncModule chat = plugin.getModuleManager() == null ? null
                : plugin.getModuleManager().getModule(ChatSyncModule.class);
        if (chat != null) chat.setOperationsPaused(paused);
    }

    private boolean chatPaused() {
        ChatSyncModule chat = plugin.getModuleManager() == null ? null
                : plugin.getModuleManager().getModule(ChatSyncModule.class);
        return chat != null && chat.isOperationsPaused();
    }

    private void cancelTasks() {
        for (CoreTask task : scheduledTasks) task.cancel();
        scheduledTasks.clear();
    }

    private Guild requireGuild() {
        Guild guild = guild();
        if (guild == null) throw new IllegalStateException("Configured Discord guild is unavailable");
        return guild;
    }

    private Guild guild() {
        var discord = plugin.getDiscordService();
        return discord == null || discord.getJda() == null
                ? null : discord.getJda().getGuildById(discord.getConfiguredGuildId());
    }

    private List<String> configuredChannelIds(String path) {
        return plugin.getAppConfig().getStringList(path).stream()
                .map(String::trim).filter(id -> id.matches("[0-9]{15,22}"))
                .distinct().limit(25).toList();
    }

    private static long millisToTicks(long millis) {
        return Math.max(1L, (millis + 49L) / 50L);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(OperationsCoordinator::text).filter(text -> !text.isBlank()).toList();
    }

    private static int integer(Object value, int minimum, int maximum, String field) {
        long result = value instanceof Number number ? number.longValue() : -1L;
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
        return (int) result;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String boundedText(Object value, int maximum, String field, boolean required) {
        String text = text(value);
        if ((required && text.isBlank()) || text.length() > maximum || containsControl(text)) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + maximum + " characters");
        }
        return text;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean containsControl(String value) {
        return value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
