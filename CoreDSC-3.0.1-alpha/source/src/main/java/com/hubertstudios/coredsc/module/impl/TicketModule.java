package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.api.CoreDSCApi;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.event.TicketCloseEvent;
import com.hubertstudios.coredsc.event.TicketCreateEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository.LinkedAccount;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import com.hubertstudios.coredsc.storage.SupportMessageRepository;
import com.hubertstudios.coredsc.storage.SupportMessageRepository.SupportMessage;
import com.hubertstudios.coredsc.storage.TicketRepository;
import com.hubertstudios.coredsc.storage.TicketRepository.ReserveStatus;
import com.hubertstudios.coredsc.storage.TicketRepository.Ticket;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
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
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.components.label.Label;
import org.bukkit.Bukkit;
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
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;


public final class TicketModule implements CoreModule, DiscordCommandContributor {
    private final CoreDSCPlugin plugin;
    private LinkedAccountRepository linkedAccounts;
    private TicketRepository tickets;
    private SupportMessageRepository messages;
    private ListenerAdapter discordListener;
    private Listener bukkitListener;
    private long parentChannelId;
    private boolean privateThread;
    private long cooldownMillis;
    private int maxOpenPerUser;
    private int maxOpenGlobal;
    private int reasonMaxLength;
    private int messageMaxLength;
    private String threadNameTemplate;
    private String openingMessageTemplate;
    private boolean userCanCloseOwn;
    private List<Long> staffRoleIds = List.of();
    private String commandName;
    private List<String> commandAliases = List.of();
    private String subCreate;
    private String subStatus;
    private String subReply;
    private String subClose;
    private String subClaim;
    private String subPriority;
    private boolean keepDefaultCommand;
    private String userPermission;
    private final List<BukkitCommand> registeredCommands = new ArrayList<>();

    public TicketModule(CoreDSCPlugin plugin) { this.plugin = plugin; }
    @Override public String id() { return "tickets"; }

