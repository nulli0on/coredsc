package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.api.CoreDSCApi;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.event.ReportCreateEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository.LinkedAccount;
import com.hubertstudios.coredsc.storage.ReportRepository;
import com.hubertstudios.coredsc.storage.ReportRepository.Report;
import com.hubertstudios.coredsc.storage.ReportRepository.ReserveStatus;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import com.hubertstudios.coredsc.storage.SupportMessageRepository;
import com.hubertstudios.coredsc.storage.SupportMessageRepository.SupportMessage;
import com.hubertstudios.coredsc.util.TextUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

                                                                                     
public final class ReportModule implements CoreModule, DiscordCommandContributor {
    private final CoreDSCPlugin plugin;
    private final Map<UUID, ArrayDeque<String>> recentChat = new ConcurrentHashMap<>();
    private LinkedAccountRepository links;
    private ReportRepository reports;
    private SupportMessageRepository messages;
    private Listener bukkitListener;
    private ListenerAdapter discordListener;
    private long parentChannelId;
    private boolean privateThreads;
    private boolean requireLinked;
    private boolean targetMustBeOnline;
    private boolean allowSelfReport;
    private boolean includeContext;
    private boolean captureChat;
    private int chatLines;
    private int reasonMaxLength;
    private int messageMaxLength;
    private long cooldownMillis;
    private long duplicateWindowMillis;
    private int maxOpenPerUser;
    private int maxOpenGlobal;
    private List<Long> staffRoleIds = List.of();
    private String threadNameTemplate;
    private String openingTemplate;
    private String commandName;
    private List<String> commandAliases = List.of();
    private String subCreate;
    private String subStatus;
    private String subReply;
    private String subClose;
    private String subAdmin;
    private String subClaim;
    private String subPriority;
    private String userPermission;
    private String adminPermission;
    private boolean keepDefaultCommand;
    private boolean guiEnabled;
    private int guiSize;
    private String guiTitle;
    private List<Integer> guiReportSlots = List.of();
    private List<String> guiFilters = List.of("OPEN", "CLAIMED", "CLOSED", "ALL");
    private int guiPreviousSlot;
    private int guiNextSlot;
    private int guiFilterSlot;
    private int guiRefreshSlot;
    private int guiCloseSlot;
    private int detailSize;
    private String detailTitle;
    private int detailInfoSlot;
    private int detailClaimSlot;
    private int detailPrioritySlot;
    private int detailCloseSlot;
    private int detailBackSlot;
    private final List<BukkitCommand> registeredCommands = new ArrayList<>();

    public ReportModule(CoreDSCPlugin plugin) { this.plugin = plugin; }
    @Override public String id() { return "reports"; }

