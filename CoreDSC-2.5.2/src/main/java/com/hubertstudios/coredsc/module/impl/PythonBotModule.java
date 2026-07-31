package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.event.AccountLinkedEvent;
import com.hubertstudios.coredsc.event.AccountUnlinkedEvent;
import com.hubertstudios.coredsc.event.DiscordReadyEvent;
import com.hubertstudios.coredsc.event.ReportCreateEvent;
import com.hubertstudios.coredsc.event.TicketCloseEvent;
import com.hubertstudios.coredsc.event.TicketCreateEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.scripting.BukkitEventBridge;
import com.hubertstudios.coredsc.scripting.PythonWorker;
import com.hubertstudios.coredsc.scripting.PythonWorker.CommandSpec;
import com.hubertstudios.coredsc.scripting.PythonWorker.ExecutionResult;
import com.hubertstudios.coredsc.scripting.PythonWorker.OptionSpec;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository.LinkedAccount;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

                                                                              
public final class PythonBotModule implements CoreModule, DiscordCommandContributor, Listener {
    private static final Set<String> RESERVED_COMMANDS = Set.of(
            "coredsc", "link", "unlink", "account", "ticket", "report", "case", "appeal",
            "apply", "application", "resetpassword"
    );

    private final CoreDSCPlugin plugin;
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final List<BukkitCommand> registeredMinecraftCommands = new ArrayList<>();
    private volatile Map<String, CommandSpec> commands = Map.of();
    private volatile PythonWorker worker;
    private volatile ListenerAdapter discordListener;
    private BukkitEventBridge eventBridge;
    private LinkedAccountRepository links;
    private boolean allowConsoleCommands;
    private boolean allowRoleActions;
    private boolean allowTicketActions;
    private boolean allowReportActions;
    private List<String> consoleAllowPrefixes = List.of();
    private int maximumActions;
    private int maximumCommands;
    private int maximumMessageLength;
    private int maximumExecutionsPerSecond;
    private long executionWindowSecond;
    private int executionsInWindow;

