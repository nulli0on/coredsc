package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.event.AccountLinkedEvent;
import com.hubertstudios.coredsc.event.AccountUnlinkedEvent;
import com.hubertstudios.coredsc.event.ReportCreateEvent;
import com.hubertstudios.coredsc.event.TicketCreateEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import com.hubertstudios.coredsc.storage.WorkflowRunRepository;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import com.hubertstudios.coredsc.scheduler.CoreTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

                                                    
public final class WorkflowModule implements CoreModule {
    private final CoreDSCPlugin plugin;
    private final List<Workflow> workflows = new ArrayList<>();
    private final List<CoreTask> schedules = new ArrayList<>();
    private LinkedAccountRepository links;
    private WorkflowRunRepository runs;
    private Listener listener;
    private boolean allowConsoleActions;
    private List<String> consoleAllowPrefixes = List.of();
    private long guildId;

    public WorkflowModule(CoreDSCPlugin plugin) { this.plugin = plugin; }
    @Override public String id() { return "workflows"; }

    @Override
    public void enable() {
        SQLiteStorage storage = plugin.getStorage();
        if (storage == null || storage.getState() != SQLiteStorage.State.READY) {
            throw new IllegalStateException("SQLite storage is not ready");
        }
        links = new LinkedAccountRepository(storage);
        runs = new WorkflowRunRepository(storage);
        allowConsoleActions = plugin.getAppConfig().getBoolean("workflows.allow-console-actions", false);
        guildId = TextUtil.parsePositiveLong(plugin.getAppConfig().get("discord.guild-id"));
        consoleAllowPrefixes = plugin.getAppConfig().getStringList("workflows.console-command-allow-prefixes")
                .stream().map(v -> v.trim().toLowerCase(Locale.ROOT)).filter(v -> !v.isBlank()).toList();
        load(plugin.getAppConfig().getMapList("workflows.definitions"));

        listener = new Listener() {
            @EventHandler public void linked(AccountLinkedEvent e) {
                trigger("ACCOUNT_LINKED", mapOfPlayer(e.minecraftUuid(), e.minecraftName(), e.discordUserId()));
            }
            @EventHandler public void unlinked(AccountUnlinkedEvent e) {
                trigger("ACCOUNT_UNLINKED", mapOfPlayer(e.minecraftUuid(), e.minecraftName(), e.discordUserId()));
            }
            @EventHandler public void ticket(TicketCreateEvent e) {
                Map<String,String> values = mapOfPlayer(e.minecraftUuid(), "", e.discordUserId());
                values.put("ticket_id", Long.toString(e.ticketId())); values.put("reason", e.reason());
                trigger("TICKET_CREATED", values);
            }
            @EventHandler public void report(ReportCreateEvent e) {
                Map<String,String> values = mapOfPlayer(e.reporterUuid(), "", "");
                values.put("report_id", Long.toString(e.reportId())); values.put("target_uuid", e.targetUuid().toString());
                values.put("reason", e.reason()); trigger("REPORT_CREATED", values);
            }
            @EventHandler public void join(PlayerJoinEvent e) {
                Map<String,String> values = mapOfPlayer(e.getPlayer().getUniqueId(), e.getPlayer().getName(), "");
                values.put("first_join", Boolean.toString(!e.getPlayer().hasPlayedBefore()));
                trigger(e.getPlayer().hasPlayedBefore() ? "PLAYER_JOIN" : "PLAYER_FIRST_JOIN", values);
            }
        };
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        scheduleConfiguredWorkflows();
        if (plugin.getStartupState() == CoreDSCPlugin.StartupState.STARTING_SERVICES) {
            trigger("SERVER_START", Map.of("server_name", plugin.getServer().getName()));
        }
    }

    @Override
    public void disable() {
        if (plugin.getStartupState() == CoreDSCPlugin.StartupState.DISABLING) {
            trigger("SERVER_STOP", Map.of("server_name", plugin.getServer().getName()));
        }
        if (listener != null) HandlerList.unregisterAll(listener);
        listener = null;
        schedules.forEach(CoreTask::cancel); schedules.clear(); workflows.clear();
    }

