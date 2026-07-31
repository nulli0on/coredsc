package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.event.AccountLinkedEvent;
import com.hubertstudios.coredsc.event.AccountUnlinkedEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository.LinkedAccount;
import com.hubertstudios.coredsc.storage.PendingLinkCodeRepository;
import com.hubertstudios.coredsc.storage.LinkSecurityRepository;
import com.hubertstudios.coredsc.storage.LinkEnforcementRepository;
import com.hubertstudios.coredsc.storage.PendingLinkCodeRepository.IssueStatus;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import com.hubertstudios.coredsc.scheduler.CoreTask;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.logging.Level;

                                                                     
public final class LinkModule implements CoreModule, DiscordCommandContributor, Listener {
    private static final CommandExecutor DISABLED_EXECUTOR = (sender, command, label, args) -> {
        sender.sendMessage("§cThis CoreDSC module is disabled.");
        return true;
    };

    private final CoreDSCPlugin plugin;
    private LinkedAccountRepository linkedAccounts;
    private PendingLinkCodeRepository pendingCodes;
    private ListenerAdapter slashListener;
    private CoreTask cleanupTask;
    private long codeExpirySeconds;
    private int codeLength;
    private String browserUrlTemplate;
    private long roleGuildId;
    private long roleId;
    private boolean removeRoleOnUnlink;
    private LinkSecurityRepository linkSecurity;
    private boolean requireGuildMembership;
    private long requiredRoleId;
    private int minimumDiscordAccountAgeDays;
    private int maximumLinksPerIpPerDay;
    private byte[] linkHashSalt;
    private LinkEnforcementRepository linkEnforcement;
    private final Map<UUID, EnforcementState> enforcementStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> restrictionNotifications = new ConcurrentHashMap<>();
    private boolean requiredLinkEnabled;
    private long linkGraceMillis;
    private long reminderIntervalMillis;
    private String requiredLinkMessage;
    private String requiredLinkBypassPermission;
    private Set<String> requiredLinkAllowedCommands = Set.of("link");
    private CoreTask reminderTask;

