package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.event.AccountLinkedEvent;
import com.hubertstudios.coredsc.event.AccountUnlinkedEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.voice.DiscordVoiceRelayPool;
import com.hubertstudios.coredsc.voice.ProximityTopology;
import com.hubertstudios.coredsc.voice.VoiceBridgeTransport;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import com.hubertstudios.coredsc.scheduler.CoreTask;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Position-based Discord proximity rooms. Linked Discord users are moved
 * between temporary channels according to Minecraft proximity.
 *
 * <p>The historical Discord/Simple Voice Chat relay implementation remains
 * isolated behind the relay-pool abstraction, but CoreDSC 2.5.2 does not
 * construct it because this source release does not bundle a verified DAVE
 * provider and its platform-native runtime.</p>
 */
public final class VoiceChatSyncModule extends ListenerAdapter
        implements CoreModule, Listener, VoiceBridgeTransport.Endpoint {

    private final CoreDSCPlugin plugin;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean speechReconcileQueued = new AtomicBoolean();
    private final Map<UUID, String> minecraftToDiscord = new ConcurrentHashMap<>();
    private final Map<String, UUID> discordToMinecraft = new ConcurrentHashMap<>();
    private final Map<UUID, ProximityRoom> rooms = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerRooms = new ConcurrentHashMap<>();
    private final Map<Long, UUID> channelRooms = new ConcurrentHashMap<>();
    private final Map<UUID, Long> recentSpeech = new ConcurrentHashMap<>();
    private final Map<String, Long> moveCooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> eligibleMinecraftPlayers = ConcurrentHashMap.newKeySet();

    private DiscordBotService discord;
    private LinkedAccountRepository links;
    private VoiceBridgeTransport bridge;
    private DiscordVoiceRelayPool relayPool;
    private CoreTask topologyTask;

    private long guildId;
    private long categoryId;
    private long lobbyChannelId;
    private double horizontalDistance;
    private double verticalDistance;
    private double falloff;
    private int minimumPlayers;
    private int maximumActiveRooms;
    private long updateTicks;
    private long roomGraceMillis;
    private long speechWindowMillis;
    private boolean createSoloRoomOnSpeech;
    private boolean cleanupManagedChannels;
    private boolean channelsVisible;
    private boolean allowLinkedGuests;
    private boolean allowUnlinkedGuests;
    private boolean minecraftToDiscordEnabled;
    private boolean discordToMinecraftEnabled;
    private boolean requireLinkedAccounts;
    private boolean includeUnlinkedMinecraftPlayers;
    private boolean requireDiscordDeafened;
    private String roomNamePrefix;
    private String optOutPermission;
    private Set<Long> guestRoleIds = Set.of();
    private volatile boolean structureReady;
    private volatile boolean cleanupCompleted;
    private volatile String lastError = "";
    private volatile long lastRelayExhaustionWarningAt;
    private volatile String audioRelayStatus = "not requested";

    public VoiceChatSyncModule(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String id() {
        return "voicechat-sync";
    }

    @Override
    public void enable() {
        FileConfiguration config = plugin.getAppConfig();
        guildId = readSnowflake(config, "voicechat-sync.discord.guild-id",
                readSnowflake(config, "discord.guild-id", 0L));
        categoryId = readSnowflake(config, "voicechat-sync.discord.category-id", 0L);
        lobbyChannelId = readSnowflake(config, "voicechat-sync.discord.lobby-channel-id", 0L);
        horizontalDistance = positive(config.getDouble(
                "voicechat-sync.proximity.horizontal-distance", 48.0), 48.0);
        verticalDistance = positive(config.getDouble(
                "voicechat-sync.proximity.vertical-distance", 24.0), 24.0);
        falloff = Math.max(0.0, config.getDouble("voicechat-sync.proximity.falloff", 6.0));
        minimumPlayers = Math.max(2, Math.min(100, config.getInt(
                "voicechat-sync.proximity.minimum-players", 2)));
        maximumActiveRooms = Math.max(1, Math.min(100, config.getInt(
                "voicechat-sync.rooms.maximum-active-rooms", 12)));
        updateTicks = Math.max(5L, config.getLong(
                "voicechat-sync.proximity.update-ticks", 10L));
        roomGraceMillis = Math.max(5_000L, config.getLong(
                "voicechat-sync.rooms.close-grace-seconds", 20L) * 1_000L);
        speechWindowMillis = Math.max(1_000L, config.getLong(
                "voicechat-sync.rooms.speech-activity-seconds", 4L) * 1_000L);
        createSoloRoomOnSpeech = config.getBoolean(
                "voicechat-sync.rooms.create-solo-on-speech", false);
        cleanupManagedChannels = config.getBoolean(
                "voicechat-sync.rooms.cleanup-managed-channels-on-startup", true);
        channelsVisible = config.getBoolean(
                "voicechat-sync.rooms.channels-visible", true);
        allowLinkedGuests = config.getBoolean(
                "voicechat-sync.rooms.allow-linked-guests", true);
        allowUnlinkedGuests = config.getBoolean(
                "voicechat-sync.rooms.allow-unlinked-guests", false);
        roomNamePrefix = sanitizePrefix(config.getString(
                "voicechat-sync.rooms.name-prefix", "coredsc-proximity"));
        optOutPermission = config.getString(
                "voicechat-sync.security.opt-out-permission", "coredsc.voice.optout");
        optOutPermission = optOutPermission == null ? "" : optOutPermission.trim();
        Set<Long> configuredGuestRoles = new LinkedHashSet<>(readSnowflakes(
                config.getStringList("voicechat-sync.rooms.guest-role-ids")));
        if (allowLinkedGuests) {
            long linkRoleId = readSnowflake(config, "discord.link-role-id", 0L);
            if (linkRoleId > 0L) {
                configuredGuestRoles.add(linkRoleId);
            } else if (!allowUnlinkedGuests) {
                plugin.getLogger().warning("Linked Discord guests require discord.link-role-id, "
                        + "a voicechat-sync guest role, or allow-unlinked-guests: true.");
            }
        }
        guestRoleIds = Set.copyOf(configuredGuestRoles);

        boolean minecraftToDiscordRequested = config.getBoolean(
                "voicechat-sync.audio.minecraft-to-discord.enabled", false);
        boolean discordToMinecraftRequested = config.getBoolean(
                "voicechat-sync.audio.discord-to-minecraft.enabled", false);
        boolean crossPlatformAudioRequested = minecraftToDiscordRequested
                || discordToMinecraftRequested;
        minecraftToDiscordEnabled = false;
        discordToMinecraftEnabled = false;
        audioRelayStatus = crossPlatformAudioRequested
                ? "disabled: this release does not bundle a verified Discord DAVE provider/native runtime"
                : "not enabled";
        requireLinkedAccounts = config.getBoolean(
                "voicechat-sync.security.require-linked-accounts", true);
        includeUnlinkedMinecraftPlayers = config.getBoolean(
                "voicechat-sync.security.include-unlinked-minecraft-players", false);
        requireDiscordDeafened = config.getBoolean(
                "voicechat-sync.security.require-discord-deafened-for-minecraft-relay", true);

        if (guildId <= 0L || categoryId <= 0L || lobbyChannelId <= 0L) {
            throw new IllegalStateException(
                    "voicechat-sync requires guild-id, category-id and lobby-channel-id");
        }
        discord = plugin.getDiscordService();
        if (discord == null) {
            throw new IllegalStateException("Discord service is unavailable");
        }
        links = new LinkedAccountRepository(plugin.getStorage());
        bridge = plugin.getVoiceChatBridge().transport();

        if (crossPlatformAudioRequested) {
            plugin.getLogger().severe("Cross-platform Discord/Simple Voice Chat audio was requested "
                    + "but has been disabled. Discord voice connections now require a DAVE "
                    + "implementation, and this source release does not bundle a verified "
                    + "cross-platform DAVE native runtime. Temporary Discord proximity rooms "
                    + "remain available. Keep both voicechat-sync.audio direction toggles false.");
        }
        relayPool = null;

        active.set(true);
        discord.addEventListener(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        loadAccountLinks();
        topologyTask = plugin.getCoreScheduler().runGlobalTimer(
                this::reconcileTopology, 1L, updateTicks);
        reconcileTopology();
    }

    @Override
    public void disable() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        if (topologyTask != null) {
            topologyTask.cancel();
            topologyTask = null;
        }
        HandlerList.unregisterAll(this);
        if (discord != null) {
            discord.removeEventListener(this);
        }
        for (ProximityRoom room : List.copyOf(rooms.values())) {
            closeRoom(room, true);
        }
        rooms.clear();
        playerRooms.clear();
        channelRooms.clear();
        recentSpeech.clear();
        moveCooldowns.clear();
        eligibleMinecraftPlayers.clear();
        if (relayPool != null) {
            relayPool.shutdown();
        }
        if (bridge != null && bridge.isRegistered()) {
            bridge.deactivate(this);
        }
        minecraftToDiscord.clear();
        discordToMinecraft.clear();
        structureReady = false;
        cleanupCompleted = false;
        relayPool = null;
        bridge = null;
        links = null;
        discord = null;
    }

    @Override
    public String statusDetail() {
        String relay = relayPool == null ? "audio relay " + audioRelayStatus : relayPool.statusDetail();
        String voice = bridge == null ? "Simple Voice Chat unavailable" : bridge.statusDetail();
        String error = lastError.isBlank() ? "" : "; last error=" + lastError;
        return "proximity=" + (structureReady ? "ready" : "not ready")
                + "; rooms=" + rooms.size() + '/' + maximumActiveRooms
                + "; unrelayed=" + unrelayedRoomCount()
                + "; " + relay
                + "; " + voice
                + error;
    }

    @Override
    public boolean isVoiceRelayActive() {
        DiscordVoiceRelayPool pool = relayPool;
        return active.get() && bridge != null && bridge.isServerReady()
                && pool != null && pool.configuredCount() > 0;
    }

    @Override
    public boolean shouldRelayMinecraft(UUID minecraftPlayerId) {
        DiscordVoiceRelayPool pool = relayPool;
        VoiceBridgeTransport currentBridge = bridge;
        if (!active.get() || currentBridge == null || !currentBridge.isServerReady()
                || pool == null || pool.configuredCount() == 0
                || !isEligibleMinecraftPlayer(minecraftPlayerId)) {
            return false;
        }
        UUID roomId = playerRooms.get(minecraftPlayerId);
        if (roomId == null) {
            return createSoloRoomOnSpeech;
        }
        ProximityRoom room = rooms.get(roomId);
        if (room == null || !shouldBridgeMinecraftMicrophone(room, minecraftPlayerId)) {
            return false;
        }
        // Retained for a future verified DAVE-enabled relay implementation.
        return pool.isConnected(roomId)
                || (createSoloRoomOnSpeech && room.minecraftPlayers.size() == 1);
    }

    @Override
    public void onMinecraftPcm(UUID minecraftPlayerId, short[] monoPcm) {
        if (!active.get() || minecraftPlayerId == null || monoPcm == null) {
            return;
        }
        recentSpeech.put(minecraftPlayerId, System.currentTimeMillis());
        UUID roomId = playerRooms.get(minecraftPlayerId);
        ProximityRoom room = roomId == null ? null : rooms.get(roomId);
        DiscordVoiceRelayPool pool = relayPool;
        if (room != null && pool != null && pool.isConnected(roomId)
                && shouldBridgeMinecraftMicrophone(room, minecraftPlayerId)) {
            pool.enqueueMinecraftPcm(roomId, minecraftPlayerId, monoPcm);
        } else if (createSoloRoomOnSpeech && speechReconcileQueued.compareAndSet(false, true)) {
            plugin.runSync(() -> {
                speechReconcileQueued.set(false);
                reconcileTopology();
            });
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        plugin.runSync(this::reconcileTopology);
    }

    @Override
    public void onSessionResume(SessionResumeEvent event) {
        plugin.runSync(this::reconcileTopology);
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (event.getGuild().getIdLong() == guildId && active.get()) {
            plugin.runSync(this::reconcileTopology);
        }
    }

    @EventHandler
    public void onAccountLinked(AccountLinkedEvent event) {
        putLink(event.minecraftUuid(), event.discordUserId());
        plugin.runSync(this::reconcileTopology);
    }

    @EventHandler
    public void onAccountUnlinked(AccountUnlinkedEvent event) {
        UUID minecraftId = event.minecraftUuid();
        String discordId = event.discordUserId();
        if (minecraftId != null) {
            String removedDiscord = minecraftToDiscord.remove(minecraftId);
            if (removedDiscord != null) {
                discordToMinecraft.remove(removedDiscord, minecraftId);
            }
        }
        if (discordId != null && !discordId.isBlank()) {
            UUID removedMinecraft = discordToMinecraft.remove(discordId);
            if (removedMinecraft != null) {
                minecraftToDiscord.remove(removedMinecraft, discordId);
            }
        }
        plugin.runSync(this::reconcileTopology);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getCoreScheduler().runGlobal(this::reconcileTopology);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        recentSpeech.remove(playerId);
        playerRooms.remove(playerId);
        plugin.getCoreScheduler().runGlobal(this::reconcileTopology);
    }

    public void reconnect() {
        structureReady = false;
        cleanupCompleted = false;
        if (relayPool != null) {
            relayPool.reconnectAll();
        }
        plugin.runSync(this::reconcileTopology);
    }

    public boolean isDiscordVoiceConnected() {
        return active.get() && structureReady;
    }

    public long configuredVoiceChannelId() {
        return lobbyChannelId;
    }

    public long configuredCategoryId() {
        return categoryId;
    }

    public int activeRoomCount() {
        return rooms.size();
    }

    private void reconcileTopology() {
        if (!active.get()) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            plugin.runSync(this::reconcileTopology);
            return;
        }
        try {
            reconcileTopologyNow();
        } catch (RuntimeException error) {
            structureReady = false;
            String detail = "voice topology reconciliation failed: " + rootMessage(error);
            if (!detail.equals(lastError)) {
                plugin.getLogger().warning(detail);
            }
            lastError = detail;
        }
    }

    private void reconcileTopologyNow() {
        JDA jda = discord == null ? null : discord.getJda();
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        Category category = guild == null ? null : guild.getCategoryById(categoryId);
        VoiceChannel lobby = guild == null ? null : guild.getVoiceChannelById(lobbyChannelId);
        if (guild == null || category == null || lobby == null) {
            structureReady = false;
            lastError = guild == null ? "configured guild is not visible"
                    : category == null ? "configured voice category is not visible"
                    : "configured voice lobby is not visible";
            return;
        }
        if (!validatePermissions(guild, category, lobby)) {
            structureReady = false;
            return;
        }
        structureReady = true;
        lastError = "";
        if (cleanupManagedChannels && !cleanupCompleted) {
            cleanupStaleChannels(category, lobby);
            cleanupCompleted = true;
        }

        long now = System.currentTimeMillis();
        recentSpeech.entrySet().removeIf(entry -> entry.getValue() + speechWindowMillis * 3L < now);
        Map<UUID, PlayerSnapshot> players = collectPlayers();
        eligibleMinecraftPlayers.clear();
        eligibleMinecraftPlayers.addAll(players.keySet());
        List<Set<UUID>> components = connectedComponents(players, Map.copyOf(playerRooms));
        List<DesiredGroup> desiredGroups = selectDesiredGroups(
                components, players, guild, lobby, now);
        reconcileRooms(desiredGroups, players, guild, category, lobby, now);
        moveLinkedMembers(players, guild, lobby, now);
        reconcileMergedRooms(guild);
        reconcileRelays(guild);
        synchronizeBridgeOutputs();
        closeExpiredRooms(guild, now);
    }

    private boolean validatePermissions(Guild guild, Category category, VoiceChannel lobby) {
        Member self = guild.getSelfMember();
        boolean categoryPermissions = self.hasPermission(category, Permission.VIEW_CHANNEL)
                && self.hasPermission(category, Permission.MANAGE_CHANNEL)
                && self.hasPermission(category, Permission.MANAGE_PERMISSIONS)
                && self.hasPermission(category, Permission.VOICE_MOVE_OTHERS);
        boolean lobbyPermissions = self.hasPermission(lobby, Permission.VIEW_CHANNEL)
                && self.hasPermission(lobby, Permission.VOICE_MOVE_OTHERS);
        if (!categoryPermissions || !lobbyPermissions) {
            lastError = "main bot requires View Channel, Manage Channels, Manage Permissions "
                    + "and Move Members for the proximity category/lobby";
            return false;
        }
        return true;
    }

    private Map<UUID, PlayerSnapshot> collectPlayers() {
        Map<UUID, PlayerSnapshot> snapshots = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean linked = minecraftToDiscord.containsKey(id);
            if (requireLinkedAccounts && !linked && !includeUnlinkedMinecraftPlayers) {
                continue;
            }
            if (!optOutPermission.isBlank() && player.hasPermission(optOutPermission)) {
                continue;
            }
            Location location = player.getLocation();
            snapshots.put(id, new PlayerSnapshot(
                    id,
                    player.getName(),
                    location.getWorld().getUID(),
                    location.getX(),
                    location.getY(),
                    location.getZ()
            ));
        }
        return snapshots;
    }

    private List<Set<UUID>> connectedComponents(
            Map<UUID, PlayerSnapshot> players,
            Map<UUID, UUID> previousRooms
    ) {
        List<ProximityTopology.Node> nodes = players.values().stream()
                .map(player -> new ProximityTopology.Node(
                        player.id(), player.worldId(), player.x(), player.y(), player.z()))
                .toList();
        return ProximityTopology.connectedComponents(
                nodes, previousRooms, horizontalDistance, verticalDistance, falloff);
    }

    private List<DesiredGroup> selectDesiredGroups(
            List<Set<UUID>> components,
            Map<UUID, PlayerSnapshot> players,
            Guild guild,
            VoiceChannel lobby,
            long now
    ) {
        List<DesiredGroup> desired = new ArrayList<>();
        for (Set<UUID> component : components) {
            boolean recentSpeaker = component.stream().anyMatch(playerId ->
                    recentSpeech.getOrDefault(playerId, 0L) + speechWindowMillis >= now);
            boolean voiceParticipant = component.stream().anyMatch(playerId ->
                    isDiscordParticipant(guild, playerId, lobby));
            boolean existingGuest = rooms.values().stream()
                    .filter(room -> overlap(room.minecraftPlayers, component) > 0)
                    .anyMatch(room -> roomHumanCount(guild, room) > linkedMembersInRoom(guild, room));
            boolean enoughPlayers = component.size() >= minimumPlayers;
            if ((enoughPlayers && (voiceParticipant || existingGuest))
                    || (createSoloRoomOnSpeech && recentSpeaker)) {
                UUID preferredAnchor = chooseAnchor(component, players, now);
                desired.add(new DesiredGroup(Set.copyOf(component), preferredAnchor));
            }
        }
        desired.sort(Comparator
                .comparingInt((DesiredGroup group) -> group.players().size()).reversed()
                .thenComparing(group -> group.preferredAnchor() == null
                        ? "" : group.preferredAnchor().toString()));
        if (desired.size() > maximumActiveRooms) {
            return new ArrayList<>(desired.subList(0, maximumActiveRooms));
        }
        return desired;
    }

    private void reconcileRooms(
            List<DesiredGroup> desiredGroups,
            Map<UUID, PlayerSnapshot> players,
            Guild guild,
            Category category,
            VoiceChannel lobby,
            long now
    ) {
        Set<UUID> matchedRooms = new HashSet<>();
        Map<UUID, UUID> nextPlayerRooms = new HashMap<>();
        for (DesiredGroup group : desiredGroups) {
            ProximityRoom room = findBestRoom(group.players(), matchedRooms);
            if (room == null && rooms.size() >= maximumActiveRooms) {
                ProximityRoom disposable = findDisposableRoom(guild, matchedRooms);
                if (disposable != null) {
                    closeRoom(disposable, true);
                }
            }
            if (room == null && rooms.size() < maximumActiveRooms) {
                room = new ProximityRoom(UUID.randomUUID());
                rooms.put(room.id, room);
            }
            if (room == null) {
                continue;
            }
            matchedRooms.add(room.id);
            room.minecraftPlayers = group.players();
            if (room.anchorPlayerId == null || !group.players().contains(room.anchorPlayerId)) {
                room.anchorPlayerId = group.preferredAnchor();
            }
            room.lastDesiredAt = now;
            room.closing = false;
            for (UUID playerId : group.players()) {
                nextPlayerRooms.put(playerId, room.id);
            }
            ensureRoomChannel(room, players, guild, category);
        }

        for (ProximityRoom source : rooms.values()) {
            if (matchedRooms.contains(source.id) || source.closing) {
                source.absorbedIntoRoomId = null;
                source.mergeMoveRequested = false;
                continue;
            }
            ProximityRoom target = matchedRooms.stream()
                    .map(rooms::get)
                    .filter(Objects::nonNull)
                    .filter(candidate -> overlap(source.minecraftPlayers,
                            candidate.minecraftPlayers) > 0)
                    .max(Comparator.comparingInt(candidate -> overlap(
                            source.minecraftPlayers, candidate.minecraftPlayers)))
                    .orElse(null);
            source.absorbedIntoRoomId = target == null ? null : target.id;
        }

        playerRooms.clear();
        playerRooms.putAll(nextPlayerRooms);
        for (ProximityRoom room : rooms.values()) {
            if (!matchedRooms.contains(room.id) && room.lastDesiredAt == 0L) {
                room.lastDesiredAt = now;
            }
        }
    }

    private ProximityRoom findBestRoom(Set<UUID> players, Set<UUID> excluded) {
        ProximityRoom best = null;
        int bestOverlap = 0;
        for (ProximityRoom room : rooms.values()) {
            if (excluded.contains(room.id) || room.closing) {
                continue;
            }
            int overlap = overlap(room.minecraftPlayers, players);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = room;
            }
        }
        return bestOverlap > 0 ? best : null;
    }

    private ProximityRoom findDisposableRoom(Guild guild, Set<UUID> excluded) {
        return rooms.values().stream()
                .filter(room -> !excluded.contains(room.id) && !room.closing)
                .filter(room -> roomHumanCount(guild, room) == 0)
                .min(Comparator.comparingLong(room -> room.lastDesiredAt))
                .orElse(null);
    }

    private void ensureRoomChannel(
            ProximityRoom room,
            Map<UUID, PlayerSnapshot> players,
            Guild guild,
            Category category
    ) {
        String desiredName = roomChannelName(room, players);
        VoiceChannel existing = room.channelId <= 0L ? null
                : guild.getVoiceChannelById(room.channelId);
        if (existing != null) {
            ensureRelayPermissions(room, existing, guild);
            if (!desiredName.equals(existing.getName()) && !room.renamePending) {
                room.renamePending = true;
                try {
                    existing.getManager().setName(desiredName).queue(
                            ignored -> room.renamePending = false,
                            error -> room.renamePending = false);
                } catch (RuntimeException error) {
                    room.renamePending = false;
                    plugin.getLogger().fine("Could not rename a proximity room: "
                            + rootMessage(error));
                }
            }
            return;
        }
        if (room.channelCreating) {
            return;
        }
        room.channelCreating = true;

        Set<Permission> publicAllowed = new HashSet<>();
        Set<Permission> publicDenied = new HashSet<>();
        publicAllowed.add(Permission.VOICE_SPEAK);
        if (channelsVisible || allowUnlinkedGuests) {
            publicAllowed.add(Permission.VIEW_CHANNEL);
        } else {
            publicDenied.add(Permission.VIEW_CHANNEL);
        }
        if (allowUnlinkedGuests) {
            publicAllowed.add(Permission.VOICE_CONNECT);
        } else {
            publicDenied.add(Permission.VOICE_CONNECT);
        }

        try {
            var action = category.createVoiceChannel(desiredName)
                    .addPermissionOverride(guild.getPublicRole(), publicAllowed, publicDenied)
                    .addPermissionOverride(guild.getSelfMember(), Set.of(
                            Permission.VIEW_CHANNEL,
                            Permission.MANAGE_CHANNEL,
                            Permission.MANAGE_PERMISSIONS,
                            Permission.VOICE_MOVE_OTHERS
                    ), Set.of());
            for (Long roleId : guestRoleIds) {
                Role role = guild.getRoleById(roleId);
                if (role != null) {
                    action = action.addPermissionOverride(role, Set.of(
                            Permission.VIEW_CHANNEL,
                            Permission.VOICE_CONNECT,
                            Permission.VOICE_SPEAK
                    ), Set.of());
                }
            }
            action.queue(channel -> {
                if (!active.get() || room.closing) {
                    deleteChannel(channel, "unused proximity room");
                    return;
                }
                room.channelId = channel.getIdLong();
                room.channelCreating = false;
                channelRooms.put(channel.getIdLong(), room.id);
                ensureRelayPermissions(room, channel, guild);
                plugin.runSync(this::reconcileTopology);
            }, error -> {
                room.channelCreating = false;
                lastError = "could not create proximity room: " + rootMessage(error);
                plugin.getLogger().warning(lastError);
            });
        } catch (RuntimeException error) {
            room.channelCreating = false;
            lastError = "could not create proximity room: " + rootMessage(error);
            plugin.getLogger().warning(lastError);
        }
    }

    private void ensureRelayPermissions(
            ProximityRoom room,
            VoiceChannel channel,
            Guild guild
    ) {
        DiscordVoiceRelayPool pool = relayPool;
        if (pool == null) {
            return;
        }
        for (String relayUserId : pool.relayUserIds()) {
            if (room.relayPermissionUserIds.contains(relayUserId)
                    || !room.relayPermissionPendingUserIds.add(relayUserId)) {
                continue;
            }
            Member relayMember = guild.getMemberById(relayUserId);
            if (relayMember != null) {
                grantRelayPermissions(room, channel, relayUserId, relayMember);
                continue;
            }
            try {
                guild.retrieveMemberById(relayUserId).queue(
                        member -> grantRelayPermissions(room, channel, relayUserId, member),
                        error -> room.relayPermissionPendingUserIds.remove(relayUserId));
            } catch (RuntimeException error) {
                room.relayPermissionPendingUserIds.remove(relayUserId);
                plugin.getLogger().fine("Could not resolve relay bot member: " + rootMessage(error));
            }
        }
    }

    private void grantRelayPermissions(
            ProximityRoom room,
            VoiceChannel channel,
            String relayUserId,
            Member relayMember
    ) {
        try {
            channel.upsertPermissionOverride(relayMember)
                    .grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT,
                            Permission.VOICE_SPEAK)
                    .queue(ignored -> {
                        room.relayPermissionPendingUserIds.remove(relayUserId);
                        room.relayPermissionUserIds.add(relayUserId);
                        plugin.runSync(this::reconcileTopology);
                    }, error -> {
                        room.relayPermissionPendingUserIds.remove(relayUserId);
                        room.relayPermissionUserIds.remove(relayUserId);
                        plugin.getLogger().fine("Could not grant relay bot access to "
                                + channel.getName() + ": " + rootMessage(error));
                    });
        } catch (RuntimeException error) {
            room.relayPermissionPendingUserIds.remove(relayUserId);
            room.relayPermissionUserIds.remove(relayUserId);
            plugin.getLogger().fine("Could not submit relay bot permissions for "
                    + channel.getName() + ": " + rootMessage(error));
        }
    }

    private void moveLinkedMembers(
            Map<UUID, PlayerSnapshot> players,
            Guild guild,
            VoiceChannel lobby,
            long now
    ) {
        for (UUID playerId : players.keySet()) {
            String discordId = minecraftToDiscord.get(playerId);
            if (discordId == null) {
                continue;
            }
            Member member = guild.getMemberById(discordId);
            GuildVoiceState state = member == null ? null : member.getVoiceState();
            if (state == null || !state.inAudioChannel() || state.getChannel() == null) {
                continue;
            }
            long currentChannelId = state.getChannel().getIdLong();
            boolean inManagedSystem = currentChannelId == lobbyChannelId
                    || channelRooms.containsKey(currentChannelId);
            if (!inManagedSystem) {
                continue;
            }
            UUID targetRoomId = playerRooms.get(playerId);
            ProximityRoom targetRoom = targetRoomId == null ? null : rooms.get(targetRoomId);
            VoiceChannel target = targetRoom == null || targetRoom.channelId <= 0L
                    ? lobby : guild.getVoiceChannelById(targetRoom.channelId);
            if (target == null || currentChannelId == target.getIdLong()) {
                continue;
            }
            if (moveCooldowns.getOrDefault(discordId, 0L) > now) {
                continue;
            }
            moveCooldowns.put(discordId, now + 2_000L);
            moveVoiceMember(guild, member, target,
                    "move " + member.getEffectiveName() + " to a proximity room");
        }
        moveCooldowns.entrySet().removeIf(entry -> entry.getValue() + 30_000L < now);
    }

    private void reconcileMergedRooms(Guild guild) {
        for (ProximityRoom source : List.copyOf(rooms.values())) {
            UUID targetId = source.absorbedIntoRoomId;
            if (targetId == null || source.mergeMoveRequested || source.channelId <= 0L) {
                continue;
            }
            ProximityRoom target = rooms.get(targetId);
            VoiceChannel sourceChannel = guild.getVoiceChannelById(source.channelId);
            VoiceChannel targetChannel = target == null || target.channelId <= 0L
                    ? null : guild.getVoiceChannelById(target.channelId);
            if (sourceChannel == null || targetChannel == null) {
                continue;
            }
            source.mergeMoveRequested = true;
            for (Member member : List.copyOf(sourceChannel.getMembers())) {
                if (member.getUser().isBot()) {
                    continue;
                }
                moveVoiceMember(guild, member, targetChannel,
                        "move a Discord guest while merging proximity rooms");
            }
            plugin.getCoreScheduler().runGlobalLater(() -> {
                ProximityRoom current = rooms.get(source.id);
                if (current != null && Objects.equals(current.absorbedIntoRoomId, targetId)) {
                    closeRoom(current, false);
                }
            }, 40L);
        }
    }

    private void reconcileRelays(Guild guild) {
        DiscordVoiceRelayPool pool = relayPool;
        if (pool == null) {
            return;
        }
        for (ProximityRoom room : rooms.values()) {
            VoiceChannel channel = room.channelId <= 0L ? null
                    : guild.getVoiceChannelById(room.channelId);
            room.discordDirectListeners = channel == null
                    ? Set.of() : directDiscordListeners(guild, room);
            boolean needsRelay = channel != null && roomNeedsRelay(guild, room, channel);
            if (needsRelay) {
                room.relayNeeded = true;
                room.relayAssigned = pool.assign(room.id, room.channelId);
                if (!room.relayAssigned) {
                    long now = System.currentTimeMillis();
                    if (lastRelayExhaustionWarningAt + 30_000L <= now) {
                        lastRelayExhaustionWarningAt = now;
                        plugin.getLogger().warning("No free Discord relay bot is available for "
                                + "one or more active proximity rooms.");
                    }
                }
            } else {
                room.relayNeeded = false;
                room.relayAssigned = false;
                pool.release(room.id);
            }
        }
    }

    private Set<UUID> directDiscordListeners(Guild guild, ProximityRoom room) {
        Set<UUID> listeners = new HashSet<>();
        for (UUID playerId : room.minecraftPlayers) {
            String discordId = minecraftToDiscord.get(playerId);
            Member member = discordId == null ? null : guild.getMemberById(discordId);
            GuildVoiceState state = member == null ? null : member.getVoiceState();
            if (state != null && state.inAudioChannel() && state.getChannel() != null
                    && state.getChannel().getIdLong() == room.channelId
                    && !state.isDeafened()) {
                listeners.add(playerId);
            }
        }
        return Set.copyOf(listeners);
    }

    private boolean roomNeedsRelay(Guild guild, ProximityRoom room, VoiceChannel channel) {
        boolean hasHumanDiscordUser = channel.getMembers().stream()
                .anyMatch(member -> !member.getUser().isBot());
        if (!hasHumanDiscordUser || room.minecraftPlayers.isEmpty()
                || bridge == null || !bridge.isServerReady()) {
            return false;
        }
        boolean needsMinecraftToDiscord = minecraftToDiscordEnabled
                && room.minecraftPlayers.stream().anyMatch(playerId ->
                shouldBridgeMinecraftMicrophone(room, playerId));
        boolean needsDiscordToMinecraft = discordToMinecraftEnabled
                && room.minecraftPlayers.stream().anyMatch(playerId ->
                needsDiscordPlayback(guild, room, playerId));
        return needsMinecraftToDiscord || needsDiscordToMinecraft;
    }

    private boolean shouldBridgeMinecraftMicrophone(ProximityRoom room, UUID playerId) {
        if (room == null || room.channelId <= 0L) {
            return false;
        }
        String discordId = minecraftToDiscord.get(playerId);
        if (discordId == null) {
            return !requireLinkedAccounts || includeUnlinkedMinecraftPlayers;
        }
        JDA jda = discord == null ? null : discord.getJda();
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        Member member = guild == null ? null : guild.getMemberById(discordId);
        GuildVoiceState state = member == null ? null : member.getVoiceState();
        boolean inRoom = state != null && state.inAudioChannel() && state.getChannel() != null
                && state.getChannel().getIdLong() == room.channelId;
        if (!inRoom) {
            return true;
        }
        if (requireDiscordDeafened) {
            return state.isDeafened();
        }
        return state.isMuted() || state.isDeafened();
    }

    private boolean needsDiscordPlayback(Guild guild, ProximityRoom room, UUID playerId) {
        String discordId = minecraftToDiscord.get(playerId);
        if (discordId == null) {
            return !requireLinkedAccounts || includeUnlinkedMinecraftPlayers;
        }
        Member member = guild.getMemberById(discordId);
        GuildVoiceState state = member == null ? null : member.getVoiceState();
        return state == null || !state.inAudioChannel() || state.getChannel() == null
                || state.getChannel().getIdLong() != room.channelId || state.isDeafened();
    }

    private void onDiscordPcm(UUID roomId, String discordUserId, short[] monoPcm) {
        VoiceBridgeTransport currentBridge = bridge;
        if (!active.get() || !discordToMinecraftEnabled || currentBridge == null) {
            return;
        }
        ProximityRoom room = rooms.get(roomId);
        if (room == null || room.anchorPlayerId == null) {
            return;
        }
        UUID linkedPlayer = discordToMinecraft.get(discordUserId);
        boolean linkedInRoom = linkedPlayer != null && room.minecraftPlayers.contains(linkedPlayer);
        UUID anchor = linkedInRoom ? linkedPlayer : room.anchorPlayerId;
        Set<UUID> excludedListeners = new HashSet<>(room.discordDirectListeners);
        if (linkedInRoom) {
            excludedListeners.add(linkedPlayer);
        }
        UUID streamId = UUID.nameUUIDFromBytes(
                ("coredsc:room:" + roomId + ":discord:" + discordUserId)
                        .getBytes(StandardCharsets.UTF_8));
        currentBridge.sendDiscordPcm(
                streamId, anchor, Set.copyOf(excludedListeners), monoPcm);
    }

    private void synchronizeBridgeOutputs() {
        VoiceBridgeTransport currentBridge = bridge;
        DiscordVoiceRelayPool pool = relayPool;
        if (currentBridge == null || !currentBridge.isRegistered()) {
            return;
        }
        Set<UUID> anchors = new HashSet<>();
        for (ProximityRoom room : rooms.values()) {
            if (room.anchorPlayerId != null && pool != null
                    && pool.isConnected(room.id)) {
                anchors.add(room.anchorPlayerId);
                anchors.addAll(room.minecraftPlayers);
            }
        }
        currentBridge.synchronizeOnlinePlayers(anchors);
    }

    private void closeExpiredRooms(Guild guild, long now) {
        for (ProximityRoom room : List.copyOf(rooms.values())) {
            if (playerRooms.containsValue(room.id)) {
                continue;
            }
            VoiceChannel channel = room.channelId <= 0L ? null
                    : guild.getVoiceChannelById(room.channelId);
            boolean humansPresent = channel != null && channel.getMembers().stream()
                    .anyMatch(member -> !member.getUser().isBot());
            long grace = humansPresent ? roomGraceMillis * 3L : roomGraceMillis;
            if (room.lastDesiredAt + grace <= now) {
                closeRoom(room, false);
            }
        }
    }

    private void closeRoom(ProximityRoom room, boolean immediate) {
        if (room == null || room.closing) {
            return;
        }
        room.closing = true;
        DiscordVoiceRelayPool pool = relayPool;
        if (pool != null) {
            pool.release(room.id);
        }
        rooms.remove(room.id);
        playerRooms.entrySet().removeIf(entry -> entry.getValue().equals(room.id));
        if (room.channelId <= 0L) {
            return;
        }
        channelRooms.remove(room.channelId);
        JDA jda = discord == null ? null : discord.getJda();
        VoiceChannel channel = jda == null ? null : jda.getVoiceChannelById(room.channelId);
        if (channel == null) {
            return;
        }
        if (immediate) {
            deleteChannel(channel, "proximity room");
            return;
        }
        Guild guild = channel.getGuild();
        VoiceChannel lobby = guild.getVoiceChannelById(lobbyChannelId);
        if (lobby != null) {
            for (Member member : List.copyOf(channel.getMembers())) {
                if (!member.getUser().isBot()) {
                    moveVoiceMember(guild, member, lobby,
                            "return a member to the proximity lobby");
                }
            }
        }
        try {
            plugin.getCoreScheduler().runGlobalLater(
                    () -> deleteChannel(channel, "proximity room"), 20L);
        } catch (RuntimeException error) {
            deleteChannel(channel, "proximity room");
        }
    }

    private void moveVoiceMember(Guild guild, Member member, VoiceChannel target, String description) {
        try {
            guild.moveVoiceMember(member, target).queue(
                    ignored -> { },
                    error -> plugin.getLogger().fine("Could not " + description + ": "
                            + rootMessage(error)));
        } catch (RuntimeException error) {
            plugin.getLogger().fine("Could not " + description + ": " + rootMessage(error));
        }
    }

    private void deleteChannel(VoiceChannel channel, String description) {
        if (channel == null) {
            return;
        }
        try {
            channel.delete().queue(
                    ignored -> { },
                    error -> plugin.getLogger().fine("Could not delete " + description + ": "
                            + rootMessage(error)));
        } catch (RuntimeException error) {
            plugin.getLogger().fine("Could not delete " + description + ": " + rootMessage(error));
        }
    }

    private void cleanupStaleChannels(Category category, VoiceChannel lobby) {
        for (VoiceChannel channel : category.getVoiceChannels()) {
            if (channel.getIdLong() == lobby.getIdLong()
                    || !channel.getName().startsWith(roomNamePrefix + '-')) {
                continue;
            }
            deleteChannel(channel, "stale proximity room " + channel.getName());
        }
    }

    private boolean isDiscordParticipant(Guild guild, UUID playerId, VoiceChannel lobby) {
        String discordId = minecraftToDiscord.get(playerId);
        Member member = discordId == null ? null : guild.getMemberById(discordId);
        GuildVoiceState state = member == null ? null : member.getVoiceState();
        if (state == null || !state.inAudioChannel() || state.getChannel() == null) {
            return false;
        }
        long channelId = state.getChannel().getIdLong();
        return channelId == lobby.getIdLong() || channelRooms.containsKey(channelId);
    }

    private int roomHumanCount(Guild guild, ProximityRoom room) {
        VoiceChannel channel = room.channelId <= 0L ? null
                : guild.getVoiceChannelById(room.channelId);
        if (channel == null) {
            return 0;
        }
        return (int) channel.getMembers().stream()
                .filter(member -> !member.getUser().isBot())
                .count();
    }

    private int linkedMembersInRoom(Guild guild, ProximityRoom room) {
        int count = 0;
        for (UUID playerId : room.minecraftPlayers) {
            String discordId = minecraftToDiscord.get(playerId);
            Member member = discordId == null ? null : guild.getMemberById(discordId);
            GuildVoiceState state = member == null ? null : member.getVoiceState();
            if (state != null && state.inAudioChannel() && state.getChannel() != null
                    && state.getChannel().getIdLong() == room.channelId) {
                count++;
            }
        }
        return count;
    }

    private UUID chooseAnchor(
            Set<UUID> component,
            Map<UUID, PlayerSnapshot> players,
            long now
    ) {
        return component.stream()
                .sorted(Comparator
                        .comparingLong((UUID playerId) ->
                                recentSpeech.getOrDefault(playerId, 0L) + speechWindowMillis >= now
                                        ? 0L : 1L)
                        .thenComparing(playerId -> players.get(playerId).name(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(UUID::toString))
                .findFirst()
                .orElse(null);
    }

    private String roomChannelName(ProximityRoom room, Map<UUID, PlayerSnapshot> players) {
        PlayerSnapshot anchor = players.get(room.anchorPlayerId);
        String anchorName = anchor == null ? "room" : anchor.name();
        String raw = roomNamePrefix + '-' + anchorName;
        String sanitized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (sanitized.length() > 90) {
            sanitized = sanitized.substring(0, 90);
        }
        return sanitized.isBlank() ? roomNamePrefix + '-' + room.id.toString().substring(0, 8)
                : sanitized;
    }

    private boolean isEligibleMinecraftPlayer(UUID playerId) {
        return playerId != null && eligibleMinecraftPlayers.contains(playerId);
    }

    private void loadAccountLinks() {
        LinkedAccountRepository repository = links;
        if (repository == null) {
            return;
        }
        repository.findAll().whenComplete((accounts, error) -> {
            if (!active.get()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().warning("Could not load linked accounts for voice routing: "
                        + rootMessage(error));
                return;
            }
            Map<UUID, String> byMinecraft = new HashMap<>();
            Map<String, UUID> byDiscord = new HashMap<>();
            for (LinkedAccountRepository.LinkedAccount account : accounts) {
                try {
                    String minecraftUuid = account.minecraftUuid();
                    String discordUserId = account.discordUserId();
                    if (minecraftUuid == null || minecraftUuid.isBlank()
                            || discordUserId == null || discordUserId.isBlank()) {
                        throw new IllegalArgumentException("blank account identifier");
                    }
                    UUID minecraftId = UUID.fromString(minecraftUuid);
                    if (Long.parseUnsignedLong(discordUserId) == 0L) {
                        throw new NumberFormatException("zero Discord ID");
                    }
                    byMinecraft.put(minecraftId, discordUserId);
                    byDiscord.put(discordUserId, minecraftId);
                } catch (RuntimeException ignored) {
                    plugin.getLogger().warning("Ignoring malformed linked account while loading voice routing.");
                }
            }
            minecraftToDiscord.clear();
            minecraftToDiscord.putAll(byMinecraft);
            discordToMinecraft.clear();
            discordToMinecraft.putAll(byDiscord);
            plugin.runSync(this::reconcileTopology);
        });
    }

    private void putLink(UUID minecraftId, String discordId) {
        if (minecraftId == null || discordId == null || discordId.isBlank()) {
            plugin.getLogger().warning("Ignoring malformed linked account event for voice routing.");
            return;
        }
        try {
            if (Long.parseUnsignedLong(discordId) == 0L) {
                throw new NumberFormatException("zero Discord ID");
            }
        } catch (NumberFormatException error) {
            plugin.getLogger().warning("Ignoring malformed linked account event for voice routing.");
            return;
        }
        String previousDiscord = minecraftToDiscord.put(minecraftId, discordId);
        if (previousDiscord != null) {
            discordToMinecraft.remove(previousDiscord);
        }
        UUID previousMinecraft = discordToMinecraft.put(discordId, minecraftId);
        if (previousMinecraft != null && !previousMinecraft.equals(minecraftId)) {
            minecraftToDiscord.remove(previousMinecraft);
        }
    }

    private long unrelayedRoomCount() {
        DiscordVoiceRelayPool pool = relayPool;
        return rooms.values().stream()
                .filter(room -> room.relayNeeded
                        && (pool == null || !pool.isConnected(room.id)))
                .count();
    }

    private List<String> readRelayTokens(FileConfiguration config) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String environmentName : config.getStringList(
                "voicechat-sync.relay-bots.token-environment-variables")) {
            if (environmentName == null || environmentName.isBlank()) {
                continue;
            }
            String token = System.getenv(environmentName.trim());
            if (token == null || token.isBlank()) {
                plugin.getLogger().warning("Voice relay token environment variable '"
                        + environmentName.trim() + "' is not set.");
            } else {
                tokens.add(token.trim());
            }
        }
        return List.copyOf(tokens);
    }

    private static Set<Long> readSnowflakes(Collection<String> values) {
        Set<Long> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                long parsed = Long.parseLong(value.trim());
                if (parsed > 0L) {
                    result.add(parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return Set.copyOf(result);
    }

    private static int overlap(Set<UUID> left, Set<UUID> right) {
        int count = 0;
        for (UUID value : left) {
            if (right.contains(value)) {
                count++;
            }
        }
        return count;
    }

    private static String sanitizePrefix(String input) {
        String value = input == null ? "coredsc-proximity" : input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return value.isBlank() ? "coredsc-proximity" : value.substring(0, Math.min(40, value.length()));
    }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double boundedGain(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(4.0, value)) : 1.0;
    }

    private static long readSnowflake(FileConfiguration config, String path, long fallback) {
        Object raw = config.get(path);
        if (raw == null || raw.toString().isBlank() || raw.toString().equals("0")) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.toString().trim());
            return value > 0L ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }

    private record PlayerSnapshot(
            UUID id,
            String name,
            UUID worldId,
            double x,
            double y,
            double z
    ) { }

    private record DesiredGroup(Set<UUID> players, UUID preferredAnchor) { }

    private static final class ProximityRoom {
        private final UUID id;
        private volatile Set<UUID> minecraftPlayers = Set.of();
        private volatile UUID anchorPlayerId;
        private volatile long channelId;
        private volatile long lastDesiredAt;
        private volatile boolean channelCreating;
        private volatile boolean renamePending;
        private final Set<String> relayPermissionUserIds = ConcurrentHashMap.newKeySet();
        private final Set<String> relayPermissionPendingUserIds = ConcurrentHashMap.newKeySet();
        private volatile Set<UUID> discordDirectListeners = Set.of();
        private volatile boolean relayNeeded;
        private volatile boolean relayAssigned;
        private volatile UUID absorbedIntoRoomId;
        private volatile boolean mergeMoveRequested;
        private volatile boolean closing;

        private ProximityRoom(UUID id) {
            this.id = id;
        }
    }
}