    @Override public String statusDetail() { return workflows.size() + " workflow(s)"; }

    public void trigger(String triggerType, Map<String,String> supplied) {
        String normalized = triggerType == null ? "UNKNOWN" : triggerType.toUpperCase(Locale.ROOT);
        Map<String,String> values = new LinkedHashMap<>(supplied == null ? Map.of() : supplied);
        Runnable start = () -> enrichAccountContext(normalized, values);
        if (Bukkit.isPrimaryThread()) start.run(); else plugin.runSync(start);
    }

    private void enrichAccountContext(String normalized, Map<String,String> values) {
        addCommonValues(values);
        UUID uuid = parseUuid(values.get("minecraft_uuid"));
        if (uuid == null || !values.getOrDefault("discord_user_id", "").isBlank()) {
            triggerResolved(normalized, values);
            return;
        }
        links.findByMinecraftUuid(uuid.toString()).whenComplete((linked, error) -> plugin.runSync(() -> {
            if (error == null && linked.isPresent()) {
                values.put("discord_user_id", linked.get().discordUserId());
                values.putIfAbsent("minecraft_name", linked.get().minecraftName());
                values.put("linked", "true");
            } else {
                values.putIfAbsent("linked", "false");
            }
            triggerResolved(normalized, values);
        }));
    }

    private void triggerResolved(String normalized, Map<String,String> values) {
        addCommonValues(values);
        values.putIfAbsent("linked", Boolean.toString(!values.getOrDefault("discord_user_id", "").isBlank()));
        for (Workflow workflow : List.copyOf(workflows)) {
            if (workflow.trigger().equals(normalized)) execute(workflow, Map.copyOf(values));
        }
    }

    private void execute(Workflow workflow, Map<String,String> values) {
        UUID uuid = parseUuid(values.get("minecraft_uuid"));
        OfflinePlayer context = uuid == null ? null : Bukkit.getOfflinePlayer(uuid);
        if (!matches(workflow, values, context)) {
            log(workflow, values, "SKIPPED", "conditions not met");
            return;
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Action action : workflow.actions()) {
            chain = chain.thenCompose(ignored -> executeAction(workflow, action, values, context));
        }
        chain.whenComplete((ignored, error) -> {
            if (error == null) {
                plugin.recordFeatureUse("workflow_run");
                log(workflow, values, "SUCCESS", "");
            }
            else {
                plugin.recordModuleFailure("workflows", error);
                log(workflow, values, "FAILED", rootMessage(error));
                plugin.getLogger().log(Level.WARNING, "[Workflows] " + workflow.id() + " failed", error);
            }
        });
    }

    private boolean matches(Workflow workflow, Map<String,String> values, OfflinePlayer player) {
        Map<String,String> conditions = workflow.conditions();
        if (conditions.containsKey("server-id") && !conditions.get("server-id").equals(serverId())) return false;
        if (conditions.containsKey("first-join") && !conditions.get("first-join").equalsIgnoreCase(values.getOrDefault("first_join", "false"))) return false;
        if (conditions.containsKey("linked") && !conditions.get("linked").equalsIgnoreCase(values.getOrDefault("linked", "false"))) return false;
        if (conditions.containsKey("permission")) {
            Player online = player == null ? null : player.getPlayer();
            if (online == null || !online.hasPermission(conditions.get("permission"))) return false;
        }
        for (Map.Entry<String,String> condition : conditions.entrySet()) {
            if (!condition.getKey().startsWith("equals.")) continue;
            String key = condition.getKey().substring("equals.".length());
            if (!condition.getValue().equalsIgnoreCase(values.getOrDefault(key, ""))) return false;
        }
        return true;
    }

