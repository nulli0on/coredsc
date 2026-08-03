package com.hubertstudios.coredsc.cloud;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.module.ModuleManager;
import com.hubertstudios.coredsc.module.impl.ConsoleModule;
import com.hubertstudios.coredsc.module.impl.NetworkModule;
import com.hubertstudios.coredsc.scripting.MiniJson;
import com.hubertstudios.coredsc.storage.CloudOperationRepository;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.time.Instant;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Authoritative local RPC boundary. The cloud role is rechecked here, every
 * operation is a closed enum-like mapping, and mutations are restart-safe and
 * idempotent before touching Minecraft, Discord, or configuration state.
 */
public final class CloudOperationRouter {
    private static final Map<String, String> OPERATION_CAPABILITY = operationCapabilities();
    private static final Map<String, Set<String>> ROLE_CAPABILITIES = roleCapabilities();
    private static final Pattern SAFE_IDEMPOTENCY = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final Pattern HARD_DENIED_COMMAND = Pattern.compile(
            "(?i)^(?:[a-z0-9_.-]+:)?(?:stop|restart|reload|rl|op|deop|permissions|luckperms|lp|plugman|minecraft:op|minecraft:stop)(?:\\s|$)");
    private static final Pattern HARD_DENIED_COREDSSC = Pattern.compile(
            "(?i)^(?:[a-z0-9_.-]+:)?coredsc(?:\\s|$)");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "token", "password", "secret", "privatekey", "private_key", "authorization", "cookie");

    private final CoreDSCPlugin plugin;
    private final CloudOperationRepository repository;
    private final CloudConfigurationService configuration;
    private final ModerationOperationService moderation;
    private final ChannelOperationService channels;
    private final OperationsCoordinator operations;
    private final AutoModOperationService autoMod;
    private final CloudMediaStore mediaStore;
    private final Supplier<Map<String, Object>> healthSupplier;
    private final Supplier<Set<UUID>> onlinePlayers;
    private final ConcurrentHashMap<String, InFlightRequest> inFlight = new ConcurrentHashMap<>();

    public CloudOperationRouter(
            CoreDSCPlugin plugin,
            CloudOperationRepository repository,
            CloudConfigurationService configuration,
            ModerationOperationService moderation,
            ChannelOperationService channels,
            OperationsCoordinator operations,
            AutoModOperationService autoMod,
            CloudMediaStore mediaStore,
            Supplier<Map<String, Object>> healthSupplier,
            Supplier<Set<UUID>> onlinePlayers
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.moderation = Objects.requireNonNull(moderation, "moderation");
        this.channels = Objects.requireNonNull(channels, "channels");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.autoMod = Objects.requireNonNull(autoMod, "autoMod");
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore");
        this.healthSupplier = Objects.requireNonNull(healthSupplier, "healthSupplier");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
    }

    public CompletableFuture<Map<String, Object>> route(
            String operation,
            Map<String, Object> payload,
            Map<String, Object> actor,
            String idempotencyKey
    ) {
        try {
            String required = OPERATION_CAPABILITY.get(operation);
            if (required == null) throw new SecurityException(
                    "The local CoreDSC agent does not expose operation '" + operation + "'");
            if (!SAFE_IDEMPOTENCY.matcher(idempotencyKey).matches()) {
                throw new IllegalArgumentException("Invalid idempotency key");
            }
            requireSafePayload(payload, 0, new int[]{0});
            Actor verified = actor(actor);
            requireCapability(verified.role(), required);
            requireFeatureEnabled(operation);
            if (isMutation(operation) && verified.reason().length() < 3) {
                throw new IllegalArgumentException("A meaningful audit reason is required");
            }
            if (!isMutation(operation)) {
                return dispatch(operation, payload, verified, idempotencyKey);
            }
            String requestFingerprint = CloudRequestFingerprint.calculate(
                    operation, payload, verified.reason());
            return idempotent(operation, payload, verified, idempotencyKey, requestFingerprint);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletableFuture<Map<String, Object>> idempotent(
            String operation,
            Map<String, Object> payload,
            Actor actor,
            String idempotencyKey,
            String requestFingerprint
    ) {
        return repository.findResult(idempotencyKey).thenCompose(stored -> {
            if (stored.isPresent()) {
                if (!stored.get().operation().equals(operation)
                        || (!stored.get().requestFingerprint().isBlank()
                        && !stored.get().requestFingerprint().equals(requestFingerprint))) {
                    return CompletableFuture.failedFuture(new SecurityException(
                            "Idempotency key was already used for a different local request"));
                }
                try {
                    return CompletableFuture.completedFuture(MiniJson.parseObject(
                            stored.get().resultJson()));
                } catch (RuntimeException error) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "Stored local operation result is unreadable", error));
                }
            }

            CompletableFuture<Map<String, Object>> gate = new CompletableFuture<>();
            InFlightRequest candidate = new InFlightRequest(requestFingerprint, gate);
            InFlightRequest existing = inFlight.putIfAbsent(idempotencyKey, candidate);
            if (existing != null) {
                if (!existing.requestFingerprint().equals(requestFingerprint)) {
                    return CompletableFuture.failedFuture(new SecurityException(
                            "Idempotency key is already executing a different local request"));
                }
                return existing.future();
            }
            CompletableFuture<Map<String, Object>> execution;
            try {
                execution = dispatch(operation, payload, actor, idempotencyKey);
            } catch (Throwable error) {
                execution = CompletableFuture.failedFuture(error);
            }
            execution.thenCompose(result -> repository.storeResult(idempotencyKey, operation,
                            requestFingerprint, MiniJson.write(result), System.currentTimeMillis())
                    .thenApply(ignored -> result))
                    .whenComplete((result, error) -> {
                        inFlight.remove(idempotencyKey, candidate);
                        if (error == null) gate.complete(result);
                        else gate.completeExceptionally(unwrap(error));
                    });
            return gate;
        });
    }

    private CompletableFuture<Map<String, Object>> dispatch(
            String operation,
            Map<String, Object> payload,
            Actor actor,
            String operationId
    ) {
        if (operation.equals("health.snapshot")) {
            return CompletableFuture.completedFuture(Map.copyOf(healthSupplier.get()));
        }
        if (operation.equals("setup.doctor")) return configuration.doctor();
        if (operation.equals("setup.createChannels")) return configuration.createRecommendedChannels(payload);
        if (operation.equals("discord.guilds")) return configuration.snapshot()
                .thenApply(snapshot -> Map.of("guilds", snapshot.getOrDefault("guilds", List.of())));
        if (operation.equals("discord.channels")) return configuration.channels(payload);
        if (operation.equals("config.snapshot")) return configuration.snapshot();
        if (operation.equals("config.validate")) return configuration.validate(payload);
        if (operation.equals("config.patch")) return configuration.patch(payload);
        if (operation.equals("config.apply")) return configuration.apply();
        if (operation.equals("media.install")) return CompletableFuture.completedFuture(mediaStore.install(payload));
        if (operation.startsWith("moderation.")) return moderation.execute(
                operation, withReason(payload, actor.reason()), actor.discordId(), actor.displayName(), operationId);
        if (operation.startsWith("channel.")) return channels.execute(
                operation, payload, operationId, actor.displayName(), actor.reason());
        if (operation.startsWith("incident.")) return operations.incident(
                operation, payload, operationId, actor.displayName(), actor.reason());
        if (operation.startsWith("maintenance.")) return operations.maintenance(
                operation, payload, operationId, actor.displayName(), actor.reason());
        if (operation.startsWith("event.")) return operations.event(
                operation, payload, actor.displayName(), actor.reason());
        if (operation.startsWith("automod.")) return autoMod.execute(
                operation, payload, actor.displayName(), actor.reason());
        if (operation.equals("console.snapshot")) return consoleSnapshot(payload);
        if (operation.equals("console.execute")) return consoleExecute(payload, actor);
        if (operation.equals("network.snapshot")) return networkSnapshot();
        if (operation.equals("network.announce")) return networkAnnounce(payload, actor);
        if (operation.equals("network.template.validate")) return configuration.validateNetworkTemplate(payload);
        if (operation.equals("network.template.apply")) return configuration.applyNetworkTemplate(payload);
        return CompletableFuture.failedFuture(new SecurityException("Unsupported local operation"));
    }

    private CompletableFuture<Map<String, Object>> consoleSnapshot(Map<String, Object> payload) {
        ConsoleModule console = module(ConsoleModule.class);
        int limit = integer(payload.get("limit"), 1, 200, 100, "limit");
        return CompletableFuture.completedFuture(Map.of(
                "entries", console == null ? List.of() : console.cloudIncidentSnapshot(limit),
                "moduleEnabled", console != null,
                "redacted", true));
    }

    private CompletableFuture<Map<String, Object>> consoleExecute(Map<String, Object> payload, Actor actor) {
        if (!plugin.getAppConfig().getBoolean("cloud-control.console.remote-execution.enabled", false)) {
            return CompletableFuture.failedFuture(new SecurityException(
                    "Cloud console execution is disabled locally in modules/cloud-control.yml"));
        }
        String command = bounded(payload.get("command"), 256, "command", true);
        if (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isBlank() || command.indexOf('\0') >= 0 || command.indexOf('\r') >= 0
                || command.indexOf('\n') >= 0) {
            return CompletableFuture.failedFuture(new SecurityException("Console command contains invalid data"));
        }
        if (HARD_DENIED_COMMAND.matcher(command).find() || HARD_DENIED_COREDSSC.matcher(command).find()) {
            return CompletableFuture.failedFuture(new SecurityException(
                    "This command is permanently blocked from cloud execution"));
        }
        String root = commandRoot(command);
        Set<String> allowed = plugin.getAppConfig().getStringList(
                        "cloud-control.console.remote-execution.allowlisted-commands").stream()
                .map(CloudOperationRouter::commandRoot).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!allowed.contains(root)) {
            return CompletableFuture.failedFuture(new SecurityException(
                    "Command root '" + root + "' is not in the local cloud console allowlist"));
        }
        String immutableCommand = command;
        return plugin.callSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), immutableCommand))
                .thenApply(accepted -> Map.of(
                        "accepted", accepted,
                        "commandRoot", root,
                        "actor", actor.displayName(),
                        "outputCaptured", false));
    }

    private CompletableFuture<Map<String, Object>> networkSnapshot() {
        NetworkModule network = module(NetworkModule.class);
        return CompletableFuture.completedFuture(Map.of(
                "enabled", network != null,
                "connected", network != null && network.connected(),
                "serverId", network == null ? "local" : network.serverId(),
                "onlinePlayers", onlinePlayers.get().size()));
    }

    private CompletableFuture<Map<String, Object>> networkAnnounce(
            Map<String, Object> payload,
            Actor actor
    ) {
        String message = bounded(payload.get("message"), 1_000, "message", true);
        NetworkModule network = module(NetworkModule.class);
        CompletableFuture<String> origin = network == null
                ? plugin.callSync(() -> plugin.getServer().getName())
                : CompletableFuture.completedFuture(network.serverId());
        return origin.thenCompose(originName -> {
            Map<String, String> data = Map.of(
                    "origin", originName,
                    "message", message,
                    "actor", actor.displayName(),
                    "timestamp", Long.toString(System.currentTimeMillis()));
            CompletableFuture<Void> published = network == null
                    ? CompletableFuture.completedFuture(null)
                    : network.publish("CLOUD_ANNOUNCEMENT", data);
            Set<UUID> recipients = Set.copyOf(onlinePlayers.get());
            recipients.forEach(uuid -> plugin.runForPlayer(uuid,
                    player -> player.sendMessage("§b[CoreDSC] §f" + message)));
            plugin.runSync(() -> plugin.getServer().getConsoleSender().sendMessage(
                    "§b[CoreDSC Cloud] §f" + message));
            announceDiscord(message, actor);
            return published.thenApply(ignored -> Map.of(
                    "published", true,
                    "networkBus", network != null,
                    "localRecipients", recipients.size()));
        });
    }

    private void announceDiscord(String message, Actor actor) {
        String channelId = plugin.getAppConfig().getString("cloud-control.operations-channel-id", "");
        var service = plugin.getDiscordService();
        TextChannel channel = channelId == null || service == null || service.getJda() == null
                ? null : service.getJda().getTextChannelById(channelId);
        if (channel == null || !channel.canTalk()) return;
        channel.sendMessageEmbeds(new EmbedBuilder().setColor(new Color(0x5865F2))
                .setTitle("Network announcement")
                .setDescription(message)
                .setFooter("Sent by " + actor.displayName())
                .setTimestamp(Instant.now()).build()).queue(ignored -> { }, error ->
                plugin.getLogger().warning("[Cloud] Discord announcement failed: " + rootMessage(error)));
    }

    private void requireFeatureEnabled(String operation) {
        String feature = operation.substring(0, operation.indexOf('.'));
        if (Set.of("health", "setup", "discord", "config", "media").contains(feature)) return;
        if (!plugin.getAppConfig().getBoolean("cloud-control.features." + feature, true)) {
            throw new SecurityException("The local '" + feature
                    + "' cloud capability is disabled in modules/cloud-control.yml");
        }
    }

    private <T extends com.hubertstudios.coredsc.module.CoreModule> T module(Class<T> type) {
        ModuleManager modules = plugin.getModuleManager();
        return modules == null ? null : modules.getModule(type);
    }

    private static Actor actor(Map<String, Object> raw) {
        String discordId = bounded(raw.get("discordUserId"), 22, "actor.discordUserId", true);
        if (!discordId.matches("[0-9]{15,22}")) {
            throw new SecurityException("Cloud actor Discord ID is invalid");
        }
        String displayName = bounded(raw.get("displayName"), 80, "actor.displayName", true);
        String role = bounded(raw.get("role"), 30, "actor.role", true).toUpperCase(Locale.ROOT);
        if (!ROLE_CAPABILITIES.containsKey(role)) throw new SecurityException("Unknown cloud staff role");
        String reason = bounded(raw.get("reason"), 500, "actor.reason", false);
        return new Actor(discordId, displayName, role, reason);
    }

    private static void requireCapability(String role, String capability) {
        Set<String> capabilities = ROLE_CAPABILITIES.get(role);
        if (capabilities == null || (!capabilities.contains("*") && !capabilities.contains(capability))) {
            throw new SecurityException("Local policy denied capability '" + capability + "' for role " + role);
        }
    }

    private static Map<String, Object> withReason(Map<String, Object> payload, String reason) {
        if (payload.containsKey("reason") && !bounded(payload.get("reason"), 500, "reason", false).equals(reason)) {
            throw new SecurityException("Payload reason does not match the audited cloud reason");
        }
        Map<String, Object> result = new LinkedHashMap<>(payload);
        result.put("reason", reason);
        return Map.copyOf(result);
    }

    private static void requireSafePayload(Object value, int depth, int[] nodes) {
        if (depth > 10 || ++nodes[0] > 2_000) {
            throw new IllegalArgumentException("Cloud payload is too deeply nested or complex");
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return;
        if (value instanceof List<?> list) {
            for (Object item : list) requireSafePayload(item, depth + 1, nodes);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT)
                        .replace("-", "").replace(".", "");
                if (SENSITIVE_KEYS.stream().anyMatch(key::contains)) {
                    throw new SecurityException("Secret-bearing field names are rejected by the local agent");
                }
                requireSafePayload(entry.getValue(), depth + 1, nodes);
            }
            return;
        }
        throw new IllegalArgumentException("Cloud payload contains an unsupported value type");
    }

    private static boolean isMutation(String operation) {
        return !operation.endsWith(".snapshot") && !operation.endsWith(".status")
                && !operation.endsWith(".list") && !operation.endsWith(".history")
                && !operation.endsWith(".rules") && !operation.equals("discord.channels")
                && !operation.equals("discord.guilds") && !operation.equals("setup.doctor")
                && !operation.equals("config.validate") && !operation.equals("network.template.validate");
    }

    private static Map<String, String> operationCapabilities() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("health.snapshot", "server.view");
        values.put("setup.doctor", "server.manage");
        values.put("setup.createChannels", "server.manage");
        values.put("discord.guilds", "config.view");
        values.put("discord.channels", "config.view");
        values.put("config.snapshot", "config.view");
        values.put("config.validate", "config.edit");
        values.put("config.patch", "config.edit");
        values.put("config.apply", "config.apply");
        values.put("media.install", "config.edit");
        for (String action : List.of("ban", "unban", "kick", "timeout", "warn"))
            values.put("moderation." + action, "moderation.manage");
        values.put("moderation.note", "case.manage");
        values.put("moderation.history", "moderation.view");
        values.put("channel.snapshot", "channel.view");
        for (String action : List.of("lock", "unlock", "slowmode", "slowmode.restore", "purge", "hide", "reveal"))
            values.put("channel." + action, "channel.manage");
        values.put("incident.status", "incident.view");
        values.put("incident.start", "incident.manage");
        values.put("incident.resolve", "incident.manage");
        values.put("maintenance.status", "maintenance.view");
        values.put("maintenance.start", "maintenance.manage");
        values.put("maintenance.cancel", "maintenance.manage");
        values.put("event.list", "event.view");
        values.put("event.create", "event.manage");
        values.put("event.cancel", "event.manage");
        values.put("automod.rules", "automod.view");
        values.put("automod.upsert", "automod.manage");
        values.put("automod.delete", "automod.manage");
        values.put("network.snapshot", "network.view");
        values.put("network.announce", "network.manage");
        values.put("network.template.validate", "network.manage");
        values.put("network.template.apply", "network.manage");
        values.put("console.snapshot", "console.view");
        values.put("console.execute", "console.execute");
        return Map.copyOf(values);
    }

    private static Map<String, Set<String>> roleCapabilities() {
        Map<String, Set<String>> values = new LinkedHashMap<>();
        values.put("OWNER", Set.of("*"));
        values.put("SERVER_ADMIN", Set.of("server.view", "server.manage", "config.view", "config.edit",
                "config.apply", "staff.view", "moderation.view", "moderation.manage", "case.manage", "channel.view",
                "channel.manage", "incident.view", "incident.manage", "maintenance.view", "maintenance.manage",
                "event.view", "event.manage", "approval.view", "approval.decide", "automod.view",
                "automod.manage", "network.view", "network.manage", "console.view"));
        values.put("MODERATOR", Set.of("server.view", "moderation.view", "moderation.manage", "case.manage", "channel.view",
                "channel.manage", "incident.view", "incident.manage", "approval.view", "console.view"));
        values.put("CONSOLE_VIEWER", Set.of("server.view", "console.view", "incident.view"));
        values.put("CONSOLE_OPERATOR", Set.of("server.view", "console.view", "console.execute",
                "incident.view", "maintenance.view"));
        values.put("SUPPORT", Set.of("server.view", "moderation.view", "case.manage", "ticket.manage"));
        values.put("VIEWER", Set.of("server.view", "config.view", "moderation.view", "channel.view",
                "incident.view"));
        return Map.copyOf(values);
    }

    private static int integer(Object value, int minimum, int maximum, int fallback, String field) {
        long number = value instanceof Number raw ? raw.longValue() : fallback;
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
        return (int) number;
    }

    private static String bounded(Object value, int maximum, String field, boolean required) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (required && text.isBlank()) throw new IllegalArgumentException(field + " is required");
        if (text.length() > maximum) throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        return text;
    }

    private static String commandRoot(String command) {
        String value = command == null ? "" : command.trim();
        if (value.startsWith("/")) value = value.substring(1);
        int space = value.indexOf(' ');
        return (space < 0 ? value : value.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = unwrap(error);
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Actor(String discordId, String displayName, String role, String reason) { }
    private record InFlightRequest(
            String requestFingerprint,
            CompletableFuture<Map<String, Object>> future
    ) { }
}