    @Override
    public void enable() {
        DiscordBotService discord = requireDiscord();
        SQLiteStorage storage = plugin.getStorage();
        if (storage == null || storage.getState() != SQLiteStorage.State.READY) {
            throw new IllegalStateException("SQLite storage is not ready");
        }
        linkedAccounts = new LinkedAccountRepository(storage);
        tickets = new TicketRepository(storage);
        messages = new SupportMessageRepository(storage);
        FileConfiguration config = plugin.getAppConfig();
        parentChannelId = readRequiredSnowflake(config, "tickets.parent-channel-id");
        privateThread = config.getBoolean("tickets.private-thread", true);
        cooldownMillis = clamp(config.getLong("tickets.cooldown-seconds", 300L), 0L, 86_400L) * 1000L;
        maxOpenPerUser = (int) clamp(config.getLong("tickets.max-open-per-user", 1L), 1L, 20L);
        maxOpenGlobal = (int) clamp(config.getLong("tickets.max-open-global", 100L), 0L, 10_000L);
        reasonMaxLength = (int) clamp(config.getLong("tickets.reason-max-length", 100L), 3L, 100L);
        messageMaxLength = (int) clamp(config.getLong("tickets.message-max-length", 1000L), 10L, 2000L);
        threadNameTemplate = value(config, "tickets.thread-name", "ticket-%id%-%minecraft_name%");
        openingMessageTemplate = value(config, "tickets.opening-message",
                "**Ticket #%id%**\nMinecraft: `%minecraft_name%` (`%minecraft_uuid%`)\n" +
                        "Discord: <@%discord_user_id%>\nReason: **%reason%**\n\n%message%");
        userCanCloseOwn = config.getBoolean("tickets.user-can-close-own", true);
        staffRoleIds = readSnowflakeList(config.getList("tickets.staff-role-ids"), "tickets.staff-role-ids");
        commandName = commandToken(value(config, "tickets.commands.root", "ticket"), "ticket");
        commandAliases = config.getStringList("tickets.commands.aliases").stream().map(v -> commandToken(v, ""))
                .filter(v -> !v.isBlank() && !v.equals(commandName)).distinct().toList();
        keepDefaultCommand = config.getBoolean("tickets.commands.keep-default-ticket-command", true);
        subCreate = commandToken(value(config, "tickets.commands.subcommands.create", "create"), "create");
        subStatus = commandToken(value(config, "tickets.commands.subcommands.status", "status"), "status");
        subReply = commandToken(value(config, "tickets.commands.subcommands.reply", "reply"), "reply");
        subClose = commandToken(value(config, "tickets.commands.subcommands.close", "close"), "close");
        subClaim = commandToken(value(config, "tickets.commands.subcommands.claim", "claim"), "claim");
        subPriority = commandToken(value(config, "tickets.commands.subcommands.priority", "priority"), "priority");
        userPermission = value(config, "tickets.commands.permission", "coredsc.ticket").trim();
        registerMinecraftCommand();

        bukkitListener = new Listener() {
            @EventHandler public void onJoin(PlayerJoinEvent event) { deliverOfflineMessages(event.getPlayer()); }
        };
        plugin.getServer().getPluginManager().registerEvents(bukkitListener, plugin);

        discordListener = new ListenerAdapter() {
            @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
                if (!event.getName().equals(commandName)) return;
                String subcommand = event.getSubcommandName() == null ? "" : event.getSubcommandName();
                if (subcommand.equals(subCreate)) handleDiscordCreate(event);
                else if (subcommand.equals(subStatus)) handleDiscordStatus(event);
                else if (subcommand.equals(subClose)) handleDiscordClose(event);
                else if (subcommand.equals(subClaim)) handleDiscordClaim(event);
                else if (subcommand.equals(subReply)) handleDiscordReply(event);
                else if (subcommand.equals(subPriority)) handleDiscordPriority(event);
                else event.reply("Unknown ticket subcommand.").setEphemeral(true).queue();
            }
            @Override public void onMessageReceived(MessageReceivedEvent event) { handleThreadMessage(event); }
            @Override public void onButtonInteraction(ButtonInteractionEvent event) { handleButton(event); }
            @Override public void onModalInteraction(ModalInteractionEvent event) { handleModal(event); }
        };
        discord.addEventListener(discordListener);
    }

    @Override
    public void disable() {
        DiscordBotService discord = plugin.getDiscordService();
        if (discordListener != null && discord != null) discord.removeEventListener(discordListener);
        discordListener = null;
        if (bukkitListener != null) HandlerList.unregisterAll(bukkitListener);
        bukkitListener = null;
        unregisterConfiguredCommands();
        PluginCommand command = plugin.getCommand("ticket");
        if (command != null) {
            command.setExecutor((sender, cmd, label, args) -> {
                sender.sendMessage("§cThe CoreDSC ticket module is disabled."); return true;
            });
            command.setTabCompleter(null);
        }
    }

    @Override public String statusDetail() { return (privateThread ? "private" : "public") + " support threads"; }

    @Override
    public List<CommandData> slashCommands() {
        return List.of(Commands.slash(commandName, value(plugin.getAppConfig(), "tickets.commands.discord-description", "Create and manage linked support tickets")).addSubcommands(
                new SubcommandData(subCreate, "Create a support ticket").addOptions(
                        new OptionData(OptionType.STRING, "reason", "Short reason", true).setMaxLength(reasonMaxLength),
                        new OptionData(OptionType.STRING, "message", "Describe the problem", true).setMaxLength(messageMaxLength)),
                new SubcommandData(subStatus, "Show your open tickets"),
                new SubcommandData(subClose, "Close a ticket").addOption(OptionType.INTEGER, "id", "Ticket ID", false),
                new SubcommandData(subClaim, "Claim a ticket as staff").addOption(OptionType.INTEGER, "id", "Ticket ID", true),
                new SubcommandData(subReply, "Reply to a ticket as staff").addOptions(
                        new OptionData(OptionType.INTEGER, "id", "Ticket ID", true),
                        new OptionData(OptionType.STRING, "message", "Reply", true).setMaxLength(messageMaxLength)),
                new SubcommandData(subPriority, "Change ticket priority").addOptions(
                        new OptionData(OptionType.INTEGER, "id", "Ticket ID", true),
                        new OptionData(OptionType.STRING, "level", "LOW, NORMAL, HIGH, URGENT", true))
        ));
    }

    public CompletableFuture<CoreDSCApi.CreateResult> createTicketForPlayer(UUID uuid, String reason, String message) {
        String cleanReason = TextUtil.truncate(TextUtil.sanitizeMinecraftUserText(reason).trim(), reasonMaxLength);
        String cleanMessage = TextUtil.truncate(TextUtil.sanitizeMinecraftUserText(message).trim(), messageMaxLength);
        if (cleanReason.isBlank() || cleanMessage.isBlank()) {
            return CompletableFuture.completedFuture(new CoreDSCApi.CreateResult(false, 0L, "Reason and message are required."));
        }
        return linkedAccounts.findByMinecraftUuid(uuid.toString()).thenCompose(link -> {
            if (link.isEmpty()) return CompletableFuture.completedFuture(
                    new CoreDSCApi.CreateResult(false, 0L, "You must link your Discord account first."));
            LinkedAccount account = link.get();
            return tickets.reserve(account.minecraftUuid(), account.minecraftName(), account.discordUserId(),
                    cleanReason, cleanMessage, System.currentTimeMillis(), cooldownMillis,
                    maxOpenPerUser, maxOpenGlobal).thenCompose(reservation -> {
                if (reservation.status() != ReserveStatus.RESERVED) return CompletableFuture.completedFuture(
                        new CoreDSCApi.CreateResult(false, 0L, reservationMessage(reservation.status(), reservation.remainingMillis())));
                return createAndActivate(account, reservation.ticketId(), cleanReason, cleanMessage)
                        .thenApply(thread -> new CoreDSCApi.CreateResult(true, reservation.ticketId(),
                                "Ticket #" + reservation.ticketId() + " created."))
                        .exceptionallyCompose(error -> tickets.release(reservation.ticketId())
                                .thenCompose(ignored -> CompletableFuture.failedFuture(error)));
            });
        });
    }

    private void registerMinecraftCommand() {
        PluginCommand fallback = plugin.getCommand("ticket");
        if (fallback == null) throw new IllegalStateException("ticket command is missing from plugin.yml");
        fallback.setExecutor((sender, cmd, label, args) -> {
            if (!keepDefaultCommand && !commandName.equals("ticket")) {
                sender.sendMessage(TextUtil.colorize(value(plugin.getAppConfig(), "tickets.messages.command-disabled", "&cThis command is disabled. Use /%command%.")
                        .replace("%command%", commandName)));
                return true;
            }
            return executeMinecraftCommand(sender, args);
        });
        fallback.setTabCompleter((sender, cmd, alias, args) -> tabComplete(args));

        if (!commandName.equals("ticket") || !commandAliases.isEmpty()) {
            CommandMap map = plugin.getServer().getCommandMap();
            BukkitCommand dynamic = new BukkitCommand(commandName,
                    value(plugin.getAppConfig(), "tickets.commands.description", "Create and manage support tickets"),
                    "/" + commandName, commandAliases) {
                @Override public boolean execute(CommandSender sender, String label, String[] args) {
                    return executeMinecraftCommand(sender, args);
                }
                @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                    return TicketModule.this.tabComplete(args);
                }
            };
            if (!userPermission.isBlank()) dynamic.setPermission(userPermission);
            map.register("coredsc", dynamic);
            registeredCommands.add(dynamic);
            plugin.getServer().getOnlinePlayers().forEach(player ->
                    plugin.runForEntity(player, player::updateCommands));
        }
    }

    private boolean executeMinecraftCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(TextUtil.colorize(value(plugin.getAppConfig(), "tickets.messages.players-only", "&cOnly players can use tickets."))); return true; }
        if (!userPermission.isBlank() && !player.hasPermission(userPermission)) { player.sendMessage(TextUtil.colorize(value(plugin.getAppConfig(), "tickets.messages.no-permission", "&cYou do not have permission."))); return true; }
        if (args.length == 0) { sendMinecraftHelp(player); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals(subCreate)) {
            if (args.length < 3) { player.sendMessage(TextUtil.colorize(ticketUsage("create", "/%command% %create% <reason> <message>"))); return true; }
            String reason = args[1]; String message = join(args, 2);
            createTicketForPlayer(player.getUniqueId(), reason, message).whenComplete((result, error) -> plugin.runForEntity(player, () -> {
                if (error != null) { plugin.getLogger().log(Level.WARNING, "[Tickets] Minecraft creation failed", error); player.sendMessage(TextUtil.colorize(value(plugin.getAppConfig(), "tickets.messages.creation-failed", "&cTicket creation failed."))); }
                else player.sendMessage(TextUtil.colorize((result.success() ? "&a" : "&c") + result.message()));
            }));
            return true;
        }
        if (sub.equals(subStatus)) { showMinecraftStatus(player); return true; }
        if (sub.equals(subReply)) {
            if (args.length < 3) { player.sendMessage(TextUtil.colorize(ticketUsage("reply", "/%command% %reply% <id> <message>"))); return true; }
            Long id = parseId(args[1]); if (id == null) { player.sendMessage(TextUtil.colorize(value(plugin.getAppConfig(), "tickets.messages.invalid-id", "&cInvalid ticket ID."))); return true; }
            replyFromMinecraft(player, id, join(args, 2));
            return true;
        }
        if (sub.equals(subClose)) {
            if (args.length < 2) { player.sendMessage(TextUtil.colorize(ticketUsage("close", "/%command% %close% <id>"))); return true; }
            Long id = parseId(args[1]); if (id == null) { player.sendMessage(TextUtil.colorize(value(plugin.getAppConfig(), "tickets.messages.invalid-id", "&cInvalid ticket ID."))); return true; }
            closeFromMinecraft(player, id);
            return true;
        }
        sendMinecraftHelp(player);
        return true;
    }

    private List<String> tabComplete(String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of(subCreate, subStatus, subReply, subClose).stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private void unregisterConfiguredCommands() {
        if (registeredCommands.isEmpty()) return;
        CommandMap map = plugin.getServer().getCommandMap();
        if (map instanceof SimpleCommandMap simple) {
            simple.getKnownCommands().entrySet().removeIf(entry -> registeredCommands.stream().anyMatch(command -> entry.getValue() == command));
        }
        for (BukkitCommand command : registeredCommands) command.unregister(map);
        registeredCommands.clear();
        plugin.getServer().getOnlinePlayers().forEach(player ->
                plugin.runForEntity(player, player::updateCommands));
    }

    private String ticketUsage(String key, String fallback) {
        return value(plugin.getAppConfig(), "tickets.commands.usage." + key, fallback)
                .replace("%command%", commandName).replace("%create%", subCreate).replace("%status%", subStatus)
                .replace("%reply%", subReply).replace("%close%", subClose);
    }

    private static String commandToken(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_-]{1,32}") ? normalized : fallback;
    }

    private void handleDiscordCreate(SlashCommandInteractionEvent event) {
        String reason = event.getOption("reason").getAsString();
        String message = event.getOption("message").getAsString();
        event.deferReply(true).queue(hook -> linkedAccounts.findByDiscordUserId(event.getUser().getId())
                .thenCompose(link -> link.isEmpty()
                        ? CompletableFuture.completedFuture(new CoreDSCApi.CreateResult(false, 0L, "Link your Minecraft account first."))
                        : createTicketForPlayer(UUID.fromString(link.get().minecraftUuid()), reason, message))
                .whenComplete((result, error) -> edit(hook, error == null ? result.message() : "Ticket creation failed.")));
    }

    private CompletableFuture<ThreadChannel> createAndActivate(LinkedAccount account, long ticketId, String reason, String message) {
        return createDiscordThread(account, ticketId, reason, message).thenCompose(thread ->
                tickets.activate(ticketId, thread.getId()).thenApply(ignored -> {
                    plugin.runSync(() -> {
                        Bukkit.getPluginManager().callEvent(new TicketCreateEvent(
                                ticketId, UUID.fromString(account.minecraftUuid()), account.discordUserId(), reason));
                        plugin.recordFeatureUse("ticket_created");
                    });
                    return thread;
                }).exceptionallyCompose(error -> deleteThreadQuietly(thread)
                        .thenCompose(ignored -> CompletableFuture.failedFuture(error))));
    }

    private CompletableFuture<ThreadChannel> createDiscordThread(LinkedAccount account, long id, String reason, String message) {
        JDA jda = requireReadyJda();
        TextChannel parent = jda.getTextChannelById(parentChannelId);
        if (parent == null) return CompletableFuture.failedFuture(new IllegalStateException("Ticket parent channel unavailable"));
        return plugin.callSync(() -> renderTicket(account, id, reason, message)).thenCompose(rendered ->
                parent.createThreadChannel(rendered.threadName(), privateThread).submit().thenCompose(thread -> {
                    CompletableFuture<?> membership = parent.getGuild()
                            .retrieveMemberById(account.discordUserId()).submit()
                            .thenCompose(member -> thread.addThreadMember(member).submit());
                    if (!privateThread) {
                        membership = membership.handle((ignored, error) -> null);
                    }
                    return membership.thenCompose(ignored -> thread.sendMessage(rendered.openingMessage())
                                    .setAllowedMentions(java.util.Collections.emptyList())
                                    .setComponents(ActionRow.of(
                                            Button.primary("coredsc:ticket:claim:" + id, "Claim"),
                                            Button.secondary("coredsc:ticket:reply:" + id, "Reply"),
                                            Button.danger("coredsc:ticket:close:" + id, "Close")))
                                    .submit())
                            .thenApply(ignored -> thread)
                            .exceptionallyCompose(error -> deleteThreadQuietly(thread)
                                    .thenCompose(ignored -> CompletableFuture.failedFuture(error)));
                }));
    }

    private void handleThreadMessage(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage() || !(event.getChannel() instanceof ThreadChannel thread)) return;
        tickets.findOpenByChannel(thread.getId()).whenComplete((found, error) -> {
            if (error != null || found.isEmpty()) return;
            Ticket ticket = found.get();
            if (event.getMember() != null && !hasStaffRole(event.getMember())
                    && !ticket.discordUserId().equals(event.getAuthor().getId())) return;
            String content = TextUtil.truncate(TextUtil.sanitizeMassMentions(event.getMessage().getContentDisplay()), messageMaxLength);
            if (content.isBlank()) return;
            deliverDiscordReply(ticket, event.getAuthor().getId(), event.getAuthor().getEffectiveName(), content)
                    .exceptionally(deliveryError -> {
                        plugin.getLogger().warning("[Tickets] Could not deliver Discord reply: "
                                + rootMessage(deliveryError));
                        return null;
                    });
        });
    }

    private CompletableFuture<Void> deliverDiscordReply(
            Ticket ticket,
            String senderId,
            String senderName,
            String content
    ) {
        String safeName = TextUtil.sanitizeMinecraftUserText(senderName);
        String safeContent = TextUtil.sanitizeMinecraftUserText(content);
        UUID playerId = UUID.fromString(ticket.minecraftUuid());
        return messages.add("TICKET", ticket.id(), "DISCORD", senderId, safeName, safeContent,
                        System.currentTimeMillis(), false, true)
                .thenCompose(messageId -> plugin.runForPlayer(playerId, player ->
                                player.sendMessage("§9[Ticket #" + ticket.id() + "] §b"
                                        + safeName + "§7: §f" + safeContent))
                        .thenCompose(delivered -> delivered
                                ? messages.markMinecraftDelivered(List.of(messageId))
                                : CompletableFuture.completedFuture(null)));
    }

    private void replyFromMinecraft(Player player, long id, String content) {
        String safe = TextUtil.truncate(TextUtil.sanitizeMinecraftUserText(content).trim(), messageMaxLength);
        String playerUuid = player.getUniqueId().toString();
        String playerName = player.getName();
        tickets.findById(id).thenCompose(found -> {
            if (found.isEmpty() || !found.get().minecraftUuid().equals(playerUuid)
                    || !isOpen(found.get())) return CompletableFuture.failedFuture(new IllegalStateException("No matching open ticket."));
            Ticket ticket = found.get();
            return messages.add("TICKET", id, "MINECRAFT", playerUuid, playerName, safe,
                    System.currentTimeMillis(), true, false).thenCompose(messageId -> sendToThread(ticket.channelId(),
                    "**" + playerName + " (Minecraft):** " + TextUtil.sanitizeMassMentions(safe))
                    .thenCompose(ignored -> messages.markDiscordDelivered(messageId)));
        }).whenComplete((ignored, error) -> plugin.runForEntity(player, () -> player.sendMessage(error == null
                ? "§aReply sent to ticket #" + id + "." : "§c" + rootMessage(error))));
    }

    private void closeFromMinecraft(Player player, long id) {
        String playerUuid = player.getUniqueId().toString();
        String playerName = player.getName();
        tickets.findById(id).thenCompose(found -> {
            if (found.isEmpty() || !found.get().minecraftUuid().equals(playerUuid))
                return CompletableFuture.failedFuture(new IllegalStateException("Ticket not found."));
            return tickets.close(id, playerName, System.currentTimeMillis()).thenApply(closed -> {
                if (!closed) throw new IllegalStateException("Ticket is already closed.");
                archiveThread(found.get().channelId());
                plugin.runSync(() -> Bukkit.getPluginManager().callEvent(new TicketCloseEvent(id, playerName)));
                return null;
            });
        }).whenComplete((ignored, error) -> plugin.runForEntity(player, () -> player.sendMessage(error == null
                ? "§aTicket #" + id + " closed." : "§c" + rootMessage(error))));
    }

    private void showMinecraftStatus(Player player) {
        tickets.findOpenByMinecraftUuid(player.getUniqueId().toString()).whenComplete((open, error) -> plugin.runForEntity(player, () -> {
            if (error != null) { player.sendMessage("§cCould not load tickets."); return; }
            if (open.isEmpty()) { player.sendMessage("§7You have no open tickets."); return; }
            player.sendMessage("§bOpen tickets:");
            for (Ticket ticket : open) player.sendMessage("§7- §f#" + ticket.id() + " §8[" + ticket.priority() + "] §7" + ticket.reason());
        }));
    }

    private void deliverOfflineMessages(Player player) {
        messages.pendingForMinecraft(player.getUniqueId().toString(), "TICKET", 50).whenComplete((pending, error) -> {
            if (error != null || pending.isEmpty()) return;
            plugin.runForEntity(player, () -> {
                List<Long> ids = new ArrayList<>();
                player.sendMessage("§bYou have " + pending.size() + " new CoreDSC support message(s):");
                for (SupportMessage item : pending) {
                    player.sendMessage("§9[" + item.itemType() + " #" + item.itemId() + "] §b" + item.senderName() + "§7: §f" + item.message());
                    ids.add(item.id());
                }
                messages.markMinecraftDelivered(ids).exceptionally(markError -> null);
            });
        });
    }

    private void handleDiscordStatus(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> tickets.findOpenByUser(event.getUser().getId()).whenComplete((open, error) -> {
            if (error != null) { edit(hook, "Could not load tickets."); return; }
            if (open.isEmpty()) { edit(hook, "You have no open tickets."); return; }
            StringBuilder text = new StringBuilder("Open tickets:\n");
            for (Ticket ticket : open) text.append("• **#").append(ticket.id()).append("** [").append(ticket.priority())
                    .append("] — ").append(ticket.reason()).append('\n');
            edit(hook, text.toString());
        }));
    }

    private void handleDiscordClose(SlashCommandInteractionEvent event) {
        long id = event.getOption("id") == null ? 0L : event.getOption("id").getAsLong();
        event.deferReply(true).queue(hook -> resolveTicket(event.getChannel().getId(), id).thenCompose(found -> {
            if (found.isEmpty()) return CompletableFuture.completedFuture("No open ticket found.");
            Ticket ticket = found.get();
            if (!ticket.discordUserId().equals(event.getUser().getId()) && !hasStaffRole(event.getMember()))
                return CompletableFuture.completedFuture("You cannot close this ticket.");
            if (ticket.discordUserId().equals(event.getUser().getId()) && !userCanCloseOwn && !hasStaffRole(event.getMember()))
                return CompletableFuture.completedFuture("Only support staff can close this ticket.");
            return closeTicket(ticket, event.getUser().getEffectiveName()).thenApply(closed -> closed ? "Ticket closed." : "Ticket already closed.");
        }).whenComplete((message, error) -> edit(hook, error == null ? message : "Ticket close failed.")));
    }

    private void handleDiscordClaim(SlashCommandInteractionEvent event) {
        if (!hasStaffRole(event.getMember())) { event.reply("Support role required.").setEphemeral(true).queue(); return; }
        long id = event.getOption("id").getAsLong();
        event.deferReply(true).queue(hook -> tickets.claim(id, event.getUser().getEffectiveName(), System.currentTimeMillis())
                .whenComplete((claimed, error) -> edit(hook, error == null && claimed ? "Ticket claimed." : "Ticket could not be claimed.")));
    }

    private void handleDiscordReply(SlashCommandInteractionEvent event) {
        if (!hasStaffRole(event.getMember())) { event.reply("Support role required.").setEphemeral(true).queue(); return; }
        long id = event.getOption("id").getAsLong(); String message = event.getOption("message").getAsString();
        event.deferReply(true).queue(hook -> staffReply(id, event.getUser().getId(), event.getUser().getEffectiveName(), message)
                .whenComplete((ignored, error) -> edit(hook, error == null ? "Reply delivered." : "Reply failed.")));
    }

    private void handleDiscordPriority(SlashCommandInteractionEvent event) {
        if (!hasStaffRole(event.getMember())) { event.reply("Support role required.").setEphemeral(true).queue(); return; }
        String level = event.getOption("level").getAsString().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "NORMAL", "HIGH", "URGENT").contains(level)) {
            event.reply("Priority must be LOW, NORMAL, HIGH, or URGENT.").setEphemeral(true).queue(); return;
        }
        long id = event.getOption("id").getAsLong();
        event.deferReply(true).queue(hook -> tickets.setPriority(id, level, System.currentTimeMillis())
                .whenComplete((changed, error) -> edit(hook, error == null && changed ? "Priority updated." : "Ticket not found.")));
    }

    private void handleButton(ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split(":");
        if (parts.length != 4 || !parts[0].equals("coredsc") || !parts[1].equals("ticket")) return;
        if (!hasStaffRole(event.getMember())) { event.reply("Support role required.").setEphemeral(true).queue(); return; }
        long id;
        try { id = Long.parseLong(parts[3]); } catch (NumberFormatException ignored) { return; }
        switch (parts[2]) {
            case "claim" -> event.deferReply(true).queue(hook -> tickets.claim(id, event.getUser().getEffectiveName(), System.currentTimeMillis())
                    .whenComplete((ok, error) -> edit(hook, error == null && ok ? "Ticket claimed." : "Already claimed or closed.")));
            case "close" -> event.deferReply(true).queue(hook -> tickets.findById(id).thenCompose(found -> found.isEmpty()
                    ? CompletableFuture.completedFuture(false) : closeTicket(found.get(), event.getUser().getEffectiveName()))
                    .whenComplete((ok, error) -> edit(hook, error == null && ok ? "Ticket closed." : "Could not close ticket.")));
            case "reply" -> {
                TextInput input = TextInput.create("message", TextInputStyle.PARAGRAPH)
                        .setRequired(true).setMaxLength(messageMaxLength).build();
                event.replyModal(Modal.create("coredsc:ticket:reply:" + id, "Reply to ticket #" + id)
                        .addComponents(Label.of("Reply", input)).build()).queue();
            }
            default -> { }
        }
    }

    private void handleModal(ModalInteractionEvent event) {
        String[] parts = event.getModalId().split(":");
        if (parts.length != 4 || !parts[0].equals("coredsc") || !parts[1].equals("ticket") || !parts[2].equals("reply")) return;
        if (!hasStaffRole(event.getMember())) { event.reply("Support role required.").setEphemeral(true).queue(); return; }
        long id; try { id = Long.parseLong(parts[3]); } catch (NumberFormatException ignored) { return; }
        String content = event.getValue("message") == null ? "" : event.getValue("message").getAsString();
        event.deferReply(true).queue(hook -> staffReply(id, event.getUser().getId(), event.getUser().getEffectiveName(), content)
                .whenComplete((ignored, error) -> edit(hook, error == null ? "Reply delivered." : "Reply failed.")));
    }

    private CompletableFuture<Void> staffReply(long id, String senderId, String senderName, String content) {
        String safe = TextUtil.truncate(TextUtil.sanitizeMinecraftUserText(content).trim(), messageMaxLength);
        return tickets.findById(id).thenCompose(found -> {
            if (found.isEmpty() || !isOpen(found.get())) return CompletableFuture.failedFuture(new IllegalStateException("Ticket not open"));
            Ticket ticket = found.get();
            return sendToThread(ticket.channelId(), "**" + senderName + " (Staff):** "
                            + TextUtil.sanitizeMassMentions(safe))
                    .thenCompose(ignored -> deliverDiscordReply(ticket, senderId, senderName, safe));
        });
    }

    private CompletableFuture<Boolean> closeTicket(Ticket ticket, String by) {
        return tickets.close(ticket.id(), by, System.currentTimeMillis()).thenApply(closed -> {
            if (closed) {
                archiveThread(ticket.channelId());
                plugin.runSync(() -> Bukkit.getPluginManager().callEvent(new TicketCloseEvent(ticket.id(), by)));
            }
            return closed;
        });
    }

    private CompletableFuture<Optional<Ticket>> resolveTicket(String channelId, long id) {
        return id > 0 ? tickets.findById(id) : tickets.findOpenByChannel(channelId);
    }

    private CompletableFuture<Void> sendToThread(String channelId, String content) {
        JDA jda = requireReadyJda();
        ThreadChannel thread = jda.getThreadChannelById(channelId);
        if (thread == null) return CompletableFuture.failedFuture(new IllegalStateException("Ticket thread unavailable"));
        return thread.sendMessage(TextUtil.truncate(content, 2000)).setAllowedMentions(java.util.Collections.emptyList())
                .submit().thenApply(ignored -> null);
    }

    private RenderedTicket renderTicket(LinkedAccount account, long id, String reason, String message) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(UUID.fromString(account.minecraftUuid()));
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("id", id); values.put("minecraft_name", account.minecraftName());
        values.put("minecraft_uuid", account.minecraftUuid()); values.put("discord_user_id", account.discordUserId());
        values.put("reason", TextUtil.sanitizeMassMentions(reason)); values.put("message", TextUtil.sanitizeMassMentions(message));
        values.put("server_name", plugin.getServer().getName());
        String name = TextUtil.truncate(TextUtil.safeChannelToken(TextUtil.replace(threadNameTemplate, values)), 100);
        String opening = plugin.getPlaceholderService().apply(player, TextUtil.replace(openingMessageTemplate, values));
        return new RenderedTicket(name, TextUtil.truncate(TextUtil.sanitizeMassMentions(opening), 2000));
    }

    private void archiveThread(String channelId) {
        JDA jda = plugin.getDiscordService() == null ? null : plugin.getDiscordService().getJda();
        ThreadChannel thread = jda == null ? null : jda.getThreadChannelById(channelId);
        if (thread != null) thread.getManager().setLocked(true).setArchived(true).queue(ignored -> { }, error -> { });
    }

    private CompletableFuture<Void> deleteThreadQuietly(ThreadChannel thread) {
        try { return thread.delete().submit().handle((ignored, error) -> null); }
        catch (RuntimeException error) { return CompletableFuture.completedFuture(null); }
    }

    private boolean hasStaffRole(Member member) {
        if (member == null) return false;
        if (member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_THREADS)) return true;
        for (Role role : member.getRoles()) if (staffRoleIds.contains(role.getIdLong())) return true;
        return false;
    }

    private JDA requireReadyJda() {
        DiscordBotService service = requireDiscord();
        if (!service.isReady() || service.getJda() == null) throw new IllegalStateException("Discord is not ready");
        return service.getJda();
    }
    private DiscordBotService requireDiscord() {
        DiscordBotService service = plugin.getDiscordService();
        if (service == null) throw new IllegalStateException("Discord service is not initialised");
        return service;
    }
    private void edit(InteractionHook hook, String message) {
        hook.editOriginal(TextUtil.truncate(TextUtil.sanitizeMassMentions(message), 2000))
                .setAllowedMentions(java.util.Collections.emptyList()).queue(ignored -> { }, error -> { });
    }
    private void sendMinecraftHelp(Player player) {
        List<String> configured = plugin.getAppConfig().getStringList("tickets.messages.help");
        List<String> lines = configured.isEmpty() ? List.of(
                "&b/%command% %create% <reason> <message>",
                "&b/%command% %status%",
                "&b/%command% %reply% <id> <message>",
                "&b/%command% %close% <id>") : configured;
        for (String line : lines) player.sendMessage(TextUtil.colorize(line
                .replace("%command%", commandName).replace("%create%", subCreate).replace("%status%", subStatus)
                .replace("%reply%", subReply).replace("%close%", subClose)));
    }
    private static boolean isOpen(Ticket ticket) { return ticket.status().equals("OPEN") || ticket.status().equals("CLAIMED"); }
    private static Long parseId(String value) { try { long id=Long.parseLong(value); return id>0?id:null; } catch(NumberFormatException e){return null;} }
    private static String join(String[] args, int start) { return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length)); }
    private static String reservationMessage(ReserveStatus status, long remaining) { return switch (status) {
        case USER_LIMIT -> "You already have the maximum number of open tickets.";
        case GLOBAL_LIMIT -> "The server currently has too many open tickets.";
        case COOLDOWN -> "Please wait " + Math.max(1L, (remaining + 999L) / 1000L) + " second(s).";
        case RESERVED -> "Ticket reserved.";
    }; }
    private static long readRequiredSnowflake(FileConfiguration c, String path) {
        String value=String.valueOf(c.get(path)).trim(); try { long id=Long.parseLong(value); if(id<=0)throw new NumberFormatException(); return id; }
        catch(NumberFormatException e){throw new IllegalArgumentException(path+" must be a positive Discord ID",e);} }
    private static List<Long> readSnowflakeList(List<?> raw,String path){ if(raw==null)return List.of(); List<Long> out=new ArrayList<>(); for(Object x:raw){try{long id=Long.parseLong(x.toString());if(id<=0)throw new NumberFormatException();out.add(id);}catch(Exception e){throw new IllegalArgumentException(path+" contains invalid ID",e);}}return List.copyOf(out);}
    private static String value(FileConfiguration c,String path,String fallback){String v=c.getString(path,fallback);return v==null?fallback:v;}
    private static long clamp(long v,long min,long max){return Math.max(min,Math.min(max,v));}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
    private record RenderedTicket(String threadName,String openingMessage) { }
}