    private CompletableFuture<Void> executeAction(
            Workflow workflow, Action action, Map<String,String> values, OfflinePlayer player
    ) {
        return plugin.callSync(() -> new RenderedAction(
                render(action.value("message"), values, player),
                render(action.value("channel-id"), values, player),
                action.type().equals("RUN_CONSOLE_COMMAND")
                        ? renderConsoleCommand(action.value("command"), values)
                        : render(action.value("command"), values, player),
                render(action.value("role-id"), values, player),
                render(action.value("group"), values, player),
                render(action.value("reason"), values, player),
                render(action.value("event"), values, player)
        )).thenCompose(rendered -> switch (action.type()) {
            case "SEND_DISCORD_MESSAGE" -> enqueueDiscord(
                    rendered.channelId(), rendered.message(),
                    workflow.id() + ':' + values.getOrDefault("timestamp", ""));
            case "SEND_PLAYER_MESSAGE" -> plugin.callSync(() -> {
                Player online = player == null ? null : player.getPlayer();
                if (online != null) online.sendMessage(TextUtil.colorize(rendered.message()));
                return null;
            });
            case "RUN_CONSOLE_COMMAND" -> plugin.callSync(() -> {
                runConsole(rendered.command()); return null;
            });
            case "ADD_DISCORD_ROLE" -> changeDiscordRole(values, rendered.roleId(), true);
            case "REMOVE_DISCORD_ROLE" -> changeDiscordRole(values, rendered.roleId(), false);
            case "ADD_LUCKPERMS_GROUP" -> changeLuckPermsGroup(values, rendered.group(), true);
            case "REMOVE_LUCKPERMS_GROUP" -> changeLuckPermsGroup(values, rendered.group(), false);
            case "CREATE_TICKET" -> createTicket(values, rendered.reason(), rendered.message());
            case "PUBLISH_NETWORK" -> publishNetwork(rendered.event(), values);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Unsupported workflow action: " + action.type()));
        });
    }

    private CompletableFuture<Void> enqueueDiscord(String channelId, String message, String dedupe) {
        DeliveryQueueModule queue = module(DeliveryQueueModule.class);
        if (queue != null) return queue.enqueue(channelId, message, 10, dedupe).thenApply(ignored -> null);
        JDA jda = plugin.getDiscordService() == null ? null : plugin.getDiscordService().getJda();
        net.dv8tion.jda.api.entities.channel.concrete.TextChannel channel = jda == null ? null : jda.getTextChannelById(channelId);
        if (channel == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord channel unavailable"));
        return channel.sendMessage(TextUtil.truncate(TextUtil.sanitizeMassMentions(message), 2000))
                .setAllowedMentions(java.util.Collections.emptyList()).submit().thenApply(ignored -> null);
    }

    private CompletableFuture<Void> changeDiscordRole(Map<String,String> values, String roleId, boolean add) {
        String discordId = values.getOrDefault("discord_user_id", "");
        if (discordId.isBlank()) {
            UUID uuid = parseUuid(values.get("minecraft_uuid"));
            if (uuid == null) return CompletableFuture.failedFuture(new IllegalStateException("No account context"));
            return links.findByMinecraftUuid(uuid.toString()).thenCompose(link -> link.isEmpty()
                    ? CompletableFuture.failedFuture(new IllegalStateException("Account is not linked"))
                    : changeDiscordRole(link.get().discordUserId(), roleId, add));
        }
        return changeDiscordRole(discordId, roleId, add);
    }

    private CompletableFuture<Void> changeDiscordRole(String discordId, String roleId, boolean add) {
        JDA jda = plugin.getDiscordService() == null ? null : plugin.getDiscordService().getJda();
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        Role role = guild == null ? null : guild.getRoleById(roleId);
        if (guild == null || role == null) return CompletableFuture.failedFuture(new IllegalStateException("Guild/role unavailable"));
        return guild.retrieveMemberById(discordId).submit().thenCompose(member -> (add
                ? guild.addRoleToMember(member, role)
                : guild.removeRoleFromMember(member, role)).submit()).thenApply(ignored -> null);
    }

    private CompletableFuture<Void> changeLuckPermsGroup(Map<String,String> values, String group, boolean add) {
        UUID uuid = parseUuid(values.get("minecraft_uuid"));
        if (uuid == null) return CompletableFuture.failedFuture(new IllegalStateException("No Minecraft UUID context"));
        LuckPerms luckPerms;
        try { luckPerms = LuckPermsProvider.get(); }
        catch (IllegalStateException error) { return CompletableFuture.failedFuture(error); }
        return luckPerms.getUserManager().loadUser(uuid).thenCompose(user -> {
            InheritanceNode node = InheritanceNode.builder(group).build();
            if (add) user.data().add(node); else user.data().remove(node);
            return luckPerms.getUserManager().saveUser(user);
        });
    }

    private CompletableFuture<Void> createTicket(Map<String,String> values, String reason, String message) {
        UUID uuid = parseUuid(values.get("minecraft_uuid"));
        TicketModule tickets = module(TicketModule.class);
        if (uuid == null || tickets == null) return CompletableFuture.failedFuture(new IllegalStateException("Ticket module/context unavailable"));
        return tickets.createTicketForPlayer(uuid, renderRaw(reason, values), message).thenCompose(result -> result.success()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException(result.message())));
    }

