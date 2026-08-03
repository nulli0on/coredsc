package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository.LinkedAccount;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.member.GuildMemberUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import com.hubertstudios.coredsc.scheduler.CoreTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Synchronises selected LuckPerms groups with configured Discord roles. */
public final class LuckPermsSyncModule implements CoreModule {
    private final CoreDSCPlugin plugin;
    private volatile List<RoleMapping> mappings = List.of();
    private final ConcurrentHashMap<String, LinkedAccount> pendingInitialSyncs = new ConcurrentHashMap<>();
    private final Set<Long> warnedUnmanageableRoles = ConcurrentHashMap.newKeySet();
    private final Set<Long> warnedMissingRoles = ConcurrentHashMap.newKeySet();

    private LuckPerms luckPerms;
    private LinkedAccountRepository linkedAccounts;
    private EventSubscription<UserDataRecalculateEvent> lpSubscription;
    private Listener bukkitListener;
    private ListenerAdapter discordListener;
    private CoreTask reconciliationTask;
    private long guildId;
    private boolean removeDiscordRoleWhenGroupMissing;
    private boolean removeLuckPermsGroupWhenRoleMissing;
    private boolean removeDiscordRolesOnUnlink;
    private boolean removeLuckPermsGroupsOnUnlink;
    private InitialAuthority initialAuthority;