    @Override
    public void enable() {
        SQLiteStorage storage = plugin.getStorage();
        if (storage == null || storage.getState() != SQLiteStorage.State.READY) throw new IllegalStateException("SQLite storage is not ready");
        requireDiscord();
        links = new LinkedAccountRepository(storage);
        reports = new ReportRepository(storage);
        messages = new SupportMessageRepository(storage);
        FileConfiguration c = plugin.getAppConfig();
        parentChannelId = readRequiredSnowflake(c, "reports.parent-channel-id");
        privateThreads = c.getBoolean("reports.private-thread", true);
        requireLinked = c.getBoolean("reports.require-linked-account", true);
        targetMustBeOnline = c.getBoolean("reports.target-must-be-online", true);
        allowSelfReport = c.getBoolean("reports.allow-self-report", false);
        includeContext = c.getBoolean("reports.include-context", true);
        captureChat = c.getBoolean("reports.chat-history.enabled", false);
        chatLines = (int) clamp(c.getLong("reports.chat-history.lines", 5L), 0L, 20L);
        reasonMaxLength = (int) clamp(c.getLong("reports.reason-max-length", 100L), 3L, 100L);
        messageMaxLength = (int) clamp(c.getLong("reports.message-max-length", 1000L), 0L, 2000L);
        cooldownMillis = clamp(c.getLong("reports.cooldown-seconds", 120L), 0L, 86_400L) * 1000L;
        duplicateWindowMillis = clamp(c.getLong("reports.duplicate-window-seconds", 60L), 0L, 3600L) * 1000L;
        maxOpenPerUser = (int) clamp(c.getLong("reports.max-open-per-user", 5L), 1L, 50L);
        maxOpenGlobal = (int) clamp(c.getLong("reports.max-open-global", 500L), 0L, 100_000L);
        staffRoleIds = readSnowflakeList(c.getList("reports.staff-role-ids"), "reports.staff-role-ids");
        threadNameTemplate = value(c, "reports.thread-name", "report-%id%-%target_name%");
        openingTemplate = value(c, "reports.opening-message",
                "**Report #%id%**\nReporter: `%reporter_name%` (<@%reporter_discord_id%>)\n" +
                        "Target: `%target_name%` (`%target_uuid%`)\nReason: **%reason%**\n\n%message%\n\n%context%\n%chat_history%");
        loadCommandAndGuiConfig(c);
        registerMinecraftCommand();
        bukkitListener = new Listener() {
            @EventHandler public void onChat(AsyncChatEvent event) {
                if (!captureChat || chatLines <= 0 || event.isCancelled()) return;
                String line = PlainTextComponentSerializer.plainText().serialize(event.message());
                ArrayDeque<String> buffer = recentChat.computeIfAbsent(event.getPlayer().getUniqueId(), ignored -> new ArrayDeque<>());
                synchronized (buffer) {
                    buffer.addLast(TextUtil.truncate(line, 300));
                    while (buffer.size() > chatLines) buffer.removeFirst();
                }
            }
            @EventHandler public void onJoin(PlayerJoinEvent event) { deliverOffline(event.getPlayer()); }
            @EventHandler public void onInventoryClick(InventoryClickEvent event) { handleGuiClick(event); }
            @EventHandler public void onInventoryClose(InventoryCloseEvent event) {
                if (event.getInventory().getHolder() instanceof ReportGuiHolder holder) holder.closed = true;
            }
        };
        plugin.getServer().getPluginManager().registerEvents(bukkitListener, plugin);
        discordListener = new ListenerAdapter() {
            @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
                if (!event.getName().equals(commandName)) return;
                String subcommand = event.getSubcommandName() == null ? "" : event.getSubcommandName();
                if (subcommand.equals(subStatus)) discordStatus(event);
                else if (subcommand.equals(subClaim)) discordClaim(event);
                else if (subcommand.equals(subReply)) discordReply(event);
                else if (subcommand.equals(subClose)) discordClose(event);
                else if (subcommand.equals(subPriority)) discordPriority(event);
                else event.reply("Unknown report subcommand.").setEphemeral(true).queue();
            }
            @Override public void onMessageReceived(MessageReceivedEvent event) { handleThreadMessage(event); }
            @Override public void onButtonInteraction(ButtonInteractionEvent event) { handleButton(event); }
        };
        plugin.getDiscordService().addEventListener(discordListener);
    }

    @Override
    public void disable() {
        if (bukkitListener != null) HandlerList.unregisterAll(bukkitListener);
        bukkitListener = null;
        if (discordListener != null && plugin.getDiscordService() != null) plugin.getDiscordService().removeEventListener(discordListener);
        discordListener = null;
        recentChat.clear();
        unregisterConfiguredCommands();
        PluginCommand command = plugin.getCommand("report");
        if (command != null) command.setExecutor((sender, cmd, label, args) -> { sender.sendMessage("§cReports are disabled."); return true; });
    }

    @Override public String statusDetail() { return "Minecraft reports to channel " + parentChannelId; }

    @Override
    public List<CommandData> slashCommands() {
        return List.of(Commands.slash(commandName, value(plugin.getAppConfig(), "reports.commands.discord-description", "View and manage Minecraft reports")).addSubcommands(
                new SubcommandData(subStatus, "Show your submitted reports"),
                new SubcommandData(subClaim, "Claim a report").addOption(OptionType.INTEGER, "id", "Report ID", true),
                new SubcommandData(subReply, "Reply to a reporter").addOptions(
                        new OptionData(OptionType.INTEGER, "id", "Report ID", true),
                        new OptionData(OptionType.STRING, "message", "Reply", true).setMaxLength(messageMaxLength)),
                new SubcommandData(subClose, "Close a report").addOption(OptionType.INTEGER, "id", "Report ID", true),
                new SubcommandData(subPriority, "Set report priority").addOptions(
                        new OptionData(OptionType.INTEGER, "id", "Report ID", true),
                        new OptionData(OptionType.STRING, "level", "LOW, NORMAL, HIGH, URGENT", true))
        ));
    }

    public CompletableFuture<CoreDSCApi.CreateResult> createReport(
            UUID reporterUuid, UUID targetUuid, String reason, String message
    ) {
        return createReport(reporterUuid, targetUuid, reason, message, false);
    }

    private CompletableFuture<CoreDSCApi.CreateResult> createReport(
            UUID reporterUuid, UUID targetUuid, String reason, String message, boolean bypassLimits
    ) {
        if (!allowSelfReport && reporterUuid.equals(targetUuid)) {
            return CompletableFuture.completedFuture(
                    new CoreDSCApi.CreateResult(false, 0L, "You cannot report yourself."));
        }
        String cleanReason = TextUtil.truncate(TextUtil.sanitizeMinecraftUserText(reason).trim(), reasonMaxLength);
        String cleanMessage = TextUtil.truncate(TextUtil.sanitizeMinecraftUserText(message).trim(), messageMaxLength);
        if (cleanReason.isBlank()) {
            return CompletableFuture.completedFuture(
                    new CoreDSCApi.CreateResult(false, 0L, "A reason is required."));
        }

        return plugin.callSync(() -> {
            Player reporter = Bukkit.getPlayer(reporterUuid);
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
            String reporterName = reporter == null ? Bukkit.getOfflinePlayer(reporterUuid).getName() : reporter.getName();
            String targetName = target.getName();
            return new ReportIdentity(
                    reporterName == null ? reporterUuid.toString() : reporterName,
                    targetName == null ? targetUuid.toString() : targetName);
        }).thenCompose(identity -> links.findByMinecraftUuid(reporterUuid.toString()).thenCompose(reporterLink -> {
            if (requireLinked && reporterLink.isEmpty()) {
                return CompletableFuture.completedFuture(
                        new CoreDSCApi.CreateResult(false, 0L, "You must link your Discord account first."));
            }
            String reporterDiscord = reporterLink.map(LinkedAccount::discordUserId).orElse("");
            return links.findByMinecraftUuid(targetUuid.toString()).thenCompose(targetLink -> reports.reserve(
                    reporterUuid.toString(), identity.reporterName(), reporterDiscord,
                    targetUuid.toString(), identity.targetName(), targetLink.map(LinkedAccount::discordUserId).orElse(""),
                    cleanReason, cleanMessage, System.currentTimeMillis(), bypassLimits ? 0L : cooldownMillis,
                    bypassLimits ? 0L : duplicateWindowMillis, bypassLimits ? Integer.MAX_VALUE : maxOpenPerUser,
                    bypassLimits ? 0 : maxOpenGlobal).thenCompose(reservation -> {
                if (reservation.status() != ReserveStatus.RESERVED) {
                    return CompletableFuture.completedFuture(new CoreDSCApi.CreateResult(
                            false, 0L, reserveMessage(reservation.status(), reservation.remainingMillis())));
                }
                return createThread(reservation.reportId(), reporterUuid, identity.reporterName(), reporterDiscord,
                        targetUuid, identity.targetName(), cleanReason, cleanMessage)
                        .thenCompose(thread -> reports.activate(reservation.reportId(), thread.getId()).thenApply(ignored -> {
                            plugin.runSync(() -> {
                                Bukkit.getPluginManager().callEvent(new ReportCreateEvent(
                                        reservation.reportId(), reporterUuid, targetUuid, cleanReason));
                                plugin.recordFeatureUse("report_created");
                            });
                            return new CoreDSCApi.CreateResult(
                                    true, reservation.reportId(), "Report #" + reservation.reportId() + " created.");
                        }).exceptionallyCompose(error -> deleteThreadQuietly(thread)
                                .thenCompose(ignored -> CompletableFuture.failedFuture(error))))
                        .exceptionallyCompose(error -> reports.release(reservation.reportId())
                                .thenCompose(ignored -> CompletableFuture.failedFuture(error)));
            }));
        }));
    }

    private void loadCommandAndGuiConfig(FileConfiguration c) {
        commandName = commandToken(value(c, "reports.commands.root", "report"), "report");
        commandAliases = c.getStringList("reports.commands.aliases").stream()
                .map(v -> commandToken(v, "")).filter(v -> !v.isBlank() && !v.equals(commandName)).distinct().toList();
        keepDefaultCommand = c.getBoolean("reports.commands.keep-default-report-command", true);
        subCreate = commandToken(value(c, "reports.commands.subcommands.create", "create"), "create");
        subStatus = commandToken(value(c, "reports.commands.subcommands.status", "status"), "status");
        subReply = commandToken(value(c, "reports.commands.subcommands.reply", "reply"), "reply");
        subClose = commandToken(value(c, "reports.commands.subcommands.close", "close"), "close");
        subAdmin = commandToken(value(c, "reports.commands.subcommands.admin", "admin"), "admin");
        subClaim = commandToken(value(c, "reports.commands.subcommands.claim", "claim"), "claim");
        subPriority = commandToken(value(c, "reports.commands.subcommands.priority", "priority"), "priority");
        userPermission = value(c, "reports.commands.permissions.user", "coredsc.report").trim();
        adminPermission = value(c, "reports.commands.permissions.admin", "coredsc.report.admin").trim();

        guiEnabled = c.getBoolean("reports.gui.enabled", true);
        guiSize = inventorySize((int) c.getLong("reports.gui.list.size", 54L));
        guiTitle = value(c, "reports.gui.list.title", "&8Reports &7- &f%filter% &8(&f%page%&8/&f%pages%&8)");
        guiReportSlots = readSlots(c.getIntegerList("reports.gui.list.report-slots"), guiSize,
                List.of(10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34));
        guiFilters = c.getStringList("reports.gui.list.filters").stream().map(v -> v.trim().toUpperCase(Locale.ROOT))
                .filter(v -> Set.of("ALL","OPEN","UNCLAIMED","CLAIMED","CLOSED","CREATING").contains(v)).distinct().toList();
        if (guiFilters.isEmpty()) guiFilters = List.of("OPEN", "CLAIMED", "CLOSED", "ALL");
        guiPreviousSlot = slot(c, "reports.gui.list.controls.previous.slot", 45, guiSize);
        guiFilterSlot = slot(c, "reports.gui.list.controls.filter.slot", 47, guiSize);
        guiRefreshSlot = slot(c, "reports.gui.list.controls.refresh.slot", 49, guiSize);
        guiCloseSlot = slot(c, "reports.gui.list.controls.close.slot", 51, guiSize);
        guiNextSlot = slot(c, "reports.gui.list.controls.next.slot", 53, guiSize);

        detailSize = inventorySize((int) c.getLong("reports.gui.detail.size", 45L));
        detailTitle = value(c, "reports.gui.detail.title", "&8Report #&f%id%");
        detailInfoSlot = slot(c, "reports.gui.detail.info.slot", 13, detailSize);
        detailClaimSlot = slot(c, "reports.gui.detail.actions.claim.slot", 29, detailSize);
        detailPrioritySlot = slot(c, "reports.gui.detail.actions.priority.slot", 31, detailSize);
        detailCloseSlot = slot(c, "reports.gui.detail.actions.close.slot", 33, detailSize);
        detailBackSlot = slot(c, "reports.gui.detail.actions.back.slot", 36, detailSize);
    }

    private void registerMinecraftCommand() {
        PluginCommand fallback = plugin.getCommand("report");
        if (fallback == null) throw new IllegalStateException("report command missing from plugin.yml");
        fallback.setExecutor((sender, cmd, label, args) -> {
            if (!keepDefaultCommand && !commandName.equals("report")) {
                sender.sendMessage(color(message("reports.messages.command-disabled", "&cThis command is disabled. Use /%command%.")
                        .replace("%command%", commandName)));
                return true;
            }
            return executeMinecraftCommand(sender, label, args);
        });
        fallback.setTabCompleter((sender, cmd, alias, args) -> tabComplete(sender, args));

        if (!commandName.equals("report") || !commandAliases.isEmpty()) {
            CommandMap map = plugin.getServer().getCommandMap();
            BukkitCommand dynamic = new BukkitCommand(commandName,
                    value(plugin.getAppConfig(), "reports.commands.description", "Report a Minecraft player"),
                    "/" + commandName, commandAliases) {
                @Override public boolean execute(CommandSender sender, String label, String[] args) {
                    return executeMinecraftCommand(sender, label, args);
                }
                @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                    return ReportModule.this.tabComplete(sender, args);
                }
            };
            map.register("coredsc", dynamic);
            registeredCommands.add(dynamic);
            plugin.getServer().getOnlinePlayers().forEach(Player::updateCommands);
        }
    }

    private boolean executeMinecraftCommand(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(message("reports.messages.players-only", "&cOnly players can use reports.")));
            return true;
        }
        if (args.length == 0) { help(player); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals(subAdmin)) {
            if (!guiEnabled) { player.sendMessage(color(message("reports.messages.gui-disabled", "&cThe report GUI is disabled."))); return true; }
            if (!adminPermission.isBlank() && !player.hasPermission(adminPermission)) { player.sendMessage(color(message("reports.messages.no-admin-permission", "&cYou cannot manage reports."))); return true; }
            openReportList(player, 0, guiFilters.get(0));
            return true;
        }
        if (!userPermission.isBlank() && !player.hasPermission(userPermission)) {
            player.sendMessage(color(message("reports.messages.no-permission", "&cYou do not have permission.")));
            return true;
        }
        if (sub.equals(subStatus)) { status(player); return true; }
        if (sub.equals(subReply)) {
            if (args.length < 3) { player.sendMessage(color(usage("reply", "/%command% %reply% <id> <message>"))); return true; }
            Long id = parseId(args[1]); if (id == null) { player.sendMessage(color(message("reports.messages.invalid-id", "&cInvalid report ID."))); return true; }
            replyFromMinecraft(player, id, join(args, 2));
            return true;
        }
        if (sub.equals(subClose)) {
            if (args.length < 2) { player.sendMessage(color(usage("close", "/%command% %close% <id>"))); return true; }
            Long id = parseId(args[1]); if (id == null) { player.sendMessage(color(message("reports.messages.invalid-id", "&cInvalid report ID."))); return true; }
            closeFromMinecraft(player, id);
            return true;
        }
        int offset = sub.equals(subCreate) ? 1 : 0;
        if (args.length - offset < 2) { player.sendMessage(color(usage("create", "/%command% [%create%] <player> <reason> [message]"))); return true; }
        Player target = Bukkit.getPlayerExact(args[offset]);
        if (target == null && targetMustBeOnline) { player.sendMessage(color(message("reports.messages.target-must-be-online", "&cThat player must be online."))); return true; }
        OfflinePlayer offline = target != null ? target : Bukkit.getOfflinePlayerIfCached(args[offset]);
        if (offline == null || (!offline.hasPlayedBefore() && !offline.isOnline())) { player.sendMessage(color(message("reports.messages.unknown-player", "&cUnknown player."))); return true; }
        String reason = args[offset + 1];
        String reportMessage = args.length > offset + 2 ? join(args, offset + 2) : "";
        createReport(player.getUniqueId(), offline.getUniqueId(), reason, reportMessage,
                player.hasPermission("coredsc.report.bypass")).whenComplete((result, error) -> plugin.runSync(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "[Reports] creation failed", error);
                player.sendMessage(color(message("reports.messages.creation-failed", "&cReport creation failed.")));
            } else {
                String key = result.success() ? "reports.messages.created" : "reports.messages.creation-rejected";
                String fallbackMessage = result.success() ? "&a%message%" : "&c%message%";
                player.sendMessage(color(message(key, fallbackMessage).replace("%message%", result.message()).replace("%id%", String.valueOf(result.id()))));
            }
        }));
        return true;
    }

    private List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of(subCreate, subStatus, subReply, subClose));
            if (guiEnabled && (adminPermission.isBlank() || sender.hasPermission(adminPermission))) base.add(subAdmin);
            for (Player player : Bukkit.getOnlinePlayers()) base.add(player.getName());
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return base.stream().distinct().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(40).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase(subCreate)) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }

    private void unregisterConfiguredCommands() {
        if (registeredCommands.isEmpty()) return;
        CommandMap map = plugin.getServer().getCommandMap();
        if (map instanceof SimpleCommandMap simple) {
            simple.getKnownCommands().entrySet().removeIf(entry -> registeredCommands.stream().anyMatch(command -> entry.getValue() == command));
        }
        for (BukkitCommand command : registeredCommands) command.unregister(map);
        registeredCommands.clear();
        plugin.getServer().getOnlinePlayers().forEach(Player::updateCommands);
    }

    private CompletableFuture<ThreadChannel> createThread(
            long id, UUID reporterUuid, String reporterName, String reporterDiscord,
            UUID targetUuid, String targetName, String reason, String message
    ) {
        JDA jda=requireReadyJda(); TextChannel parent=jda.getTextChannelById(parentChannelId);
        if(parent==null)return CompletableFuture.failedFuture(new IllegalStateException("Report parent channel unavailable"));
        return plugin.callSync(()->render(id,reporterUuid,reporterName,reporterDiscord,targetUuid,targetName,reason,message))
                .thenCompose(rendered->parent.createThreadChannel(rendered.name(),privateThreads).submit().thenCompose(thread->{
                    CompletableFuture<?> membership = reporterDiscord.isBlank()
                            ? CompletableFuture.completedFuture(null)
                            : parent.getGuild().retrieveMemberById(reporterDiscord).submit()
                                    .thenCompose(member -> thread.addThreadMember(member).submit());
                    if (!privateThreads) {
                        membership = membership.handle((ignored, error) -> null);
                    }
                    return membership.thenCompose(ignored->thread.sendMessage(rendered.message())
                                    .setAllowedMentions(java.util.Collections.emptyList())
                                    .setComponents(ActionRow.of(
                                            Button.primary("coredsc:report:claim:"+id,"Claim"),
                                            Button.secondary("coredsc:report:high:"+id,"High priority"),
                                            Button.danger("coredsc:report:close:"+id,"Close")))
                                    .submit()).thenApply(ignored->thread)
                            .exceptionallyCompose(error->deleteThreadQuietly(thread)
                                    .thenCompose(ignored->CompletableFuture.failedFuture(error)));
                }));
    }

    private Rendered render(long id,UUID reporterUuid,String reporterName,String reporterDiscord,UUID targetUuid,String targetName,String reason,String message){
        Player reporter=Bukkit.getPlayer(reporterUuid); Player target=Bukkit.getPlayer(targetUuid);
        String context="";
        if(includeContext&&target!=null){Location l=target.getLocation();context="World: `"+l.getWorld().getName()+"` | XYZ: `"+l.getBlockX()+", "+l.getBlockY()+", "+l.getBlockZ()+"` | Ping: `"+target.getPing()+"` | Mode: `"+target.getGameMode()+"`";}
        String history="";
        if(captureChat){ArrayDeque<String> buffer=recentChat.get(targetUuid);if(buffer!=null){synchronized(buffer){history=buffer.isEmpty()?"":"Recent chat:\n```\n"+String.join("\n",buffer)+"\n```";}}}
        Map<String,Object> values=new LinkedHashMap<>();values.put("id",id);values.put("reporter_name",reporterName);values.put("reporter_uuid",reporterUuid);
        values.put("reporter_discord_id",reporterDiscord);values.put("target_name",targetName);values.put("target_uuid",targetUuid);values.put("reason",reason);
        values.put("message",message.isBlank()?"No additional message.":message);values.put("context",context);values.put("chat_history",history);values.put("server_name",plugin.getServer().getName());
        String name=TextUtil.truncate(TextUtil.safeChannelToken(TextUtil.replace(threadNameTemplate,values)),100);
        String opening=plugin.getPlaceholderService().apply(reporter,TextUtil.replace(openingTemplate,values));
        return new Rendered(name,TextUtil.truncate(TextUtil.sanitizeMassMentions(opening),2000));
    }

    private void handleThreadMessage(MessageReceivedEvent event){
        if(event.getAuthor().isBot()||event.isWebhookMessage()||!(event.getChannel() instanceof ThreadChannel thread))return;
        reports.findOpenByChannel(thread.getId()).whenComplete((found,error)->{
            if(error!=null||found.isEmpty())return;
            Report report=found.get();
            String content=TextUtil.truncate(event.getMessage().getContentDisplay(),messageMaxLength);
            if(content.isBlank())return;
            if(report.reporterDiscordId().equals(event.getAuthor().getId())) {
                messages.add("REPORT",report.id(),"DISCORD",event.getAuthor().getId(),
                        event.getAuthor().getEffectiveName(),content,System.currentTimeMillis(),true,true);
                return;
            }
            if(!hasStaff(event.getMember()))return;
            deliverStaffReply(report,event.getAuthor().getId(),event.getAuthor().getEffectiveName(),content)
                    .exceptionally(deliveryError->{
                        plugin.getLogger().warning("[Reports] Could not deliver Discord reply: "+rootMessage(deliveryError));
                        return null;
                    });
        });
    }

    private void handleButton(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("coredsc:report:")) return;
        if (!hasStaff(event.getMember())) {
            event.reply("Staff role required.").setEphemeral(true).queue();
            return;
        }
        String[] parts = id.split(":");
        if (parts.length != 4) { event.reply("Invalid report action.").setEphemeral(true).queue(); return; }
        Long reportId = parseId(parts[3]);
        if (reportId == null) { event.reply("Invalid report ID.").setEphemeral(true).queue(); return; }
        String action = parts[2];
        event.deferReply(true).queue(hook -> {
            CompletableFuture<String> work = switch (action) {
                case "claim" -> reports.claim(reportId, event.getUser().getEffectiveName())
                        .thenApply(ok -> ok ? "Report claimed." : "Report is already claimed or closed.");
                case "high" -> reports.setPriority(reportId, "HIGH")
                        .thenApply(ok -> ok ? "Priority set to HIGH." : "Report not found.");
                case "close" -> reports.findById(reportId).thenCompose(found -> {
                    if (found.isEmpty()) return CompletableFuture.completedFuture("Report not found.");
                    return reports.close(reportId, event.getUser().getEffectiveName(), System.currentTimeMillis())
                            .thenApply(ok -> { if (ok) archive(found.get().channelId()); return ok ? "Report closed." : "Report is already closed."; });
                });
                default -> CompletableFuture.completedFuture("Unknown report action.");
            };
            work.whenComplete((message,error) -> edit(hook,error==null?message:"Report action failed: "+rootMessage(error)));
        });
    }

    private CompletableFuture<Void> deliverStaffReply(
            Report report, String senderId, String senderName, String content
    ) {
        String safeName=TextUtil.sanitizeMinecraftUserText(senderName);
        String safeContent=TextUtil.sanitizeMinecraftUserText(content);
        return plugin.callSync(()->Bukkit.getPlayer(UUID.fromString(report.reporterUuid())))
                .thenCompose(online->{
                    boolean delivered=online!=null;
                    return messages.add("REPORT",report.id(),"DISCORD",senderId,safeName,safeContent,
                                    System.currentTimeMillis(),delivered,true)
                            .thenCompose(messageId->online==null
                                    ? CompletableFuture.completedFuture(null)
                                    : plugin.callSync(()->{
                                        online.sendMessage("§c[Report #"+report.id()+"] §b"+safeName+"§7: §f"+safeContent);
                                        return null;
                                    }));
                });
    }

    private void deliverOffline(Player player){messages.pendingForMinecraft(player.getUniqueId().toString(),"REPORT",50).whenComplete((pending,error)->{if(error!=null||pending.isEmpty())return;
        List<SupportMessage> reportMessages=pending.stream().filter(m->m.itemType().equals("REPORT")).toList();if(reportMessages.isEmpty())return;
        plugin.runSync(()->{List<Long> ids=new ArrayList<>();for(SupportMessage m:reportMessages){player.sendMessage("§c[Report #"+m.itemId()+"] §b"+m.senderName()+"§7: §f"+m.message());ids.add(m.id());}messages.markMinecraftDelivered(ids);});});}

    private void status(Player player){reports.findOpenByReporter(player.getUniqueId().toString()).whenComplete((list,error)->plugin.runSync(()->{if(error!=null){player.sendMessage("§cCould not load reports.");return;}if(list.isEmpty()){player.sendMessage("§7No open reports.");return;}player.sendMessage("§cOpen reports:");for(Report r:list)player.sendMessage("§7- §f#"+r.id()+" §8["+r.priority()+"] §7"+r.targetName()+": "+r.reason());}));}
    private void replyFromMinecraft(Player player,long id,String content){String safe=TextUtil.truncate(TextUtil.sanitizeMinecraftUserText(content).trim(),messageMaxLength);reports.findById(id).thenCompose(found->{if(found.isEmpty()||!found.get().reporterUuid().equals(player.getUniqueId().toString())||!isOpen(found.get()))return CompletableFuture.failedFuture(new IllegalStateException("Report not found."));Report r=found.get();
        return messages.add("REPORT",id,"MINECRAFT",player.getUniqueId().toString(),player.getName(),safe,System.currentTimeMillis(),true,false).thenCompose(mid->sendThread(r.channelId(),"**"+player.getName()+" (Reporter):** "+safe).thenCompose(v->messages.markDiscordDelivered(mid)));}).whenComplete((v,e)->plugin.runSync(()->player.sendMessage(e==null?"§aReply sent.":"§c"+rootMessage(e))));}
    private void closeFromMinecraft(Player player,long id){reports.findById(id).thenCompose(found->{if(found.isEmpty()||!found.get().reporterUuid().equals(player.getUniqueId().toString()))return CompletableFuture.failedFuture(new IllegalStateException("Report not found."));return reports.close(id,player.getName(),System.currentTimeMillis()).thenApply(ok->{if(ok)archive(found.get().channelId());return ok;});}).whenComplete((ok,e)->plugin.runSync(()->player.sendMessage(e==null&&Boolean.TRUE.equals(ok)?"§aReport closed.":"§cCould not close report.")));}

    private void openReportList(Player player, int requestedPage, String requestedFilter) {
        if (!guiEnabled) return;
        String filter = normalizeFilter(requestedFilter);
        int perPage = Math.max(1, guiReportSlots.size());
        reports.countForAdmin(filter).thenCombine(
                reports.findPageForAdmin(filter, perPage, Math.max(0, requestedPage) * perPage),
                (count, pageReports) -> new AdminPage(count, pageReports)
        ).whenComplete((data, error) -> plugin.runSync(() -> {
            if (error != null) {
                player.sendMessage(color(message("reports.messages.gui-load-failed", "&cCould not load reports.")));
                return;
            }
            int pages = Math.max(1, (data.count + perPage - 1) / perPage);
            int page = Math.max(0, Math.min(requestedPage, pages - 1));
            if (page != requestedPage) { openReportList(player, page, filter); return; }
            ReportGuiHolder holder = new ReportGuiHolder(GuiView.LIST, page, filter, 0L);
            Map<String, String> values = guiValues(null, page, pages, filter);
            Inventory inventory = Bukkit.createInventory(holder, guiSize, color(replaceGui(guiTitle, values)));
            holder.inventory = inventory;
            fillBackground(inventory, "reports.gui.list.background");
            for (int i = 0; i < data.reports.size() && i < guiReportSlots.size(); i++) {
                Report report = data.reports.get(i);
                inventory.setItem(guiReportSlots.get(i), reportItem(report, page, pages, filter));
                holder.reportBySlot.put(guiReportSlots.get(i), report.id());
            }
            inventory.setItem(guiPreviousSlot, configuredItem("reports.gui.list.controls.previous", Material.ARROW,
                    "&ePrevious page", List.of("&7Page %page%/%pages%"), values));
            inventory.setItem(guiNextSlot, configuredItem("reports.gui.list.controls.next", Material.ARROW,
                    "&eNext page", List.of("&7Page %page%/%pages%"), values));
            inventory.setItem(guiFilterSlot, configuredItem("reports.gui.list.controls.filter", Material.HOPPER,
                    "&bFilter: &f%filter%", List.of("&7Click to change"), values));
            inventory.setItem(guiRefreshSlot, configuredItem("reports.gui.list.controls.refresh", Material.CLOCK,
                    "&aRefresh", List.of("&7Reload report data"), values));
            inventory.setItem(guiCloseSlot, configuredItem("reports.gui.list.controls.close", Material.BARRIER,
                    "&cClose", List.of(), values));
            player.openInventory(inventory);
        }));
    }

    private void openReportDetail(Player player, long reportId, int returnPage, String returnFilter) {
        reports.findById(reportId).whenComplete((found, error) -> plugin.runSync(() -> {
            if (error != null || found.isEmpty()) {
                player.sendMessage(color(message("reports.messages.report-not-found", "&cReport not found.")));
                openReportList(player, returnPage, returnFilter);
                return;
            }
            Report report = found.get();
            ReportGuiHolder holder = new ReportGuiHolder(GuiView.DETAIL, returnPage, returnFilter, report.id());
            Map<String, String> values = guiValues(report, returnPage, 1, returnFilter);
            Inventory inventory = Bukkit.createInventory(holder, detailSize, color(replaceGui(detailTitle, values)));
            holder.inventory = inventory;
            fillBackground(inventory, "reports.gui.detail.background");
            inventory.setItem(detailInfoSlot, configuredItem("reports.gui.detail.info", Material.BOOK,
                    "&cReport #%id%", List.of(
                            "&7Reporter: &f%reporter_name%",
                            "&7Target: &f%target_name%",
                            "&7Status: &f%status%",
                            "&7Priority: &f%priority%",
                            "&7Claimed by: &f%claimed_by%",
                            "",
                            "&7Reason: &f%reason%",
                            "&7Message: &f%message%"), values));
            inventory.setItem(detailClaimSlot, configuredItem("reports.gui.detail.actions.claim", Material.NAME_TAG,
                    "&bClaim report", List.of("&7Current: &f%claimed_by%"), values));
            inventory.setItem(detailPrioritySlot, configuredItem("reports.gui.detail.actions.priority", Material.REDSTONE_TORCH,
                    "&ePriority: &f%priority%", List.of("&7Click to cycle priority"), values));
            inventory.setItem(detailCloseSlot, configuredItem("reports.gui.detail.actions.close", Material.BARRIER,
                    "&cClose report", List.of("&7This also archives the Discord thread"), values));
            inventory.setItem(detailBackSlot, configuredItem("reports.gui.detail.actions.back", Material.ARROW,
                    "&7Back", List.of("&7Return to report list"), values));
            player.openInventory(inventory);
        }));
    }

    private void handleGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof ReportGuiHolder holder)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) return;
        if (!adminPermission.isBlank() && !player.hasPermission(adminPermission)) {
            player.closeInventory();
            return;
        }
        if (holder.view == GuiView.LIST) {
            Long reportId = holder.reportBySlot.get(event.getRawSlot());
            if (reportId != null) { openReportDetail(player, reportId, holder.page, holder.filter); return; }
            if (event.getRawSlot() == guiPreviousSlot) { openReportList(player, Math.max(0, holder.page - 1), holder.filter); return; }
            if (event.getRawSlot() == guiNextSlot) { openReportList(player, holder.page + 1, holder.filter); return; }
            if (event.getRawSlot() == guiFilterSlot) { openReportList(player, 0, nextFilter(holder.filter)); return; }
            if (event.getRawSlot() == guiRefreshSlot) { openReportList(player, holder.page, holder.filter); return; }
            if (event.getRawSlot() == guiCloseSlot) player.closeInventory();
            return;
        }
        if (holder.view == GuiView.DETAIL) {
            if (event.getRawSlot() == detailBackSlot) { openReportList(player, holder.page, holder.filter); return; }
            if (event.getRawSlot() == detailClaimSlot) {
                reports.claim(holder.reportId, player.getName()).whenComplete((ok, error) -> plugin.runSync(() -> {
                    player.sendMessage(color(ok != null && ok ? message("reports.messages.claimed", "&aReport claimed.")
                            : message("reports.messages.claim-failed", "&cCould not claim report.")));
                    openReportDetail(player, holder.reportId, holder.page, holder.filter);
                }));
                return;
            }
            if (event.getRawSlot() == detailPrioritySlot) {
                reports.findById(holder.reportId).thenCompose(found -> found.isEmpty()
                        ? CompletableFuture.completedFuture(false)
                        : reports.setPriority(holder.reportId, nextPriority(found.get().priority())))
                        .whenComplete((ok, error) -> plugin.runSync(() -> openReportDetail(player, holder.reportId, holder.page, holder.filter)));
                return;
            }
            if (event.getRawSlot() == detailCloseSlot) {
                reports.findById(holder.reportId).thenCompose(found -> found.isEmpty()
                        ? CompletableFuture.completedFuture(false)
                        : reports.close(holder.reportId, player.getName(), System.currentTimeMillis()).thenApply(ok -> {
                            if (ok) archive(found.get().channelId());
                            return ok;
                        })).whenComplete((ok, error) -> plugin.runSync(() -> {
                            player.sendMessage(color(ok != null && ok ? message("reports.messages.closed", "&aReport closed.")
                                    : message("reports.messages.close-failed", "&cCould not close report.")));
                            openReportList(player, holder.page, holder.filter);
                        }));
            }
        }
    }

    private ItemStack reportItem(Report report, int page, int pages, String filter) {
        return configuredItem("reports.gui.list.report-item", Material.PAPER,
                "&cReport #%id% &8[&f%status%&8]", List.of(
                        "&7Reporter: &f%reporter_name%",
                        "&7Target: &f%target_name%",
                        "&7Priority: &f%priority%",
                        "&7Claimed by: &f%claimed_by%",
                        "",
                        "&7Reason: &f%reason%",
                        "",
                        "&eClick to open"), guiValues(report, page, pages, filter));
    }

    private ItemStack configuredItem(String path, Material fallbackMaterial, String fallbackName, List<String> fallbackLore, Map<String, String> values) {
        Material material = material(value(plugin.getAppConfig(), path + ".material", fallbackMaterial.name()), fallbackMaterial);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = value(plugin.getAppConfig(), path + ".name", fallbackName);
            meta.setDisplayName(color(replaceGui(name, values)));
            List<String> configuredLore = plugin.getAppConfig().getStringList(path + ".lore");
            List<String> lore = configuredLore.isEmpty() ? fallbackLore : configuredLore;
            meta.setLore(lore.stream().map(line -> color(replaceGui(line, values))).toList());
            if (plugin.getAppConfig().getBoolean(path + ".glow", false)) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillBackground(Inventory inventory, String path) {
        if (!plugin.getAppConfig().getBoolean(path + ".enabled", false)) return;
        ItemStack item = configuredItem(path, Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), Map.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) if (inventory.getItem(slot) == null) inventory.setItem(slot, item);
    }

    private Map<String, String> guiValues(Report report, int page, int pages, String filter) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("page", String.valueOf(page + 1)); values.put("pages", String.valueOf(Math.max(1, pages))); values.put("filter", filter);
        values.put("command", commandName); values.put("admin", subAdmin);
        if (report != null) {
            values.put("id", String.valueOf(report.id())); values.put("reporter_name", safe(report.reporterName()));
            values.put("reporter_uuid", safe(report.reporterUuid())); values.put("target_name", safe(report.targetName()));
            values.put("target_uuid", safe(report.targetUuid())); values.put("reason", TextUtil.truncate(safe(report.reason()), 120));
            values.put("message", TextUtil.truncate(safe(report.message()), 160)); values.put("status", safe(report.status()));
            values.put("priority", safe(report.priority())); values.put("claimed_by", blank(report.claimedBy(), "Nobody"));
            values.put("closed_by", blank(report.closedBy(), "Nobody"));
        }
        return values;
    }

    private String usage(String key, String fallback) {
        return message("reports.commands.usage." + key, fallback)
                .replace("%command%", commandName).replace("%create%", subCreate).replace("%status%", subStatus)
                .replace("%reply%", subReply).replace("%close%", subClose).replace("%admin%", subAdmin);
    }

    private String message(String path, String fallback) { return value(plugin.getAppConfig(), path, fallback); }
    private static String color(String text) { return TextUtil.colorize(text == null ? "" : text); }
    private static String replaceGui(String template, Map<String, String> values) {
        String output = template == null ? "" : template;
        for (Map.Entry<String, String> entry : values.entrySet()) output = output.replace("%" + entry.getKey() + "%", entry.getValue());
        return output;
    }
    private static String commandToken(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_-]{1,32}") ? normalized : fallback;
    }
    private static int inventorySize(int value) { int clamped = Math.max(9, Math.min(54, value)); return ((clamped + 8) / 9) * 9; }
    private static int slot(FileConfiguration c, String path, int fallback, int size) { int value = (int) c.getLong(path, fallback); return value >= 0 && value < size ? value : Math.min(fallback, size - 1); }
    private static List<Integer> readSlots(List<Integer> configured, int size, List<Integer> fallback) {
        List<Integer> source = configured == null || configured.isEmpty() ? fallback : configured;
        return source.stream().filter(slot -> slot != null && slot >= 0 && slot < size).distinct().toList();
    }
    private static Material material(String name, Material fallback) { Material value = Material.matchMaterial(name == null ? "" : name.trim()); return value == null ? fallback : value; }
    private String normalizeFilter(String value) { String normalized = value == null ? "ALL" : value.toUpperCase(Locale.ROOT); return guiFilters.contains(normalized) ? normalized : guiFilters.get(0); }
    private String nextFilter(String current) { int index = guiFilters.indexOf(normalizeFilter(current)); return guiFilters.get((index + 1) % guiFilters.size()); }
    private static String nextPriority(String current) { return switch (current == null ? "NORMAL" : current.toUpperCase(Locale.ROOT)) { case "LOW" -> "NORMAL"; case "NORMAL" -> "HIGH"; case "HIGH" -> "URGENT"; default -> "LOW"; }; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    private void discordStatus(SlashCommandInteractionEvent event){event.deferReply(true).queue(hook->links.findByDiscordUserId(event.getUser().getId()).thenCompose(link->link.isEmpty()?CompletableFuture.completedFuture(List.<Report>of()):reports.findOpenByReporter(link.get().minecraftUuid())).whenComplete((list,e)->{if(e!=null){edit(hook,"Could not load reports.");return;}if(list.isEmpty()){edit(hook,"No open reports.");return;}StringBuilder b=new StringBuilder("Open reports:\n");for(Report r:list)b.append("• #").append(r.id()).append(" [").append(r.priority()).append("] ").append(r.targetName()).append(": ").append(r.reason()).append('\n');edit(hook,b.toString());}));}
    private void discordClaim(SlashCommandInteractionEvent event){if(!hasStaff(event.getMember())){event.reply("Staff role required.").setEphemeral(true).queue();return;}long id=event.getOption("id").getAsLong();event.deferReply(true).queue(h->reports.claim(id,event.getUser().getEffectiveName()).whenComplete((ok,e)->edit(h,e==null&&ok?"Report claimed.":"Could not claim report.")));}
    private void discordReply(SlashCommandInteractionEvent event){if(!hasStaff(event.getMember())){event.reply("Staff role required.").setEphemeral(true).queue();return;}long id=event.getOption("id").getAsLong();String msg=event.getOption("message").getAsString();event.deferReply(true).queue(h->reports.findById(id).thenCompose(found->{if(found.isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("Report not found"));Report r=found.get();return sendThread(r.channelId(),"**"+event.getUser().getEffectiveName()+" (Staff):** "+msg)
                .thenCompose(ignored->deliverStaffReply(r,event.getUser().getId(),event.getUser().getEffectiveName(),msg));}).whenComplete((v,e)->edit(h,e==null?"Reply delivered.":"Reply failed.")));}
    private void discordClose(SlashCommandInteractionEvent event){if(!hasStaff(event.getMember())){event.reply("Staff role required.").setEphemeral(true).queue();return;}long id=event.getOption("id").getAsLong();event.deferReply(true).queue(h->reports.findById(id).thenCompose(found->found.isEmpty()?CompletableFuture.completedFuture(false):reports.close(id,event.getUser().getEffectiveName(),System.currentTimeMillis()).thenApply(ok->{if(ok)archive(found.get().channelId());return ok;})).whenComplete((ok,e)->edit(h,e==null&&ok?"Report closed.":"Could not close report.")));}
    private void discordPriority(SlashCommandInteractionEvent event){if(!hasStaff(event.getMember())){event.reply("Staff role required.").setEphemeral(true).queue();return;}String level=event.getOption("level").getAsString().toUpperCase(Locale.ROOT);if(!List.of("LOW","NORMAL","HIGH","URGENT").contains(level)){event.reply("Invalid priority.").setEphemeral(true).queue();return;}long id=event.getOption("id").getAsLong();event.deferReply(true).queue(h->reports.setPriority(id,level).whenComplete((ok,e)->edit(h,e==null&&ok?"Priority updated.":"Report not found.")));}

    private CompletableFuture<Void> sendThread(String id,String content){ThreadChannel t=requireReadyJda().getThreadChannelById(id);if(t==null)return CompletableFuture.failedFuture(new IllegalStateException("Report thread unavailable"));return t.sendMessage(TextUtil.truncate(TextUtil.sanitizeMassMentions(content),2000)).setAllowedMentions(java.util.Collections.emptyList()).submit().thenApply(v->null);}
    private CompletableFuture<Void> deleteThreadQuietly(ThreadChannel thread){
        try{return thread.delete().submit().handle((ignored,error)->null);}
        catch(RuntimeException error){return CompletableFuture.completedFuture(null);}
    }
    private void archive(String id){JDA j=plugin.getDiscordService()==null?null:plugin.getDiscordService().getJda();ThreadChannel t=j==null?null:j.getThreadChannelById(id);if(t!=null)t.getManager().setLocked(true).setArchived(true).queue();}
    private boolean hasStaff(Member m){if(m==null)return false;if(m.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_THREADS))return true;for(Role r:m.getRoles())if(staffRoleIds.contains(r.getIdLong()))return true;return false;}
    private JDA requireReadyJda(){DiscordBotService s=requireDiscord();if(!s.isReady()||s.getJda()==null)throw new IllegalStateException("Discord is not ready");return s.getJda();}
    private DiscordBotService requireDiscord(){DiscordBotService s=plugin.getDiscordService();if(s==null)throw new IllegalStateException("Discord service not initialised");return s;}
    private void edit(InteractionHook h,String m){h.editOriginal(TextUtil.truncate(TextUtil.sanitizeMassMentions(m),2000)).setAllowedMentions(java.util.Collections.emptyList()).queue();}
    private void help(Player player){
        List<String> configured = plugin.getAppConfig().getStringList("reports.messages.help");
        List<String> lines = configured.isEmpty() ? List.of(
                "&c/%command% <player> <reason> [message]",
                "&c/%command% %status%",
                "&c/%command% %reply% <id> <message>",
                "&c/%command% %close% <id>",
                "&c/%command% %admin% &7(Admin)") : configured;
        for (String line : lines) player.sendMessage(color(line.replace("%command%", commandName)
                .replace("%create%", subCreate).replace("%status%", subStatus).replace("%reply%", subReply)
                .replace("%close%", subClose).replace("%admin%", subAdmin)));
    }
    private static boolean isOpen(Report r){return r.status().equals("OPEN")||r.status().equals("CLAIMED");}
    private static String reserveMessage(ReserveStatus s,long rem){return switch(s){case USER_LIMIT->"Too many open reports.";case GLOBAL_LIMIT->"The report system is currently full.";case COOLDOWN->"Wait "+Math.max(1,(rem+999)/1000)+" second(s).";case DUPLICATE->"This looks like a duplicate report.";case RESERVED->"Reserved.";};}
    private static String join(String[] a,int start){return String.join(" ",Arrays.copyOfRange(a,start,a.length));}
    private static Long parseId(String v){try{long id=Long.parseLong(v);return id>0?id:null;}catch(Exception e){return null;}}
    private static long readRequiredSnowflake(FileConfiguration c,String path){try{long id=Long.parseLong(String.valueOf(c.get(path)));if(id<=0)throw new NumberFormatException();return id;}catch(Exception e){throw new IllegalArgumentException(path+" must be a positive Discord ID",e);}}
    private static List<Long> readSnowflakeList(List<?> raw,String path){if(raw==null)return List.of();List<Long> out=new ArrayList<>();for(Object x:raw){try{long id=Long.parseLong(x.toString());if(id<=0)throw new NumberFormatException();out.add(id);}catch(Exception e){throw new IllegalArgumentException(path+" contains invalid ID",e);}}return List.copyOf(out);}
    private static String value(FileConfiguration c,String path,String fallback){String v=c.getString(path,fallback);return v==null?fallback:v;}
    private static long clamp(long v,long min,long max){return Math.max(min,Math.min(max,v));}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
    private enum GuiView { LIST, DETAIL }
    private static final class ReportGuiHolder implements InventoryHolder {
        private final GuiView view;
        private final int page;
        private final String filter;
        private final long reportId;
        private final Map<Integer, Long> reportBySlot = new LinkedHashMap<>();
        private Inventory inventory;
        private boolean closed;
        private ReportGuiHolder(GuiView view, int page, String filter, long reportId) {
            this.view = view; this.page = page; this.filter = filter; this.reportId = reportId;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private record AdminPage(int count, List<Report> reports){}
    private record Rendered(String name,String message){}
    private record ReportIdentity(String reporterName, String targetName){}
}