    private CompletableFuture<Void> publishNetwork(String event, Map<String,String> values) {
        NetworkModule network = module(NetworkModule.class);
        return network == null ? CompletableFuture.completedFuture(null) : network.publish(event, values);
    }

    private String renderConsoleCommand(String template, Map<String,String> values) {
        Map<String,String> safe = new LinkedHashMap<>();
        putSafeName(safe, "minecraft_name", values.get("minecraft_name"));
        putSafeUuid(safe, "minecraft_uuid", values.get("minecraft_uuid"));
        putSafeSnowflake(safe, "discord_user_id", values.get("discord_user_id"));
        putSafeName(safe, "target_name", values.get("target_name"));
        putSafeUuid(safe, "target_uuid", values.get("target_uuid"));
        putSafePositiveLong(safe, "ticket_id", values.get("ticket_id"));
        putSafePositiveLong(safe, "report_id", values.get("report_id"));
        String configuredServerId = values.getOrDefault("server_id", serverId());
        if (TextUtil.isSafeIdentifier(configuredServerId)) safe.put("server_id", configuredServerId);
        return TextUtil.renderRestrictedCommand(template, safe);
    }

    private static void putSafeName(Map<String,String> values, String key, String value) {
        if (TextUtil.isSafeMinecraftName(value)) values.put(key, value);
    }
    private static void putSafeUuid(Map<String,String> values, String key, String value) {
        if (TextUtil.isUuid(value)) values.put(key, value);
    }
    private static void putSafeSnowflake(Map<String,String> values, String key, String value) {
        if (TextUtil.isPositiveSnowflake(value)) values.put(key, value);
    }
    private static void putSafePositiveLong(Map<String,String> values, String key, String value) {
        try {
            if (value != null && Long.parseLong(value) > 0L) values.put(key, value);
        } catch (NumberFormatException ignored) { }
    }