    public PythonBotModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return "python-bot"; }

    @Override
    public void enable() {
        if (plugin.getStorage() == null) throw new IllegalStateException("SQLite storage is unavailable");
        links = new LinkedAccountRepository(plugin.getStorage());
        loadSettings();

        discordListener = new ListenerAdapter() {
            @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
                CommandSpec command = commands.get(event.getName().toLowerCase(Locale.ROOT));
                if (command != null && command.platforms().contains("DISCORD")) handleDiscord(event, command);
            }
        };
        if (plugin.getDiscordService() == null) throw new IllegalStateException("Discord service is unavailable");
        plugin.getDiscordService().addEventListener(discordListener);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        eventBridge = new BukkitEventBridge(plugin,
                (eventName, data) -> publishExternalEvent(eventName, data, "bukkit-event"));
        eventBridge.registerConfigured();

        worker = new PythonWorker(plugin, plugin.getConfigManager().getBotDirectory(), snapshot ->
                plugin.runSync(() -> applySnapshot(snapshot)));
        if (plugin.getAppConfig().getBoolean("bot.auto-start", false)) {
            worker.startAsync().exceptionally(error -> {
                plugin.getLogger().warning("[Python] Developer feature unavailable: " + rootMessage(error));
                return null;
            });
        } else {
            plugin.getLogger().info("[Python] Module loaded with auto-start disabled. Use /coredsc bot start when needed.");
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (discordListener != null && plugin.getDiscordService() != null) {
            plugin.getDiscordService().removeEventListener(discordListener);
        }
        discordListener = null;
        if (eventBridge != null) eventBridge.unregisterAll();
        eventBridge = null;
        unregisterMinecraftCommands();
        commands = Map.of();
        cooldowns.clear();
        PythonWorker current = worker;
        worker = null;
        if (current != null) {
            try {
                current.stopAsync().get(5L, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                plugin.getLogger().warning("[Python] Worker shutdown was interrupted.");
            } catch (Exception error) {
                plugin.getLogger().warning("[Python] Worker did not stop cleanly during module shutdown: "
                        + rootMessage(error));
            }
        }
    }

    @Override
    public String statusDetail() {
        PythonWorker current = worker;
        String workerStatus = current == null ? "not started" : current.state() + ": " + current.detail();
        BukkitEventBridge bridge = eventBridge;
        return workerStatus + "; event bridges " + (bridge == null ? "0/0" : bridge.activeCount() + "/" + bridge.configuredCount());
    }

    public PythonWorker.State workerState() {
        PythonWorker current = worker;
        return current == null ? PythonWorker.State.STOPPED : current.state();
    }

    public String workerDetail() {
        PythonWorker current = worker;
        return current == null ? "module disabled" : current.detail();
    }

    public List<String> loadedScripts() {
        PythonWorker current = worker;
        return current == null ? List.of() : current.snapshot().scripts();
    }

    public int configuredEventBridges() {
        BukkitEventBridge bridge = eventBridge;
        return bridge == null ? 0 : bridge.configuredCount();
    }

    public int activeEventBridges() {
        BukkitEventBridge bridge = eventBridge;
        return bridge == null ? 0 : bridge.activeCount();
    }

    public CompletableFuture<PythonWorker.Snapshot> restartWorker() {
        PythonWorker current = worker;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Python module is disabled"));
        loadSettings();
        return current.restartAsync();
    }

    public CompletableFuture<Void> stopWorker() {
        PythonWorker current = worker;
        if (current == null) return CompletableFuture.completedFuture(null);
        return current.stopAsync().thenRun(() -> plugin.runSync(() -> {
            commands = Map.of();
            unregisterMinecraftCommands();
            if (plugin.getDiscordService() != null) plugin.getDiscordService().registerSlashCommands();
            if (plugin.getMetricsService() != null) plugin.getMetricsService().refreshSnapshot();
        }));
    }

    public CompletableFuture<PythonWorker.Snapshot> startWorker() {
        PythonWorker current = worker;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Python module is disabled"));
        return current.isReady() ? CompletableFuture.completedFuture(current.snapshot()) : current.startAsync();
    }

    @Override
    public List<CommandData> slashCommands() {
        List<CommandData> output = new ArrayList<>();
        for (CommandSpec command : commands.values()) {
            if (!command.platforms().contains("DISCORD")) continue;
            SlashCommandData slash = Commands.slash(command.name(), command.description());
            for (OptionSpec option : command.options()) {
                OptionType type = switch (option.type()) {
                    case "INTEGER" -> OptionType.INTEGER;
                    case "BOOLEAN" -> OptionType.BOOLEAN;
                    case "USER" -> OptionType.USER;
                    default -> OptionType.STRING;
                };
                OptionData data = new OptionData(type, option.name(), option.description(), option.required());
                if (type == OptionType.STRING) data.setMaxLength(option.maxLength());
                slash.addOptions(data);
            }
            output.add(slash);
        }
        return List.copyOf(output);
    }

    private void applySnapshot(PythonWorker.Snapshot snapshot) {
        if (worker == null) return;
        Set<String> occupiedDiscordNames = new LinkedHashSet<>();
        if (plugin.getModuleManager() != null) {
            for (CoreModule module : plugin.getModuleManager().getEnabledModulesSnapshot()) {
                if (module == this || !(module instanceof DiscordCommandContributor contributor)) continue;
                for (CommandData data : contributor.slashCommands()) {
                    occupiedDiscordNames.add(data.getName().toLowerCase(Locale.ROOT));
                }
            }
        }
        LinkedHashMap<String, CommandSpec> accepted = new LinkedHashMap<>();
        for (CommandSpec command : snapshot.commands()) {
            if (accepted.size() >= maximumCommands) {
                plugin.getLogger().warning("[Python] Command limit reached; remaining Python commands were ignored.");
                break;
            }
            if (RESERVED_COMMANDS.contains(command.name())) {
                plugin.getLogger().warning("[Python] Reserved command /" + command.name() + " was ignored.");
                continue;
            }
            if (command.platforms().contains("DISCORD") && occupiedDiscordNames.contains(command.name())) {
                plugin.getLogger().warning("[Python] Discord command /" + command.name()
                        + " conflicts with another CoreDSC module and was ignored.");
                continue;
            }
            if (accepted.putIfAbsent(command.name(), command) != null) {
                plugin.getLogger().warning("[Python] Duplicate command /" + command.name() + " was ignored.");
            }
        }
        commands = Collections.unmodifiableMap(accepted);
        unregisterMinecraftCommands();
        registerMinecraftCommands();
        if (plugin.getDiscordService() != null) plugin.getDiscordService().registerSlashCommands();
        if (plugin.getMetricsService() != null) plugin.getMetricsService().refreshSnapshot();
    }

    private void registerMinecraftCommands() {
        CommandMap map = plugin.getServer().getCommandMap();
        for (CommandSpec command : commands.values()) {
            if (!command.platforms().contains("MINECRAFT")) continue;
            BukkitCommand dynamic = new BukkitCommand(command.name(), command.description(),
                    "/" + command.name(), List.of()) {
                @Override public boolean execute(CommandSender sender, String label, String[] args) {
                    if (!testPermission(sender)) return true;
                    handleMinecraft(sender, command, args);
                    return true;
                }
            };
            if (!command.permission().isBlank()) dynamic.setPermission(command.permission());
            boolean primary = map.register("coredscpy", dynamic);
            registeredMinecraftCommands.add(dynamic);
            if (!primary) plugin.getLogger().warning("[Python] /" + command.name()
                    + " conflicts with another command; use /coredscpy:" + command.name() + ".");
        }
        plugin.getServer().getOnlinePlayers().forEach(Player::updateCommands);
    }

    private void unregisterMinecraftCommands() {
        if (registeredMinecraftCommands.isEmpty()) return;
        CommandMap map = plugin.getServer().getCommandMap();
        if (map instanceof SimpleCommandMap simple) {
            simple.getKnownCommands().entrySet().removeIf(entry ->
                    registeredMinecraftCommands.stream().anyMatch(command -> entry.getValue() == command));
        }
        for (BukkitCommand command : registeredMinecraftCommands) {
            try { command.unregister(map); }
            catch (RuntimeException error) {
                plugin.getLogger().warning("[Python] Could not unregister /" + command.getName()
                        + ": " + rootMessage(error));
            }
        }
        registeredMinecraftCommands.clear();
        plugin.getServer().getOnlinePlayers().forEach(Player::updateCommands);
    }

    private void handleMinecraft(CommandSender sender, CommandSpec command, String[] args) {
        if (!command.permission().isBlank() && !sender.hasPermission(command.permission())) {
            sender.sendMessage("§cYou do not have permission.");
            return;
        }
        String cooldownIdentity = sender instanceof Player player ? player.getUniqueId().toString() : "console";
        Player player = sender instanceof Player online ? online : null;
        CompletableFuture<Optional<LinkedAccount>> linkFuture = player == null
                ? CompletableFuture.completedFuture(Optional.empty())
                : links.findByMinecraftUuid(player.getUniqueId().toString());
        linkFuture.thenCompose(link -> {
            if (command.linkedOnly() && link.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Link your Discord account first."));
            }
            long claim = claimCommandExecution(command, cooldownIdentity);
            if (claim < 0L) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Python command rate limit reached. Try again shortly."));
            }
            if (claim > 0L) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Please wait " + Math.max(1L, (claim + 999L) / 1000L) + " second(s)."));
            }
            return plugin.callSync(() -> minecraftContext(sender, player, link, args));
        }).thenCompose(context -> executeCommand(command, context))
          .whenComplete((reply, error) -> plugin.runSync(() -> sender.sendMessage(error == null
                  ? TextUtil.colorize(reply.isBlank() ? "&aPython command completed." : reply)
                  : "§cPython command failed: " + rootMessage(error))));
    }

    private void handleDiscord(SlashCommandInteractionEvent event, CommandSpec command) {
        try {
            if (command.guildOnly() && !event.isFromGuild()) {
                event.reply("This command is guild-only.").setEphemeral(true)
                        .queue(ignored -> { }, error -> plugin.getLogger().warning(
                                "[Python] Could not reject a guild-only command: " + rootMessage(error)));
                return;
            }
            if (!hasRole(event.getMember(), command.allowedRoleIds())) {
                event.reply("You do not have a required role.").setEphemeral(true)
                        .queue(ignored -> { }, error -> plugin.getLogger().warning(
                                "[Python] Could not reject a role-restricted command: " + rootMessage(error)));
                return;
            }
            event.deferReply(command.ephemeral()).queue(hook ->
                    links.findByDiscordUserId(event.getUser().getId()).thenCompose(link -> {
                        if (command.linkedOnly() && link.isEmpty()) {
                            return CompletableFuture.failedFuture(new IllegalStateException("Link your Minecraft account first."));
                        }
                        long claim = claimCommandExecution(command, event.getUser().getId());
                        if (claim < 0L) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "Python command rate limit reached. Try again shortly."));
                        }
                        if (claim > 0L) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "Please wait before using this command again."));
                        }
                        return plugin.callSync(() -> discordContext(event, link));
                    }).thenCompose(context -> executeCommand(command, context))
                      .whenComplete((reply, error) -> {
                          String message = error == null
                                  ? (reply.isBlank() ? "Python command completed." : reply)
                                  : "Python command failed: " + rootMessage(error);
                          try {
                              hook.editOriginal(TextUtil.truncate(
                                              TextUtil.sanitizeMassMentions(message), maximumMessageLength))
                                      .setAllowedMentions(Collections.emptyList())
                                      .queue(ignored -> { }, editError -> plugin.getLogger().warning(
                                              "[Python] Could not edit a Discord command reply: "
                                                      + rootMessage(editError)));
                          } catch (RuntimeException editError) {
                              plugin.getLogger().warning("[Python] Could not edit a Discord command reply: "
                                      + rootMessage(editError));
                          }
                      }), deferError -> plugin.getLogger().warning(
                            "[Python] Could not acknowledge a Discord command: " + rootMessage(deferError)));
        } catch (RuntimeException error) {
            plugin.getLogger().warning("[Python] Could not handle Discord command /" + command.name()
                    + ": " + rootMessage(error));
        }
    }

    private CompletableFuture<String> executeCommand(CommandSpec command, Map<String, Object> context) {
        PythonWorker current = worker;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Python worker is disabled"));
        return current.execute("command", command.handler(), "", context)
                .thenCompose(result -> executeActions(result, context))
                .thenApply(reply -> {
                    plugin.recordFeatureUse("python_execution");
                    return reply;
                });
    }

    private CompletableFuture<String> executeActions(ExecutionResult result, Map<String, Object> context) {
        List<Map<String, Object>> actions = result.actions();
        if (actions.size() > maximumActions) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Python script returned too many actions (maximum " + maximumActions + ")"));
        }
        AtomicReference<String> reply = new AtomicReference<>("");
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Map<String, Object> action : actions) {
            chain = chain.thenCompose(ignored -> executeAction(action, context, reply));
        }
        return chain.thenApply(ignored -> reply.get());
    }

    private CompletableFuture<Void> executeAction(
            Map<String, Object> action,
            Map<String, Object> context,
            AtomicReference<String> reply
    ) {
        String type = text(action.get("type")).toUpperCase(Locale.ROOT);
        switch (type) {
            case "REPLY" -> {
                reply.set(TextUtil.truncate(text(action.get("message")), maximumMessageLength));
                return CompletableFuture.completedFuture(null);
            }
            case "LOG" -> {
                String message = TextUtil.truncate(text(action.get("message")), 2000);
                if (text(action.get("level")).equalsIgnoreCase("WARNING")) plugin.getLogger().warning("[Python] " + message);
                else plugin.getLogger().info("[Python] " + message);
                return CompletableFuture.completedFuture(null);
            }
            case "MINECRAFT_BROADCAST" -> {
                String message = TextUtil.colorize(TextUtil.truncate(text(action.get("message")), 2000));
                return plugin.callSync(() -> { Bukkit.broadcastMessage(message); return null; });
            }
            case "PLAYER_MESSAGE" -> {
                String uuidText = text(action.get("player_uuid"));
                if (uuidText.isBlank()) uuidText = contextPlayerUuid(context);
                UUID uuid = parseUuid(uuidText);
                if (uuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("PLAYER_MESSAGE requires a valid player UUID"));
                String message = TextUtil.colorize(TextUtil.truncate(text(action.get("message")), 2000));
                return plugin.callSync(() -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) player.sendMessage(message);
                    return null;
                });
            }
            case "DISCORD_SEND" -> {
                String channelId = text(action.get("channel_id"));
                String message = TextUtil.truncate(
                        TextUtil.sanitizeMassMentions(text(action.get("message"))), maximumMessageLength);
                if (!TextUtil.isPositiveSnowflake(channelId)) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException(
                            "DISCORD_SEND requires a valid Discord channel ID"));
                }
                if (message.isBlank()) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException(
                            "DISCORD_SEND requires a non-blank message"));
                }
                boolean durable = bool(action.get("durable"), false);
                if (durable) {
                    DeliveryQueueModule queue = module(DeliveryQueueModule.class);
                    if (queue == null) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "DISCORD_SEND requested durable delivery, but delivery-queue is disabled"));
                    }
                    String dedupeKey = text(action.get("dedupe_key"));
                    if (dedupeKey.isBlank()) dedupeKey = null;
                    return queue.enqueue(channelId, message, 50, dedupeKey).thenApply(ignored -> null);
                }
                JDA jda = plugin.getDiscordService() == null ? null : plugin.getDiscordService().getJda();
                MessageChannel channel = jda == null ? null : messageChannel(jda, channelId);
                if (channel == null) return CompletableFuture.failedFuture(new IllegalStateException("Discord channel is unavailable"));
                return channel.sendMessage(message).setAllowedMentions(Collections.emptyList()).submit().thenApply(ignored -> null);
            }
            case "ADD_DISCORD_ROLE", "REMOVE_DISCORD_ROLE" -> {
                if (!allowRoleActions) return CompletableFuture.failedFuture(new IllegalStateException("Python role actions are disabled"));
                return discordRole(type.equals("ADD_DISCORD_ROLE"), action, context);
            }
            case "CREATE_TICKET" -> {
                if (!allowTicketActions) return CompletableFuture.failedFuture(new IllegalStateException("Python ticket actions are disabled"));
                TicketModule tickets = module(TicketModule.class);
                UUID uuid = parseUuid(nonBlank(text(action.get("player_uuid")), contextPlayerUuid(context)));
                if (tickets == null || uuid == null) return CompletableFuture.failedFuture(new IllegalStateException("Ticket module/player unavailable"));
                return tickets.createTicketForPlayer(uuid, text(action.get("reason")), text(action.get("message")))
                        .thenCompose(result -> result.success() ? CompletableFuture.completedFuture(null)
                                : CompletableFuture.failedFuture(new IllegalStateException(result.message())));
            }
            case "CREATE_REPORT" -> {
                if (!allowReportActions) return CompletableFuture.failedFuture(new IllegalStateException("Python report actions are disabled"));
                return createReport(action, context);
            }
            case "CONSOLE_COMMAND" -> {
                String command = text(action.get("command"));
                if (!allowConsoleCommands || !consoleAllowed(command)) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Python console action is not allowlisted"));
                }
                if (command.contains("\n") || command.contains("\r")) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Unsafe console command blocked"));
                }
                return plugin.callSync(() -> { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command); return null; });
            }
            default -> {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported Python action: " + type));
            }
        }
    }

    private CompletableFuture<Void> discordRole(boolean add, Map<String, Object> action, Map<String, Object> context) {
        String roleId = text(action.get("role_id"));
        String userId = nonBlank(text(action.get("user_id")), contextDiscordUserId(context));
        long guildId = TextUtil.parsePositiveLong(plugin.getAppConfig().get("discord.guild-id"));
        JDA jda = plugin.getDiscordService() == null ? null : plugin.getDiscordService().getJda();
        var guild = jda == null ? null : jda.getGuildById(guildId);
        var role = guild == null ? null : guild.getRoleById(roleId);
        if (guild == null || role == null || !TextUtil.isPositiveSnowflake(userId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Discord guild, role or user is unavailable"));
        }
        return guild.retrieveMemberById(userId).submit()
                .thenCompose(member -> (add ? guild.addRoleToMember(member, role)
                        : guild.removeRoleFromMember(member, role)).submit())
                .thenApply(ignored -> null);
    }

    private CompletableFuture<Void> createReport(Map<String, Object> action, Map<String, Object> context) {
        ReportModule reports = module(ReportModule.class);
        UUID reporter = parseUuid(nonBlank(text(action.get("reporter_uuid")), contextPlayerUuid(context)));
        String target = text(action.get("target"));
        if (reports == null || reporter == null || target.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Report module/reporter/target unavailable"));
        }
        return plugin.callSync(() -> {
            UUID parsed = parseUuid(target);
            if (parsed != null) return parsed;
            Player online = Bukkit.getPlayerExact(target);
            if (online != null) return online.getUniqueId();
            OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
            return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
        }).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalStateException("Unknown report target"));
            return reports.createReport(reporter, targetUuid, text(action.get("reason")), text(action.get("message")))
                    .thenCompose(result -> result.success() ? CompletableFuture.completedFuture(null)
                            : CompletableFuture.failedFuture(new IllegalStateException(result.message())));
        });
    }

    private Map<String, Object> minecraftContext(
            CommandSender sender, Player player, Optional<LinkedAccount> link, String[] args
    ) {
        Map<String, Object> context = baseContext("MINECRAFT", link);
        context.put("sender_name", sender.getName());
        context.put("args", List.of(args));
        if (player != null) context.put("player", playerMap(player));
        return context;
    }

    private Map<String, Object> discordContext(SlashCommandInteractionEvent event, Optional<LinkedAccount> link) {
        Map<String, Object> context = baseContext("DISCORD", link);
        context.put("discord_user", Map.of(
                "id", event.getUser().getId(),
                "name", event.getUser().getName(),
                "display_name", event.getMember() == null
                        ? event.getUser().getEffectiveName() : event.getMember().getEffectiveName()
        ));
        Map<String, Object> options = new LinkedHashMap<>();
        for (OptionMapping option : event.getOptions()) {
            Object value = switch (option.getType()) {
                case INTEGER -> option.getAsLong();
                case BOOLEAN -> option.getAsBoolean();
                case USER -> Map.of("id", option.getAsUser().getId(), "name", option.getAsUser().getName());
                default -> option.getAsString();
            };
            options.put(option.getName(), value);
        }
        context.put("options", options);
        return context;
    }

    private Map<String, Object> baseContext(String platform, Optional<LinkedAccount> link) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("platform", platform);
        context.put("server", Map.of(
                "name", plugin.getServer().getName(),
                "version", plugin.getServer().getVersion(),
                "online_players", plugin.getServer().getOnlinePlayers().size(),
                "max_players", plugin.getServer().getMaxPlayers()
        ));
        Map<String, Object> plugins = new LinkedHashMap<>();
        for (org.bukkit.plugin.Plugin installed : plugin.getServer().getPluginManager().getPlugins()) {
            plugins.put(installed.getName(), Map.of(
                    "enabled", installed.isEnabled(),
                    "version", installed.getDescription().getVersion()
            ));
        }
        context.put("plugins", plugins);
        link.ifPresent(account -> context.put("link", Map.of(
                "minecraft_uuid", account.minecraftUuid(),
                "minecraft_name", account.minecraftName(),
                "discord_user_id", account.discordUserId(),
                "linked_at", account.linkedAt()
        )));
        return context;
    }

    private static Map<String, Object> playerMap(Player player) {
        return Map.of(
                "uuid", player.getUniqueId().toString(),
                "name", player.getName(),
                "display_name", player.getName(),
                "world", player.getWorld().getName(),
                "online", true
        );
    }

    public CompletableFuture<Boolean> publishExternalEvent(
            String eventName,
            Map<String, ?> eventData,
            String source
    ) {
        String normalized = normalizeEventName(eventName);
        if (normalized.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Event names must match [a-z0-9][a-z0-9_.:-]{0,63}"));
        }
        PythonWorker current = worker;
        if (current == null || !current.isReady() || !current.snapshot().events().contains(normalized)) {
            return CompletableFuture.completedFuture(false);
        }
        if (!claimExecution()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Python execution rate limit reached"));
        }

        Map<String, Object> rawData = new LinkedHashMap<>();
        if (eventData != null) {
            for (Map.Entry<String, ?> entry : eventData.entrySet()) {
                String key = text(entry.getKey());
                if (!key.matches("[A-Za-z0-9_.-]{1,64}")) continue;
                rawData.put(key, entry.getValue());
                if (rawData.size() >= 50) break;
            }
        }
        Map<String, Object> frozenRawData = Collections.unmodifiableMap(rawData);
        return plugin.callSync(() -> {
            Map<String, Object> safeData = new LinkedHashMap<>();
            frozenRawData.forEach((key, value) ->
                    safeData.put(key, BukkitEventBridge.jsonSafe(value)));
            Map<String, Object> event = new LinkedHashMap<>();
                                                                             
            event.putAll(safeData);
            event.put("name", normalized);
            event.put("source", TextUtil.truncate(
                    TextUtil.singleLine(nonBlank(source, "external")), 128));
            event.put("data", Collections.unmodifiableMap(safeData));
            Map<String, Object> context = baseContext("EVENT", Optional.empty());
            context.put("event", Collections.unmodifiableMap(event));
            return context;
        }).thenCompose(context -> current.execute("event", "", normalized, context)
                .thenCompose(result -> executeActions(result, context)))
          .thenApply(ignored -> {
              plugin.recordFeatureUse("python_execution");
              return true;
          });
    }

    private void dispatchEvent(String eventName, Map<String, Object> eventData) {
        publishExternalEvent(eventName, eventData, "coredsc")
                .exceptionally(error -> {
                    plugin.getLogger().warning("[Python] Event '" + eventName + "' failed: " + rootMessage(error));
                    return false;
                });
    }

    @EventHandler public void onPlayerJoin(PlayerJoinEvent event) {
        dispatchEvent("player_join", Map.of("player", playerMap(event.getPlayer())));
    }
    @EventHandler public void onPlayerQuit(PlayerQuitEvent event) {
        dispatchEvent("player_quit", Map.of("player", playerMap(event.getPlayer())));
    }
    @EventHandler public void onAccountLinked(AccountLinkedEvent event) {
        dispatchEvent("account_linked", eventData(
                "minecraft_uuid", uuidText(event.minecraftUuid()),
                "minecraft_name", event.minecraftName(),
                "discord_user_id", event.discordUserId()));
    }
    @EventHandler public void onAccountUnlinked(AccountUnlinkedEvent event) {
        dispatchEvent("account_unlinked", eventData(
                "minecraft_uuid", uuidText(event.minecraftUuid()),
                "minecraft_name", event.minecraftName(),
                "discord_user_id", event.discordUserId()));
    }
    @EventHandler public void onTicketCreate(TicketCreateEvent event) {
        dispatchEvent("ticket_created", eventData(
                "ticket_id", event.ticketId(),
                "minecraft_uuid", uuidText(event.minecraftUuid()),
                "discord_user_id", event.discordUserId(),
                "reason", event.reason()));
    }
    @EventHandler public void onTicketClose(TicketCloseEvent event) {
        dispatchEvent("ticket_closed", eventData(
                "ticket_id", event.ticketId(), "closed_by", event.closedBy()));
    }
    @EventHandler public void onReportCreate(ReportCreateEvent event) {
        dispatchEvent("report_created", eventData(
                "report_id", event.reportId(),
                "reporter_uuid", uuidText(event.reporterUuid()),
                "target_uuid", uuidText(event.targetUuid()),
                "reason", event.reason()));
    }
    @EventHandler public void onDiscordReady(DiscordReadyEvent event) {
        dispatchEvent("discord_ready", eventData("bot_user_id", event.botUserId()));
    }

    private static Map<String, Object> eventData(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Event data requires key/value pairs");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return Collections.unmodifiableMap(values);
    }

    private static String uuidText(UUID value) {
        return value == null ? "" : value.toString();
    }

    private void loadSettings() {
        allowConsoleCommands = plugin.getAppConfig().getBoolean("bot.security.allow-console-commands", false);
        allowRoleActions = plugin.getAppConfig().getBoolean("bot.security.allow-discord-role-actions", true);
        allowTicketActions = plugin.getAppConfig().getBoolean("bot.security.allow-ticket-actions", true);
        allowReportActions = plugin.getAppConfig().getBoolean("bot.security.allow-report-actions", true);
        consoleAllowPrefixes = plugin.getAppConfig().getStringList("bot.security.console-command-allow-prefixes")
                .stream().map(value -> value.trim().toLowerCase(Locale.ROOT)).filter(value -> !value.isBlank()).toList();
        maximumActions = clamp(plugin.getAppConfig().getInt("bot.maximum-actions-per-execution", 10), 1, 100);
        maximumCommands = clamp(plugin.getAppConfig().getInt("bot.limits.maximum-registered-commands", 50), 1, 200);
        maximumMessageLength = clamp(plugin.getAppConfig().getInt("bot.limits.maximum-message-length", 1900), 1, 2000);
        maximumExecutionsPerSecond = clamp(plugin.getAppConfig().getInt("bot.limits.maximum-executions-per-second", 20), 1, 1000);
    }

    private synchronized boolean claimExecution() {
        long second = System.currentTimeMillis() / 1000L;
        if (executionWindowSecond != second) {
            executionWindowSecond = second;
            executionsInWindow = 0;
        }
        if (executionsInWindow >= maximumExecutionsPerSecond) return false;
        executionsInWindow++;
        return true;
    }

     
                                                                                   
                                                                              
                                                                                     
       
    private synchronized long claimCommandExecution(CommandSpec command, String identity) {
        long now = System.currentTimeMillis();
        long duration = Math.max(0L, command.cooldownSeconds()) * 1000L;
        String key = command.name() + ':' + identity;
        if (duration > 0L) {
            Long previous = cooldowns.get(key);
            if (previous != null && now - previous < duration) {
                return duration - (now - previous);
            }
        }

        long second = now / 1000L;
        if (executionWindowSecond != second) {
            executionWindowSecond = second;
            executionsInWindow = 0;
        }
        if (executionsInWindow >= maximumExecutionsPerSecond) {
            return -1L;
        }
        executionsInWindow++;
        if (duration > 0L) {
            cooldowns.put(key, now);
            pruneCooldowns(now);
        }
        return 0L;
    }

    private void pruneCooldowns(long now) {
        if (cooldowns.size() <= 10_000) return;
        long cutoff = now - 86_400_000L;
        cooldowns.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        if (cooldowns.size() > 20_000) cooldowns.clear();
    }

    private boolean consoleAllowed(String command) {
        String lower = command.trim().toLowerCase(Locale.ROOT);
        for (String prefix : consoleAllowPrefixes) {
            if (lower.equals(prefix) || lower.startsWith(prefix + " ")) return true;
        }
        return false;
    }

    private static boolean hasRole(Member member, List<Long> roleIds) {
        if (roleIds.isEmpty()) return true;
        if (member == null) return false;
        for (Role role : member.getRoles()) if (roleIds.contains(role.getIdLong())) return true;
        return false;
    }

    private static MessageChannel messageChannel(JDA jda, String id) {
        if (!TextUtil.isPositiveSnowflake(id)) return null;
        MessageChannel text = jda.getTextChannelById(id);
        if (text != null) return text;
        return jda.getThreadChannelById(id);
    }

    @SuppressWarnings("unchecked")
    private static String contextPlayerUuid(Map<String, Object> context) {
        Object player = context.get("player");
        if (player instanceof Map<?, ?> map) return text(map.get("uuid"));
        Object link = context.get("link");
        if (link instanceof Map<?, ?> map) return text(map.get("minecraft_uuid"));
        return "";
    }

    private static String contextDiscordUserId(Map<String, Object> context) {
        Object user = context.get("discord_user");
        if (user instanceof Map<?, ?> map) {
            String id = text(map.get("id"));
            if (!id.isBlank()) return id;
        }
        Object link = context.get("link");
        if (link instanceof Map<?, ?> map) return text(map.get("discord_user_id"));
        return "";
    }

    private <T extends CoreModule> T module(Class<T> type) {
        return plugin.getModuleManager() == null ? null : plugin.getModuleManager().getModule(type);
    }

    private static UUID parseUuid(String value) {
        try { return value == null || value.isBlank() ? null : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    private static String nonBlank(String first, String second) { return first == null || first.isBlank() ? second : first; }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool
                : value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
    private static String normalizeEventName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9][a-z0-9_.:-]{0,63}") ? normalized : "";
    }
    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