    public LuckPermsSyncModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "luckperms-sync";
    }

    @Override
    public void enable() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            throw new IllegalStateException("LuckPerms is not installed or enabled");
        }
        DiscordBotService discord = requireDiscord();
        SQLiteStorage storage = plugin.getStorage();
        if (storage == null || storage.getState() != SQLiteStorage.State.READY) {
            throw new IllegalStateException("SQLite storage is not ready");
        }
        linkedAccounts = new LinkedAccountRepository(storage);
        luckPerms = LuckPermsProvider.get();

        FileConfiguration config = plugin.getAppConfig();
        guildId = readRequiredSnowflake(config, "luckperms-sync.guild-id");
        removeDiscordRoleWhenGroupMissing = config.getBoolean(
                "luckperms-sync.remove-discord-role-when-group-missing", true);
        removeLuckPermsGroupWhenRoleMissing = config.getBoolean(
                "luckperms-sync.remove-luckperms-group-when-role-missing", true);
        removeDiscordRolesOnUnlink = config.getBoolean(
                "luckperms-sync.remove-discord-roles-on-unlink", true);
        removeLuckPermsGroupsOnUnlink = config.getBoolean(
                "luckperms-sync.remove-luckperms-groups-on-unlink", false);
        initialAuthority = InitialAuthority.parse(value(config,
                "luckperms-sync.initial-authority", "minecraft"));
        loadMappings(config.getMapList("luckperms-sync.mappings"));
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("luckperms-sync.mappings has no enabled mappings");
        }

        lpSubscription = luckPerms.getEventBus().subscribe(
                plugin,
                UserDataRecalculateEvent.class,
                event -> syncMinecraftToDiscord(event.getUser().getUniqueId(), event.getUser())
        );

        bukkitListener = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onJoin(PlayerJoinEvent event) {
                syncMinecraftToDiscord(event.getPlayer().getUniqueId(), null);
            }
        };
        plugin.getServer().getPluginManager().registerEvents(bukkitListener, plugin);

        discordListener = new ListenerAdapter() {
            @Override
            public void onGuildMemberUpdate(GuildMemberUpdateEvent event) {
                if (event.getGuild().getIdLong() == guildId) {
                    syncDiscordMemberToMinecraft(event.getMember());
                }
            }

            @Override
            public void onReady(ReadyEvent event) {
                resumeSynchronization();
            }

            @Override
            public void onSessionResume(SessionResumeEvent event) {
                resumeSynchronization();
            }
        };
        discord.addEventListener(discordListener);
        if (discord.isReady()) {
            plugin.runSync(this::reconcileOnlinePlayers);
        }

        long reconcileSeconds = clamp(config.getLong(
                "luckperms-sync.reconcile-online-seconds", 600L), 0L, 86_400L);
        if (reconcileSeconds > 0L) {
            long ticks = reconcileSeconds * 20L;
            reconciliationTask = plugin.getCoreScheduler().runGlobalTimer(
                                        this::reconcileOnlinePlayers,
                    ticks,
                    ticks
            );
        }
    }

    @Override
    public void disable() {
        if (lpSubscription != null) {
            lpSubscription.close();
            lpSubscription = null;
        }
        if (bukkitListener != null) {
            HandlerList.unregisterAll(bukkitListener);
            bukkitListener = null;
        }
        DiscordBotService discord = plugin.getDiscordService();
        if (discordListener != null && discord != null) {
            discord.removeEventListener(discordListener);
            discordListener = null;
        }
        if (reconciliationTask != null) {
            reconciliationTask.cancel();
            reconciliationTask = null;
        }
        pendingInitialSyncs.clear();
        warnedUnmanageableRoles.clear();
        warnedMissingRoles.clear();
        mappings = List.of();
        luckPerms = null;
    }

    @Override
    public String statusDetail() {
        return mappings.size() + " role mapping(s)";
    }

    /** Called by the link module after an account has been linked. */
    public void syncAfterLink(LinkedAccount account) {
        if (account == null) {
            return;
        }
        pendingInitialSyncs.put(account.discordUserId(), account);
        attemptInitialSync(account);
    }

    private void resumeSynchronization() {
        plugin.runSync(() -> {
            if (luckPerms == null) {
                return;
            }
            reconcileOnlinePlayers();
            for (LinkedAccount account : List.copyOf(pendingInitialSyncs.values())) {
                attemptInitialSync(account);
            }
        });
    }

    private void attemptInitialSync(LinkedAccount account) {
        if (luckPerms == null || mappings.isEmpty()) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(account.minecraftUuid());
        } catch (IllegalArgumentException exception) {
            pendingInitialSyncs.remove(account.discordUserId(), account);
            plugin.getLogger().warning("[LuckPermsSync] Invalid linked UUID: " + account.minecraftUuid());
            return;
        }
        retrieveMember(account.discordUserId(), member -> {
            pendingInitialSyncs.remove(account.discordUserId(), account);
            performInitialSync(account, uuid, member);
        }, error -> {
            pendingInitialSyncs.remove(account.discordUserId(), account);
            plugin.getLogger().fine("[LuckPermsSync] Initial sync could not retrieve Discord member: "
                    + rootMessage(error));
        });
    }

    private void performInitialSync(LinkedAccount account, UUID uuid, Member member) {
        LuckPerms api = luckPerms;
        if (api == null) {
            return;
        }
        if (mappings.stream().anyMatch(this::initialMinecraftToDiscord)) {
            api.getUserManager().loadUser(uuid).thenAccept(user -> {
                Set<String> groups = inheritedGroupNames(user);
                applyMinecraftRoles(member, new MinecraftSync(account, groups), true);
            }).exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING,
                        "[LuckPermsSync] Initial Minecraft-to-Discord sync failed", error);
                return null;
            });
        }
        if (mappings.stream().anyMatch(this::initialDiscordToMinecraft)) {
            observeLuckPermsUpdate(
                    api.getUserManager().modifyUser(
                            uuid, user -> applyDiscordGroups(member, user, true)),
                    "initial Discord-to-Minecraft sync"
            );
        }
    }

    /** Called by the link module before/after an account is removed. */
    public void handleUnlink(LinkedAccount account) {
        if (account == null) {
            return;
        }
        pendingInitialSyncs.remove(account.discordUserId());
        if (removeDiscordRolesOnUnlink) {
            retrieveMember(account.discordUserId(), member -> {
                for (RoleMapping mapping : mappings) {
                    Role role = member.getGuild().getRoleById(mapping.roleId());
                    if (role != null && member.getRoles().contains(role)) {
                        changeDiscordRole(member, role, false, "unlink cleanup");
                    }
                }
            });
        }
        LuckPerms api = luckPerms;
        if (removeLuckPermsGroupsOnUnlink && api != null) {
            try {
                UUID uuid = UUID.fromString(account.minecraftUuid());
                observeLuckPermsUpdate(api.getUserManager().modifyUser(uuid, user -> {
                    for (RoleMapping mapping : mappings) {
                        if (mapping.direction().discordToMinecraft()) {
                            user.data().remove(InheritanceNode.builder(mapping.group()).build());
                        }
                    }
                }), "unlink group cleanup");
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("[LuckPermsSync] Invalid UUID during unlink: "
                        + account.minecraftUuid());
            }
        }
    }

    private void reconcileOnlinePlayers() {
        for (UUID playerId : plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getUniqueId).toList()) {
            plugin.runForPlayer(playerId, player -> {
                syncMinecraftToDiscord(playerId, null);
                syncDiscordOnlyToMinecraft(playerId);
            });
        }
    }

    private void syncDiscordOnlyToMinecraft(UUID uuid) {
        LuckPerms api = luckPerms;
        LinkedAccountRepository accounts = linkedAccounts;
        if (api == null || accounts == null || mappings.stream().noneMatch(
                mapping -> mapping.direction() == Direction.DISCORD_TO_MINECRAFT)) {
            return;
        }
        accounts.findByMinecraftUuid(uuid.toString()).thenAccept(account ->
                account.ifPresent(linked -> retrieveMember(linked.discordUserId(), member ->
                        api.getUserManager().modifyUser(uuid, user -> {
                            for (RoleMapping mapping : mappings) {
                                if (mapping.direction() != Direction.DISCORD_TO_MINECRAFT) {
                                    continue;
                                }
                                Role role = member.getGuild().getRoleById(mapping.roleId());
                                boolean hasRole = role != null && member.getRoles().contains(role);
                                InheritanceNode node = InheritanceNode.builder(mapping.group()).build();
                                if (hasRole) {
                                    user.data().add(node);
                                } else if (removeLuckPermsGroupWhenRoleMissing) {
                                    user.data().remove(node);
                                }
                            }
                        }).exceptionally(error -> {
                            plugin.getLogger().log(Level.WARNING,
                                    "[LuckPermsSync] Discord-to-Minecraft reconciliation failed", error);
                            return null;
                        })))).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING,
                    "[LuckPermsSync] Linked account lookup failed during reconciliation", error);
            return null;
        });
    }

    private void syncMinecraftToDiscord(UUID uuid, User loadedUser) {
        LuckPerms api = luckPerms;
        LinkedAccountRepository accounts = linkedAccounts;
        if (api == null || accounts == null || mappings.stream().noneMatch(
                mapping -> normalMinecraftToDiscord(mapping))) {
            return;
        }
        java.util.concurrent.CompletableFuture<User> userFuture = loadedUser == null
                ? api.getUserManager().loadUser(uuid)
                : java.util.concurrent.CompletableFuture.completedFuture(loadedUser);
        userFuture.thenCompose(user -> accounts.findByMinecraftUuid(uuid.toString())
                .thenApply(account -> new MinecraftSync(
                        account.orElse(null), inheritedGroupNames(user))))
                .thenAccept(sync -> {
                    if (sync.account() == null) {
                        return;
                    }
                    retrieveMember(sync.account().discordUserId(),
                            member -> applyMinecraftRoles(member, sync, false));
                }).exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING,
                            "[LuckPermsSync] Minecraft-to-Discord sync failed for " + uuid, error);
                    return null;
                });
    }

    private Set<String> inheritedGroupNames(User user) {
        Set<String> groups = new HashSet<>();
        for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
            groups.add(group.getName().toLowerCase(Locale.ROOT));
        }
        return groups;
    }

    private void applyMinecraftRoles(Member member, MinecraftSync sync, boolean initial) {
        for (RoleMapping mapping : mappings) {
            boolean applies = initial
                    ? initialMinecraftToDiscord(mapping)
                    : normalMinecraftToDiscord(mapping);
            if (!applies) {
                continue;
            }
            Role role = member.getGuild().getRoleById(mapping.roleId());
            if (role == null) {
                if (warnedMissingRoles.add(mapping.roleId())) {
                    plugin.getLogger().warning("[LuckPermsSync] Discord role " + mapping.roleId()
                            + " does not exist in guild " + guildId + '.');
                }
                continue;
            }
            boolean shouldHave = sync.groups().contains(mapping.group().toLowerCase(Locale.ROOT));
            boolean hasRole = member.getRoles().contains(role);
            if (shouldHave && !hasRole) {
                changeDiscordRole(member, role, true, "LuckPerms group " + mapping.group());
            } else if (!shouldHave && hasRole && removeDiscordRoleWhenGroupMissing) {
                changeDiscordRole(member, role, false,
                        "missing LuckPerms group " + mapping.group());
            }
        }
    }

    private void syncDiscordMemberToMinecraft(Member member) {
        LuckPerms api = luckPerms;
        LinkedAccountRepository accounts = linkedAccounts;
        if (api == null || accounts == null
                || mappings.stream().noneMatch(this::normalDiscordToMinecraft)) {
            return;
        }
        accounts.findByDiscordUserId(member.getId())
                .thenAccept(account -> account.ifPresent(linked -> {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(linked.minecraftUuid());
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning("[LuckPermsSync] Invalid linked UUID: "
                                + linked.minecraftUuid());
                        return;
                    }
                    observeLuckPermsUpdate(
                            api.getUserManager().modifyUser(
                                    uuid, user -> applyDiscordGroups(member, user, false)),
                            "Discord member update for " + member.getId()
                    );
                }))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING,
                            "[LuckPermsSync] Discord member lookup failed", error);
                    return null;
                });
    }

    private void applyDiscordGroups(Member member, User user, boolean initial) {
        for (RoleMapping mapping : mappings) {
            boolean applies = initial
                    ? initialDiscordToMinecraft(mapping)
                    : normalDiscordToMinecraft(mapping);
            if (!applies) {
                continue;
            }
            Role role = member.getGuild().getRoleById(mapping.roleId());
            boolean hasRole = role != null && member.getRoles().contains(role);
            InheritanceNode node = InheritanceNode.builder(mapping.group()).build();
            if (hasRole) {
                user.data().add(node);
            } else if (removeLuckPermsGroupWhenRoleMissing) {
                user.data().remove(node);
            }
        }
    }

    private void observeLuckPermsUpdate(
            java.util.concurrent.CompletableFuture<?> future,
            String context
    ) {
        future.exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING,
                    "[LuckPermsSync] " + context + " failed", error);
            return null;
        });
    }

    private void retrieveMember(String discordUserId, java.util.function.Consumer<Member> callback) {
        retrieveMember(discordUserId, callback, error -> plugin.getLogger().fine(
                "[LuckPermsSync] Discord member unavailable: " + rootMessage(error)));
    }

    private boolean retrieveMember(
            String discordUserId,
            java.util.function.Consumer<Member> callback,
            java.util.function.Consumer<Throwable> failure
    ) {
        DiscordBotService discord = plugin.getDiscordService();
        JDA jda = discord == null ? null : discord.getJda();
        if (discord == null || !discord.isReady() || jda == null) {
            return false;
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            plugin.getLogger().warning("[LuckPermsSync] Guild " + guildId
                    + " is not visible to the bot.");
            return false;
        }
        try {
            guild.retrieveMemberById(discordUserId).queue(callback, failure);
            return true;
        } catch (RuntimeException exception) {
            failure.accept(exception);
            return false;
        }
    }

    private void changeDiscordRole(Member member, Role role, boolean add, String reason) {
        Member self = member.getGuild().getSelfMember();
        if (role.isManaged() || !self.hasPermission(Permission.MANAGE_ROLES)
                || !self.canInteract(role)) {
            if (warnedUnmanageableRoles.add(role.getIdLong())) {
                plugin.getLogger().warning("[LuckPermsSync] Cannot manage Discord role "
                        + role.getName() + " (" + role.getId() + "). Check role hierarchy and Manage Roles.");
            }
            return;
        }

        try {
            if (add) {
                member.getGuild().addRoleToMember(member, role).reason(reason).queue(
                        ignored -> plugin.recordFeatureUse("role_sync"),
                        error -> roleChangeFailed(role, error));
            } else {
                member.getGuild().removeRoleFromMember(member, role).reason(reason).queue(
                        ignored -> plugin.recordFeatureUse("role_sync"),
                        error -> roleChangeFailed(role, error));
            }
        } catch (RuntimeException exception) {
            roleChangeFailed(role, exception);
        }
    }

    private void roleChangeFailed(Role role, Throwable error) {
        plugin.getLogger().warning("[LuckPermsSync] Could not update role " + role.getName()
                + ": " + rootMessage(error));
    }

    private boolean normalMinecraftToDiscord(RoleMapping mapping) {
        return mapping.direction().minecraftToDiscord();
    }

    private boolean normalDiscordToMinecraft(RoleMapping mapping) {
        return mapping.direction().discordToMinecraft();
    }

    private boolean initialMinecraftToDiscord(RoleMapping mapping) {
        return mapping.direction() == Direction.MINECRAFT_TO_DISCORD
                || mapping.direction() == Direction.BIDIRECTIONAL
                && initialAuthority == InitialAuthority.MINECRAFT;
    }

    private boolean initialDiscordToMinecraft(RoleMapping mapping) {
        return mapping.direction() == Direction.DISCORD_TO_MINECRAFT
                || mapping.direction() == Direction.BIDIRECTIONAL
                && initialAuthority == InitialAuthority.DISCORD;
    }

    private void loadMappings(List<java.util.Map<?, ?>> configured) {
        List<RoleMapping> loaded = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        Set<Long> roles = new HashSet<>();
        for (java.util.Map<?, ?> raw : configured) {
            if (!booleanValue(raw.get("enabled"), true)) {
                continue;
            }
            String group = string(raw.get("group")).toLowerCase(Locale.ROOT);
            long roleId = parseSnowflake(raw.get("role-id"), "luckperms-sync mapping role-id");
            Direction direction = Direction.parse(string(raw.get("direction")));
            if (group.isBlank()) {
                throw new IllegalArgumentException("LuckPerms mapping group cannot be blank");
            }
            if (luckPerms.getGroupManager().getGroup(group) == null) {
                plugin.getLogger().warning("[LuckPermsSync] Group '" + group
                        + "' does not currently exist; the mapping remains configured.");
            }
            if (!groups.add(group)) {
                throw new IllegalArgumentException("Duplicate LuckPerms group mapping: " + group);
            }
            if (!roles.add(roleId)) {
                throw new IllegalArgumentException("Duplicate Discord role mapping: " + roleId);
            }
            loaded.add(new RoleMapping(group, roleId, direction));
        }
        mappings = List.copyOf(loaded);
    }

    private DiscordBotService requireDiscord() {
        DiscordBotService service = plugin.getDiscordService();
        if (service == null) {
            throw new IllegalStateException("Discord service is not initialised");
        }
        return service;
    }

    private static long readRequiredSnowflake(FileConfiguration config, String path) {
        return parseSnowflake(config.get(path), path);
    }

    private static long parseSnowflake(Object raw, String path) {
        String value = raw == null ? "" : raw.toString().trim();
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0L) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(path + " must be a positive Discord ID", exception);
        }
    }

    private static String value(FileConfiguration config, String path, String fallback) {
        String configured = config.getString(path, fallback);
        return configured == null ? fallback : configured.trim();
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

    private enum Direction {
        MINECRAFT_TO_DISCORD,
        DISCORD_TO_MINECRAFT,
        BIDIRECTIONAL;

        private boolean minecraftToDiscord() {
            return this == MINECRAFT_TO_DISCORD || this == BIDIRECTIONAL;
        }

        private boolean discordToMinecraft() {
            return this == DISCORD_TO_MINECRAFT || this == BIDIRECTIONAL;
        }

        private static Direction parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "minecraft-to-discord", "minecraft", "mc-to-discord" -> MINECRAFT_TO_DISCORD;
                case "discord-to-minecraft", "discord", "discord-to-mc" -> DISCORD_TO_MINECRAFT;
                case "", "bidirectional", "both" -> BIDIRECTIONAL;
                default -> throw new IllegalArgumentException(
                        "Unsupported LuckPerms mapping direction: " + value);
            };
        }
    }

    private enum InitialAuthority {
        MINECRAFT,
        DISCORD;

        private static InitialAuthority parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "minecraft", "luckperms" -> MINECRAFT;
                case "discord" -> DISCORD;
                default -> throw new IllegalArgumentException(
                        "luckperms-sync.initial-authority must be minecraft or discord");
            };
        }
    }

    private record RoleMapping(String group, long roleId, Direction direction) { }

    private record MinecraftSync(LinkedAccount account, Set<String> groups) { }
}