    private void runConsole(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (!allowConsoleActions || consoleAllowPrefixes.stream().noneMatch(prefix -> commandMatchesPrefix(normalized, prefix))) {
            throw new IllegalStateException("Workflow console action is not allowlisted");
        }
        if (command.contains("\n") || command.contains("\r")) throw new IllegalArgumentException("Multiline command blocked");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void load(List<Map<?,?>> configured) {
        workflows.clear();
        for (Map<?,?> raw : configured) {
            if (!bool(raw.get("enabled"), true)) continue;
            String id = text(raw.get("id"));
            String trigger = text(raw.get("trigger")).toUpperCase(Locale.ROOT);
            if (id.isBlank() || trigger.isBlank()) throw new IllegalArgumentException("Workflow requires id and trigger");
            Map<String,String> conditions = stringMap(raw.get("conditions"));
            List<Action> actions = new ArrayList<>();
            Object rawActions = raw.get("actions");
            if (rawActions instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?,?> map)) continue;
                    String type = text(map.get("type")).toUpperCase(Locale.ROOT);
                    if (type.isBlank()) throw new IllegalArgumentException("Workflow action requires type");
                    actions.add(new Action(type, stringMap(map)));
                }
            }
            if (actions.isEmpty()) throw new IllegalArgumentException("Workflow " + id + " has no actions");
            long interval = number(raw.get("interval-seconds"), 0L);
            workflows.add(new Workflow(id, trigger, conditions, List.copyOf(actions), interval));
        }
    }

    private void scheduleConfiguredWorkflows() {
        for (Workflow workflow : workflows) {
            if (!workflow.trigger().equals("SCHEDULE") || workflow.intervalSeconds() <= 0L) continue;
            long seconds = Math.max(1L, Math.min(604_800L, workflow.intervalSeconds()));
            long ticks = seconds * 20L;
            schedules.add(plugin.getCoreScheduler().runGlobalTimer(() -> {
                Map<String,String> values = new LinkedHashMap<>();
                values.put("scheduled", "true");
                addCommonValues(values);
                execute(workflow, Map.copyOf(values));
            }, ticks, ticks));
        }
    }

    private void addCommonValues(Map<String,String> values) {
        values.putIfAbsent("server_name", plugin.getServer().getName());
        values.putIfAbsent("server_id", serverId());
        values.putIfAbsent("server_version", plugin.getServer().getVersion());
        values.putIfAbsent("online_players", Integer.toString(plugin.getServer().getOnlinePlayers().size()));
        values.putIfAbsent("max_players", Integer.toString(plugin.getServer().getMaxPlayers()));
        values.putIfAbsent("online_player_names", plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName).sorted().collect(java.util.stream.Collectors.joining(", ")));
        values.putIfAbsent("timestamp", Long.toString(System.currentTimeMillis()));
    }

    private String render(String value, Map<String,String> values, OfflinePlayer player) {
        String rendered = renderRaw(value, values);
        return plugin.getPlaceholderService().apply(player, rendered);
    }
    private static String renderRaw(String value, Map<String,String> values) {
        Map<String,Object> objects = new LinkedHashMap<>(values);
        return TextUtil.replace(value == null ? "" : value, objects);
    }
    private void log(Workflow workflow, Map<String,String> values, String status, String detail) {
        runs.log(workflow.id(), workflow.trigger(), values.getOrDefault("minecraft_uuid", ""),
                status, TextUtil.truncate(detail, 500), System.currentTimeMillis()).exceptionally(error -> null);
    }
    private String serverId() {
        NetworkModule network = module(NetworkModule.class);
        return network == null ? plugin.getServer().getName() : network.serverId();
    }
    private <T extends CoreModule> T module(Class<T> type) {
        return plugin.getModuleManager() == null ? null : plugin.getModuleManager().getModule(type);
    }
    private static Map<String,String> mapOfPlayer(UUID uuid, String name, String discordId) {
        Map<String,String> values = new LinkedHashMap<>();
        values.put("minecraft_uuid", uuid.toString()); values.put("minecraft_name", name == null ? "" : name);
        values.put("discord_user_id", discordId == null ? "" : discordId); return values;
    }
    private static Map<String,String> stringMap(Object value) {
        Map<String,String> map = new LinkedHashMap<>();
        if (value instanceof Map<?,?> raw) raw.forEach((k,v) -> map.put(text(k).toLowerCase(Locale.ROOT), text(v)));
        return Map.copyOf(map);
    }
    private static UUID parseUuid(String value) {
        try { return value == null || value.isBlank() ? null : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private static boolean bool(Object value, boolean fallback) { return value == null ? fallback : Boolean.parseBoolean(value.toString()); }
    private static long number(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(text(value)); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static boolean commandMatchesPrefix(String command, String prefix) {
        return command.equals(prefix) || command.startsWith(prefix + " ");
    }
    private static String rootMessage(Throwable t) { Throwable c=t; while(c.getCause()!=null)c=c.getCause(); return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage(); }
    private record RenderedAction(String message, String channelId, String command, String roleId, String group, String reason, String event) { }
    private record Workflow(String id, String trigger, Map<String,String> conditions, List<Action> actions, long intervalSeconds) { }
    private record Action(String type, Map<String,String> values) { String value(String key) { return values.getOrDefault(key, ""); } }
}