    public LinkModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "link";
    }

    @Override
    public void enable() {
        SQLiteStorage storage = plugin.getStorage();
        if (storage == null || storage.getState() != SQLiteStorage.State.READY) {
            throw new IllegalStateException("SQLite storage is not ready");
        }
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null) {
            throw new IllegalStateException("Discord service is not initialised");
        }

        FileConfiguration config = plugin.getAppConfig();
        codeExpirySeconds = clamp(config.getLong("link.code-expiry-seconds", 300L), 30L, 3600L);
        codeLength = (int) clamp(config.getLong("link.code-length", 8L), 6L, 16L);
        browserUrlTemplate = config.getString("link.browser-url", "");
        if (browserUrlTemplate == null) browserUrlTemplate = "";
        browserUrlTemplate = browserUrlTemplate.trim();
        roleGuildId = readOptionalSnowflake(config, "discord.guild-id");
        roleId = readOptionalSnowflake(config, "discord.link-role-id");
        if (roleId > 0L && roleGuildId <= 0L) {
            throw new IllegalArgumentException(
                    "discord.guild-id must be configured when discord.link-role-id is set");
        }
        removeRoleOnUnlink = config.getBoolean("discord.unlink-remove-role", true);
        requireGuildMembership = config.getBoolean("link.security.require-discord-server-membership", false);
        requiredRoleId = readOptionalSnowflake(config, "link.security.required-discord-role-id");
        minimumDiscordAccountAgeDays = (int) clamp(config.getLong(
                "link.security.minimum-discord-account-age-days", 0L), 0L, 3650L);
        maximumLinksPerIpPerDay = (int) clamp(config.getLong(
                "link.security.maximum-links-per-ip-per-day", 0L), 0L, 1000L);
        linkHashSalt = loadOrCreateLinkSalt();

        linkedAccounts = new LinkedAccountRepository(storage);
        pendingCodes = new PendingLinkCodeRepository(storage);
        linkSecurity = new LinkSecurityRepository(storage);
        linkEnforcement = new LinkEnforcementRepository(storage);
        requiredLinkEnabled = config.getBoolean("link.required.enabled", false);
        linkGraceMillis = clamp(config.getLong("link.required.grace-period-seconds", 300L), 0L, 604800L) * 1000L;
        reminderIntervalMillis = clamp(config.getLong("link.required.reminder-interval-seconds", 60L), 15L, 3600L) * 1000L;
        requiredLinkMessage = config.getString("link.required.message",
                "&cYou must link your Discord account. Use &e/link&c.");
        if (requiredLinkMessage == null || requiredLinkMessage.isBlank()) {
            requiredLinkMessage = "&cYou must link your Discord account. Use &e/link&c.";
        }
        requiredLinkBypassPermission = config.getString("link.required.bypass-permission", "coredsc.link.bypass");
        if (requiredLinkBypassPermission == null) requiredLinkBypassPermission = "coredsc.link.bypass";
        requiredLinkAllowedCommands = config.getStringList("link.required.allowed-commands").stream()
                .map(LinkModule::normalizeCommandRoot).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (requiredLinkAllowedCommands.isEmpty()) requiredLinkAllowedCommands = Set.of("link");
        registerMinecraftCommands();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (requiredLinkEnabled) {
            for (Player player : plugin.getServer().getOnlinePlayers()) refreshEnforcement(player);
            long period = Math.max(20L, reminderIntervalMillis / 50L);
            reminderTask = plugin.getCoreScheduler().runGlobalTimer(this::sendRequiredLinkReminders,
                    period, period);
        }

        cleanupTask = plugin.getCoreScheduler().runGlobalTimer(
                                () -> pendingCodes.deleteExpired(System.currentTimeMillis())
                        .exceptionally(error -> {
                            plugin.recordModuleFailure("link", error);
                            plugin.getLogger().warning("[Link] Expired-code cleanup failed: "
                                    + rootMessage(error));
                            return 0;
                        }),
                20L * 60L,
                20L * 300L
        );

        slashListener = new ListenerAdapter() {
            @Override
            public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
                switch (event.getName()) {
                    case "link" -> handleDiscordLink(event);
                    case "unlink" -> handleDiscordUnlink(event);
                    case "account" -> handleDiscordAccount(event);
                    default -> { }
                }
            }
        };
        discord.addEventListener(slashListener);
    }


    @Override
    public List<CommandData> slashCommands() {
        return List.of(
                Commands.slash("link", "Link your Minecraft account to Discord")
                        .addOption(OptionType.STRING, "code", "Your temporary in-game link code", true),
                Commands.slash("unlink", "Unlink your Discord account from Minecraft"),
                Commands.slash("account", "Show your linked Minecraft account")
        );
    }

    @Override
    public String statusDetail() {
        return requiredLinkEnabled
                ? "required linking enabled; grace=" + (linkGraceMillis / 1000L) + "s"
                : "account linking enabled";
    }

    @Override
    public void disable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
        }
        HandlerList.unregisterAll(this);
        enforcementStates.clear();
        restrictionNotifications.clear();
        DiscordBotService discord = plugin.getDiscordService();
        if (slashListener != null && discord != null) {
            discord.removeEventListener(slashListener);
            slashListener = null;
        }
        if (plugin.getCommand("link") != null) {
            plugin.getCommand("link").setExecutor(DISABLED_EXECUTOR);
        }
        if (plugin.getCommand("unlink") != null) {
            plugin.getCommand("unlink").setExecutor(DISABLED_EXECUTOR);
        }
    }

    private void registerMinecraftCommands() {
        if (plugin.getCommand("link") == null || plugin.getCommand("unlink") == null) {
            throw new IllegalStateException("link/unlink commands are missing from plugin.yml");
        }

        plugin.getCommand("link").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can generate a link code.");
                return true;
            }

            DiscordBotService discord = plugin.getDiscordService();
            if (discord == null || !discord.isReady()) {
                player.sendMessage("§cDiscord linking is not ready. Try again after the bot has connected.");
                return true;
            }

            UUID playerId = player.getUniqueId();
            String playerName = player.getName();
            long expiresAt = System.currentTimeMillis() + codeExpirySeconds * 1000L;

            CompletableFuture<Boolean> limiter = maximumLinksPerIpPerDay <= 0
                    ? CompletableFuture.completedFuture(true)
                    : linkSecurity.registerAttempt(hashPlayerAddress(player), System.currentTimeMillis(),
                            86_400_000L, maximumLinksPerIpPerDay);
            limiter.thenCompose(allowed -> {
                if (!allowed) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "Too many link attempts from this network today."));
                }
                return pendingCodes.issueCode(playerId.toString(), playerName, expiresAt, codeLength);
            }).whenComplete((result, error) -> plugin.runSync(() -> {
                        Player online = plugin.getServer().getPlayer(playerId);
                        if (online == null) {
                            return;
                        }
                        if (error != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "[Link] Could not generate a code for " + playerName, error);
                            online.sendMessage("§cCoreDSC could not generate a link code. Try again later.");
                        } else if (result.status() == IssueStatus.ALREADY_LINKED) {
                            online.sendMessage("§cYour Minecraft account is already linked.");
                        } else {
                            plugin.recordFeatureUse("link_code_created");
                            online.sendMessage("§aYour CoreDSC link code is §e" + result.code()
                                    + "§a. Run §e/link " + result.code()
                                    + "§a in Discord within " + codeExpirySeconds + " seconds.");
                            if (!browserUrlTemplate.isBlank()) {
                                String url = browserUrlTemplate
                                        .replace("%code%", result.code())
                                        .replace("%server_id%", plugin.getServer().getName());
                                if (url.startsWith("https://")) {
                                    online.sendMessage(Component.text("Click here to open the secure link page", NamedTextColor.AQUA)
                                            .clickEvent(ClickEvent.openUrl(url)));
                                }
                            }
                        }
                    }));
            return true;
        });

        plugin.getCommand("unlink").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can unlink an account.");
                return true;
            }
            UUID playerId = player.getUniqueId();
            String playerName = player.getName();
            linkedAccounts.removeByMinecraftUuid(playerId.toString())
                    .whenComplete((account, error) -> plugin.runSync(() -> {
                        Player online = plugin.getServer().getPlayer(playerId);
                        if (error != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "[Link] Could not unlink " + playerName, error);
                            if (online != null) {
                                online.sendMessage("§cCoreDSC could not unlink your account. Try again later.");
                            }
                            return;
                        }
                        if (account.isEmpty()) {
                            if (online != null) {
                                online.sendMessage("§cYour Minecraft account is not linked.");
                            }
                            return;
                        }
                        if (online != null) {
                            online.sendMessage("§aYour Minecraft account has been unlinked from Discord.");
                        }
                        removeConfiguredRole(null, null, account.get().discordUserId());
                        LuckPermsSyncModule roleSync = roleSync();
                        if (roleSync != null) {
                            roleSync.handleUnlink(account.get());
                        }
                        fireUnlinked(account.get());
                    }));
            return true;
        });
    }

    private void handleDiscordLink(SlashCommandInteractionEvent event) {
        String code = event.getOption("code") == null
                ? ""
                : event.getOption("code").getAsString();
        event.deferReply(true).queue(hook ->
                validateDiscordRequirements(event).thenCompose(requirementError -> {
                    if (!requirementError.isBlank()) {
                        return CompletableFuture.failedFuture(new IllegalStateException(requirementError));
                    }
                    return pendingCodes.consumeAndLink(code, event.getUser().getId(), System.currentTimeMillis());
                }).whenComplete((result, error) -> {
                            if (error != null) {
                                plugin.getLogger().log(Level.WARNING,
                                        "[Link] Discord link transaction failed", error);
                                Throwable root = rootCause(error);
                                edit(hook, root instanceof IllegalStateException && root.getMessage() != null
                                        ? root.getMessage()
                                        : "CoreDSC could not validate that code. Try again later.");
                                return;
                            }
                            switch (result.status()) {
                                case INVALID_OR_EXPIRED -> edit(hook,
                                        "That code is invalid or expired. Generate a new code with `/link` in Minecraft.");
                                case MINECRAFT_ALREADY_LINKED -> edit(hook,
                                        "That Minecraft account is already linked.");
                                case DISCORD_ALREADY_LINKED -> edit(hook,
                                        "Your Discord account is already linked. Use `/unlink` first.");
                                case LINKED -> {
                                    assignConfiguredRole(
                                            event.getGuild(),
                                            event.getMember(),
                                            event.getUser().getId());
                                    LinkedAccount account = result.account();
                                    LuckPermsSyncModule roleSync = roleSync();
                                    if (roleSync != null) {
                                        roleSync.syncAfterLink(account);
                                    }
                                    fireLinked(account);
                                    edit(hook, "Linked to Minecraft account **"
                                            + displayName(account) + "**.");
                                }
                            }
                        }),
                error -> plugin.getLogger().warning("[Link] Could not defer Discord /link: "
                        + rootMessage(error))
        );
    }

    private CompletableFuture<String> validateDiscordRequirements(SlashCommandInteractionEvent event) {
        long createdAt = (event.getUser().getIdLong() >> 22) + 1420070400000L;
        long minimumAge = minimumDiscordAccountAgeDays * 86_400_000L;
        if (minimumAge > 0L && System.currentTimeMillis() - createdAt < minimumAge) {
            return CompletableFuture.completedFuture(
                    "Your Discord account is too new to link to this server.");
        }
        if (!requireGuildMembership && requiredRoleId <= 0L) {
            return CompletableFuture.completedFuture("");
        }
        Guild guild = resolveRoleGuild(event.getGuild());
        if (guild == null) {
            return CompletableFuture.completedFuture(
                    "The configured Discord server is currently unavailable.");
        }
        Member member = event.getMember() != null
                && event.getMember().getGuild().getIdLong() == guild.getIdLong()
                ? event.getMember()
                : guild.getMemberById(event.getUser().getId());
        CompletableFuture<Member> memberFuture = member != null
                ? CompletableFuture.completedFuture(member)
                : guild.retrieveMemberById(event.getUser().getId()).submit();
        return memberFuture.handle((resolved, error) -> {
            if (error != null || resolved == null) {
                return "You must join the configured Discord server before linking.";
            }
            if (requiredRoleId > 0L
                    && resolved.getRoles().stream().noneMatch(role -> role.getIdLong() == requiredRoleId)) {
                return "You do not have the Discord role required for linking.";
            }
            return "";
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRequiredLinkJoin(PlayerJoinEvent event) {
        if (requiredLinkEnabled) refreshEnforcement(event.getPlayer());
    }

    @EventHandler
    public void onRequiredLinkQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        enforcementStates.remove(uuid);
        restrictionNotifications.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRequiredLinkMove(PlayerMoveEvent event) {
        if (!isRestricted(event.getPlayer())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to != null && (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ())) {
            event.setTo(from);
            notifyRestricted(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRequiredLinkChat(AsyncChatEvent event) {
        if (!isRestricted(event.getPlayer())) return;
        event.setCancelled(true);
        plugin.runSync(() -> notifyRestricted(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRequiredLinkCommand(PlayerCommandPreprocessEvent event) {
        if (!isRestricted(event.getPlayer())) return;
        String root = normalizeCommandRoot(event.getMessage());
        if (requiredLinkAllowedCommands.contains(root)) return;
        event.setCancelled(true);
        notifyRestricted(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRequiredLinkInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !isRestricted(player)) return;
        event.setCancelled(true);
        notifyRestricted(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRequiredLinkInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isRestricted(player)) return;
        event.setCancelled(true);
        notifyRestricted(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRequiredLinkInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isRestricted(player)) return;
        event.setCancelled(true);
        notifyRestricted(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRequiredLinkDrop(PlayerDropItemEvent event) {
        if (!isRestricted(event.getPlayer())) return;
        event.setCancelled(true);
        notifyRestricted(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRequiredLinkPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isRestricted(player)) return;
        event.setCancelled(true);
        notifyRestricted(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRequiredLinkSwapHands(PlayerSwapHandItemsEvent event) {
        if (!isRestricted(event.getPlayer())) return;
        event.setCancelled(true);
        notifyRestricted(event.getPlayer());
    }

    private void refreshEnforcement(Player player) {
        if (!requiredLinkEnabled || player.hasPermission(requiredLinkBypassPermission)) {
            enforcementStates.put(player.getUniqueId(), EnforcementState.linkedState());
            return;
        }
        UUID uuid = player.getUniqueId();
        linkedAccounts.findByMinecraftUuid(uuid.toString())
                .thenCompose(account -> {
                    if (account.isPresent()) {
                        return CompletableFuture.completedFuture(EnforcementState.linkedState());
                    }
                    return linkEnforcement.getOrCreate(uuid.toString(), System.currentTimeMillis())
                            .thenApply(state -> new EnforcementState(false, state.firstSeenAt(), state.lastReminderAt()));
                }).whenComplete((state, error) -> plugin.runSync(() -> {
                    Player online = plugin.getServer().getPlayer(uuid);
                    if (online == null) return;
                    if (error != null) {
                        plugin.getLogger().warning("[Link] Could not load required-link state for "
                                + online.getName() + ": " + rootMessage(error));
                        return;                                                                             
                    }
                    enforcementStates.put(uuid, state);
                    if (!state.linked()) notifyRestrictedOrGrace(online, state);
                }));
    }

    private boolean isRestricted(Player player) {
        if (!requiredLinkEnabled || player.hasPermission(requiredLinkBypassPermission)) return false;
        EnforcementState state = enforcementStates.get(player.getUniqueId());
        if (state == null || state.linked()) return false;
        return System.currentTimeMillis() - state.firstSeenAt() >= linkGraceMillis;
    }

    private void sendRequiredLinkReminders() {
        if (!requiredLinkEnabled) return;
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission(requiredLinkBypassPermission)) continue;
            EnforcementState state = enforcementStates.get(player.getUniqueId());
            if (state == null || state.linked() || now - state.lastReminderAt() < reminderIntervalMillis) continue;
            notifyRestrictedOrGrace(player, state);
            EnforcementState updated = new EnforcementState(false, state.firstSeenAt(), now);
            enforcementStates.put(player.getUniqueId(), updated);
            linkEnforcement.markReminder(player.getUniqueId().toString(), now).exceptionally(error -> {
                plugin.getLogger().warning("[Link] Could not persist reminder state: " + rootMessage(error));
                return null;
            });
        }
    }

    private void notifyRestrictedOrGrace(Player player, EnforcementState state) {
        long remaining = Math.max(0L, linkGraceMillis - (System.currentTimeMillis() - state.firstSeenAt()));
        String message = requiredLinkMessage
                .replace("%seconds%", Long.toString((remaining + 999L) / 1000L))
                .replace("%player%", player.getName());
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message));
    }

    private void notifyRestricted(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long previous = restrictionNotifications.putIfAbsent(uuid, now);
        if (previous != null && now - previous < 2_000L) return;
        restrictionNotifications.put(uuid, now);
        EnforcementState state = enforcementStates.get(uuid);
        if (state != null) notifyRestrictedOrGrace(player, state);
    }

    private static String normalizeCommandRoot(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith("/")) value = value.substring(1);
        int space = value.indexOf(' ');
        if (space >= 0) value = value.substring(0, space);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(colon + 1);
        return value.replaceAll("[^a-z0-9_-]", "");
    }

    private record EnforcementState(boolean linked, long firstSeenAt, long lastReminderAt) {
        private static EnforcementState linkedState() { return new EnforcementState(true, 0L, 0L); }
    }

    private byte[] loadOrCreateLinkSalt() {
        try {
            Path path = plugin.getDataFolder().toPath().resolve("link-salt.bin");
            if (Files.isRegularFile(path)) {
                byte[] existing = Files.readAllBytes(path);
                if (existing.length >= 16) {
                    return existing;
                }
            }
            Files.createDirectories(path.getParent());
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            Files.write(path, generated);
            return generated;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not initialise link privacy salt", exception);
        }
    }

    private String hashPlayerAddress(Player player) {
        String address = player.getAddress() == null || player.getAddress().getAddress() == null
                ? "unknown"
                : player.getAddress().getAddress().getHostAddress();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(linkHashSalt);
            return HexFormat.of().formatHex(digest.digest(address.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash link address", exception);
        }
    }

    private void fireLinked(LinkedAccount account) {
        try {
            UUID uuid = UUID.fromString(account.minecraftUuid());
            LinkEnforcementRepository enforcementRepository = linkEnforcement;
            if (enforcementRepository != null) {
                                                                                 
                                                                              
                enforcementRepository.clear(uuid.toString()).exceptionally(error -> {
                    plugin.getLogger().warning("[Link] Could not clear required-link grace state for "
                            + account.minecraftName() + ": " + rootMessage(error));
                    return null;
                });
            }
            plugin.runSync(() -> {
                enforcementStates.put(uuid, EnforcementState.linkedState());
                restrictionNotifications.remove(uuid);
                plugin.getServer().getPluginManager().callEvent(
                        new AccountLinkedEvent(uuid, account.minecraftName(), account.discordUserId()));
                plugin.recordFeatureUse("account_linked");
            });
        } catch (IllegalArgumentException ignored) { }
    }

    private void fireUnlinked(LinkedAccount account) {
        try {
            UUID uuid = UUID.fromString(account.minecraftUuid());
            plugin.runSync(() -> {
                plugin.getServer().getPluginManager().callEvent(
                        new AccountUnlinkedEvent(uuid, account.minecraftName(), account.discordUserId()));
                plugin.recordFeatureUse("account_unlinked");
                Player online = plugin.getServer().getPlayer(uuid);
                if (online != null && requiredLinkEnabled) refreshEnforcement(online);
            });
        } catch (IllegalArgumentException ignored) { }
    }

    private void handleDiscordUnlink(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook ->
                linkedAccounts.removeByDiscordUserId(event.getUser().getId())
                        .whenComplete((account, error) -> {
                            if (error != null) {
                                plugin.getLogger().log(Level.WARNING,
                                        "[Link] Discord unlink transaction failed", error);
                                edit(hook, "CoreDSC could not unlink your account. Try again later.");
                                return;
                            }
                            if (account.isEmpty()) {
                                edit(hook, "Your Discord account is not linked.");
                                return;
                            }
                            removeConfiguredRole(
                                    event.getGuild(),
                                    event.getMember(),
                                    event.getUser().getId());
                            LuckPermsSyncModule roleSync = roleSync();
                            if (roleSync != null) {
                                roleSync.handleUnlink(account.get());
                            }
                            fireUnlinked(account.get());
                            edit(hook, "Your Discord account has been unlinked from Minecraft.");
                        }),
                error -> plugin.getLogger().warning("[Link] Could not defer Discord /unlink: "
                        + rootMessage(error))
        );
    }

    private void handleDiscordAccount(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook ->
                linkedAccounts.findByDiscordUserId(event.getUser().getId())
                        .whenComplete((account, error) -> {
                            if (error != null) {
                                plugin.getLogger().log(Level.WARNING,
                                        "[Link] Discord account lookup failed", error);
                                edit(hook, "CoreDSC could not retrieve your linked account.");
                            } else if (account.isEmpty()) {
                                edit(hook, "Your Discord account is not linked.");
                            } else {
                                edit(hook, "Linked Minecraft account: **"
                                        + displayName(account.get()) + "** (`"
                                        + account.get().minecraftUuid() + "`).");
                            }
                        }),
                error -> plugin.getLogger().warning("[Link] Could not defer Discord /account: "
                        + rootMessage(error))
        );
    }

    private void assignConfiguredRole(
            Guild eventGuild,
            Member eventMember,
            String discordUserId
    ) {
        if (roleId <= 0L) {
            return;
        }
        Guild guild = resolveRoleGuild(eventGuild);
        if (guild == null) {
            return;
        }
        Role role = configuredRole(guild);
        if (role == null) {
            return;
        }
        if (eventMember != null && eventMember.getGuild().getIdLong() == guild.getIdLong()) {
            addRole(guild, eventMember, role);
            return;
        }
        guild.retrieveMemberById(discordUserId).queue(
                member -> addRole(guild, member, role),
                error -> plugin.getLogger().warning(
                        "[Link] Linked Discord user is not a member of the configured guild: "
                                + rootMessage(error))
        );
    }

    private void removeConfiguredRole(
            Guild eventGuild,
            Member eventMember,
            String discordUserId
    ) {
        if (!removeRoleOnUnlink || roleId <= 0L) {
            return;
        }
        Guild guild = resolveRoleGuild(eventGuild);
        if (guild == null) {
            return;
        }
        Role role = configuredRole(guild);
        if (role == null) {
            return;
        }
        if (eventMember != null && eventMember.getGuild().getIdLong() == guild.getIdLong()) {
            removeRole(guild, eventMember, role);
            return;
        }
        guild.retrieveMemberById(discordUserId).queue(
                member -> removeRole(guild, member, role),
                error -> plugin.getLogger().fine(
                        "[Link] Member unavailable for role removal: " + rootMessage(error))
        );
    }

    private Guild resolveRoleGuild(Guild eventGuild) {
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null || !discord.isReady() || discord.getJda() == null) {
            return null;
        }
        if (roleGuildId > 0L) {
            Guild configuredGuild = discord.getJda().getGuildById(roleGuildId);
            if (configuredGuild == null) {
                plugin.getLogger().warning("[Link] Configured guild " + roleGuildId
                        + " is not visible to the bot.");
            }
            return configuredGuild;
        }
        return eventGuild;
    }

    private void addRole(Guild guild, Member member, Role role) {
        guild.addRoleToMember(member, role).queue(
                ignored -> { },
                error -> plugin.getLogger().warning(
                        "[Link] Could not assign link role: " + rootMessage(error))
        );
    }

    private void removeRole(Guild guild, Member member, Role role) {
        guild.removeRoleFromMember(member, role).queue(
                ignored -> { },
                error -> plugin.getLogger().warning(
                        "[Link] Could not remove link role: " + rootMessage(error))
        );
    }

    private Role configuredRole(Guild guild) {
        if (roleId <= 0L) {
            return null;
        }
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            plugin.getLogger().warning("[Link] Configured role " + roleId
                    + " does not exist in guild " + guild.getId() + ".");
        }
        return role;
    }


    private LuckPermsSyncModule roleSync() {
        return plugin.getModuleManager() == null
                ? null
                : plugin.getModuleManager().getModule(LuckPermsSyncModule.class);
    }

    private void edit(InteractionHook hook, String message) {
        hook.editOriginal(message).queue(
                ignored -> { },
                error -> plugin.getLogger().warning(
                        "[Link] Could not deliver a Discord interaction response: "
                                + rootMessage(error))
        );
    }

    private static String displayName(LinkedAccount account) {
        return account.minecraftName() == null || account.minecraftName().isBlank()
                ? account.minecraftUuid()
                : account.minecraftName();
    }

    private static long readOptionalSnowflake(FileConfiguration config, String path) {
        Object raw = config.get(path);
        if (raw == null) {
            return 0L;
        }
        String value = raw.toString().trim();
        if (value.isEmpty() || value.equals("0")) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0L) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(path + " must be a positive Discord ID or 0", exception);
        }
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
