package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.event.DiscordReadyEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.storage.BanSyncRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import com.hubertstudios.coredsc.scheduler.CoreTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Initial bidirectional linked-account ban synchronization. Only bans created
 * by this module are automatically removed on the opposite platform.
 */
public final class BanSyncModule implements CoreModule, Listener {
    private static final Pattern SAFE_MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final CoreDSCPlugin plugin;
    private final Map<String, Long> suppressedDiscordBans = new ConcurrentHashMap<>();
    private final Map<String, Long> suppressedDiscordUnbans = new ConcurrentHashMap<>();
    private final Set<String> inFlightDiscordOperations = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlightMinecraftOperations = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastWarning = new AtomicLong();
    private LinkedAccountRepository accounts;
    private BanSyncRepository states;
    private ListenerAdapter discordListener;
    private CoreTask pollingTask;
    private String guildId;
    private String minecraftBanReason;
    private String discordBanReason;
    private volatile boolean active;

    public BanSyncModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "ban-sync";
    }

    @Override
    public void enable() {
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null) throw new IllegalStateException("Discord service is not initialised");
        FileConfiguration config = plugin.getAppConfig();
        guildId = value(config, "ban-sync.guild-id", value(config, "discord.guild-id", ""));
        validateSnowflake("ban-sync.guild-id", guildId);
        if (guildId.isBlank()) throw new IllegalArgumentException("ban-sync.guild-id or discord.guild-id is required");
        minecraftBanReason = sanitizeReason(value(config, "ban-sync.minecraft-ban-reason",
                "Discord ban synchronized by CoreDSC"));
        discordBanReason = sanitizeReason(value(config, "ban-sync.discord-ban-reason",
                "Minecraft ban synchronized by CoreDSC"));
        accounts = new LinkedAccountRepository(plugin.getStorage());
        states = new BanSyncRepository(plugin.getStorage());
        active = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        discordListener = new ListenerAdapter() {
            @Override
            public void onGuildBan(GuildBanEvent event) {
                if (!active || !event.getGuild().getId().equals(guildId)) return;
                String userId = event.getUser().getId();
                if (consumeSuppression(suppressedDiscordBans, userId)) return;
                handleDiscordBan(userId);
            }

            @Override
            public void onGuildUnban(GuildUnbanEvent event) {
                if (!active || !event.getGuild().getId().equals(guildId)) return;
                String userId = event.getUser().getId();
                if (consumeSuppression(suppressedDiscordUnbans, userId)) return;
                handleDiscordUnban(userId);
            }
        };
        discord.addEventListener(discordListener);

        long interval = clamp(config.getLong("ban-sync.poll-interval-ticks", 1200L), 200L, 72_000L);
        pollingTask = plugin.getCoreScheduler().runGlobalTimer(this::pollMinecraftBans,
                100L, interval);
        if (discord.isReady()) pollMinecraftBans();
    }

    @Override
    public String statusDetail() {
        return "guild=" + guildId + ", poll-active=" + (pollingTask != null);
    }

    @Override
    public void disable() {
        active = false;
        HandlerList.unregisterAll(this);
        if (pollingTask != null) {
            pollingTask.cancel();
            pollingTask = null;
        }
        DiscordBotService discord = plugin.getDiscordService();
        if (discordListener != null && discord != null) discord.removeEventListener(discordListener);
        discordListener = null;
        suppressedDiscordBans.clear();
        suppressedDiscordUnbans.clear();
        inFlightDiscordOperations.clear();
        inFlightMinecraftOperations.clear();
        accounts = null;
        states = null;
    }

    @EventHandler
    public void onDiscordReady(DiscordReadyEvent event) {
        if (active) pollMinecraftBans();
    }

    private void handleDiscordBan(String discordUserId) {
        handleDiscordBan(discordUserId, 0);
    }

    private void handleDiscordBan(String discordUserId, int retryAttempt) {
        String operationKey = discordUserId;
        if (!inFlightDiscordOperations.add(operationKey)) {
            deferDiscordOperation(discordUserId, true, retryAttempt);
            return;
        }
        LinkedAccountRepository accountRepository = accounts;
        BanSyncRepository stateRepository = states;
        if (accountRepository == null || stateRepository == null) {
            inFlightDiscordOperations.remove(operationKey);
            return;
        }
        accountRepository.findByDiscordUserId(discordUserId).thenCombine(
                stateRepository.findByDiscordUserId(discordUserId), Pair::new
        ).whenComplete((pair, error) -> {
            if (!active) {
                inFlightDiscordOperations.remove(operationKey);
                return;
            }
            if (error != null) {
                inFlightDiscordOperations.remove(operationKey);
                warn("Could not resolve Discord ban for " + discordUserId, error);
                return;
            }
            if (pair.account().isEmpty()) {
                inFlightDiscordOperations.remove(operationKey);
                return;
            }
            LinkedAccountRepository.LinkedAccount account = pair.account().get();
            if (pair.state().isPresent()
                    && !pair.state().get().minecraftUuid().equals(account.minecraftUuid())) {
                inFlightDiscordOperations.remove(operationKey);
                warn("Refusing Discord ban sync because stale ownership state belongs to another Minecraft account", null);
                return;
            }
            if (!SAFE_MINECRAFT_NAME.matcher(account.minecraftName()).matches()) {
                inFlightDiscordOperations.remove(operationKey);
                warn("Refusing to ban invalid stored Minecraft name for " + discordUserId, null);
                return;
            }
            UUID minecraftUuid;
            try {
                minecraftUuid = UUID.fromString(account.minecraftUuid());
            } catch (IllegalArgumentException invalidUuid) {
                inFlightDiscordOperations.remove(operationKey);
                warn("Refusing Discord ban sync because the stored Minecraft UUID is invalid", invalidUuid);
                return;
            }
            plugin.callSync(() -> Bukkit.getOfflinePlayer(minecraftUuid).isBanned())
                    .whenComplete((alreadyBanned, lookupError) -> {
                        if (!active) {
                            inFlightDiscordOperations.remove(operationKey);
                            return;
                        }
                        if (lookupError != null) {
                            inFlightDiscordOperations.remove(operationKey);
                            warn("Could not inspect Minecraft ban state for " + account.minecraftName(), lookupError);
                            return;
                        }
                        // Do not claim ownership of a pre-existing Minecraft ban.
                        if (Boolean.TRUE.equals(alreadyBanned)) {
                            inFlightDiscordOperations.remove(operationKey);
                            return;
                        }
                        plugin.callSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                "ban " + account.minecraftName() + " " + minecraftBanReason))
                                .whenComplete((accepted, dispatchError) -> {
                                    if (dispatchError != null || !Boolean.TRUE.equals(accepted)) {
                                        inFlightDiscordOperations.remove(operationKey);
                                        warn("Minecraft ban dispatch failed for " + account.minecraftName(), dispatchError);
                                        return;
                                    }
                                    plugin.recordFeatureUse("ban_sync");
                                    BanSyncRepository.State old = pair.state().orElse(new BanSyncRepository.State(
                                            account.minecraftUuid(), account.minecraftName(), discordUserId,
                                            false, false, "", 0L));
                                    stateRepository.upsert(new BanSyncRepository.State(
                                                    account.minecraftUuid(), account.minecraftName(), discordUserId,
                                                    true, old.discordManaged(), minecraftBanReason, System.currentTimeMillis()))
                                            .whenComplete((ignored, stateError) -> {
                                                inFlightDiscordOperations.remove(operationKey);
                                                if (stateError != null) warn("Minecraft ban applied but ownership state was not persisted", stateError);
                                            });
                                });
                    });
        });
    }

    private void handleDiscordUnban(String discordUserId) {
        handleDiscordUnban(discordUserId, 0);
    }

    private void handleDiscordUnban(String discordUserId, int retryAttempt) {
        String operationKey = discordUserId;
        if (!inFlightDiscordOperations.add(operationKey)) {
            deferDiscordOperation(discordUserId, false, retryAttempt);
            return;
        }
        BanSyncRepository stateRepository = states;
        if (stateRepository == null) {
            inFlightDiscordOperations.remove(operationKey);
            return;
        }
        stateRepository.findByDiscordUserId(discordUserId).whenComplete((stored, error) -> {
            if (error != null) {
                inFlightDiscordOperations.remove(operationKey);
                warn("Could not resolve Discord unban for " + discordUserId, error);
                return;
            }
            if (stored.isEmpty()) {
                inFlightDiscordOperations.remove(operationKey);
                return;
            }
            BanSyncRepository.State state = stored.get();
            // The Discord side is now unbanned, so any Discord ownership marker is stale.
            // If CoreDSC also owns the mirrored Minecraft ban, remove it while the module
            // is active. Otherwise only clear the stale ownership record.
            if (!state.minecraftManaged()) {
                updateOrDelete(stateRepository, state, false, false).whenComplete((ignored, stateError) -> {
                    inFlightDiscordOperations.remove(operationKey);
                    if (stateError != null) warn("Discord unban observed but ownership state was not persisted", stateError);
                });
                return;
            }
            if (!active) {
                updateOrDelete(stateRepository, state, true, false).whenComplete((ignored, stateError) -> {
                    inFlightDiscordOperations.remove(operationKey);
                    if (stateError != null) warn("Discord unban observed during shutdown but ownership state was not persisted", stateError);
                });
                return;
            }
            if (!SAFE_MINECRAFT_NAME.matcher(state.minecraftName()).matches()) {
                inFlightDiscordOperations.remove(operationKey);
                warn("Refusing to pardon invalid stored Minecraft name for " + discordUserId, null);
                return;
            }
            plugin.callSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "pardon " + state.minecraftName())).whenComplete((accepted, dispatchError) -> {
                if (dispatchError != null || !Boolean.TRUE.equals(accepted)) {
                    inFlightDiscordOperations.remove(operationKey);
                    warn("Minecraft pardon dispatch failed for " + state.minecraftName(), dispatchError);
                    return;
                }
                plugin.recordFeatureUse("ban_sync");
                // The pardon was already dispatched. Persist its terminal ownership state
                // even if a reload disabled the module before this callback ran.
                updateOrDelete(stateRepository, state, false, false).whenComplete((ignored, stateError) -> {
                    inFlightDiscordOperations.remove(operationKey);
                    if (stateError != null) warn("Minecraft pardon applied but ownership state was not persisted", stateError);
                });
            });
        });
    }

    private void deferDiscordOperation(String discordUserId, boolean banned, int retryAttempt) {
        if (!active) return;
        if (retryAttempt >= 10) {
            warn("Dropped a rapidly conflicting Discord " + (banned ? "ban" : "unban")
                    + " event after bounded retries for " + discordUserId, null);
            return;
        }
        plugin.getCoreScheduler().runGlobalLater(() -> {
            if (!active) return;
            if (banned) handleDiscordBan(discordUserId, retryAttempt + 1);
            else handleDiscordUnban(discordUserId, retryAttempt + 1);
        }, 20L);
    }

    private void pollMinecraftBans() {
        long now = System.currentTimeMillis();
        suppressedDiscordBans.entrySet().removeIf(entry -> entry.getValue() < now);
        suppressedDiscordUnbans.entrySet().removeIf(entry -> entry.getValue() < now);
        LinkedAccountRepository accountRepository = accounts;
        if (!active || accountRepository == null) return;
        accountRepository.findAll().whenComplete((linked, error) -> {
            if (!active) return;
            if (error != null) {
                warn("Could not list linked accounts for Minecraft ban polling", error);
                return;
            }
            plugin.callSync(() -> snapshotBans(linked)).whenComplete((snapshot, snapshotError) -> {
                if (!active) return;
                if (snapshotError != null) {
                    warn("Could not read Minecraft ban list", snapshotError);
                    return;
                }
                for (LinkedAccountRepository.LinkedAccount account : linked) {
                    Boolean banned = snapshot.get(account.minecraftUuid());
                    if (banned != null) reconcileMinecraftBan(account, banned);
                }
            });
        });
    }

    private Map<String, Boolean> snapshotBans(List<LinkedAccountRepository.LinkedAccount> linked) {
        Map<String, Boolean> snapshot = new HashMap<>();
        for (LinkedAccountRepository.LinkedAccount account : linked) {
            if (!SAFE_MINECRAFT_NAME.matcher(account.minecraftName()).matches()) continue;
            try {
                UUID uuid = UUID.fromString(account.minecraftUuid());
                snapshot.put(account.minecraftUuid(), Bukkit.getOfflinePlayer(uuid).isBanned());
            } catch (IllegalArgumentException invalidUuid) {
                warn("Skipping invalid stored Minecraft UUID during ban polling", invalidUuid);
            }
        }
        return snapshot;
    }

    private void reconcileMinecraftBan(LinkedAccountRepository.LinkedAccount account, boolean minecraftBanned) {
        String operationKey = account.minecraftUuid();
        if (!inFlightMinecraftOperations.add(operationKey)) return;
        BanSyncRepository stateRepository = states;
        Guild guild = guild();
        if (stateRepository == null || guild == null) {
            inFlightMinecraftOperations.remove(operationKey);
            return;
        }
        stateRepository.findByMinecraftUuid(account.minecraftUuid()).thenCombine(
                stateRepository.findByDiscordUserId(account.discordUserId()), BanStatePair::new
        ).whenComplete((stored, error) -> {
            if (!active) {
                inFlightMinecraftOperations.remove(operationKey);
                return;
            }
            if (error != null) {
                inFlightMinecraftOperations.remove(operationKey);
                warn("Could not read ban sync state for " + account.minecraftName(), error);
                return;
            }
            if (stored.byMinecraft().isPresent()
                    && !stored.byMinecraft().get().discordUserId().equals(account.discordUserId())) {
                inFlightMinecraftOperations.remove(operationKey);
                warn("Refusing ban sync because the Minecraft account has stale ownership for another Discord user", null);
                return;
            }
            if (stored.byDiscord().isPresent()
                    && !stored.byDiscord().get().minecraftUuid().equals(account.minecraftUuid())) {
                inFlightMinecraftOperations.remove(operationKey);
                warn("Refusing ban sync because the Discord account has stale ownership for another Minecraft account", null);
                return;
            }
            BanSyncRepository.State state = stored.byMinecraft().orElse(new BanSyncRepository.State(
                    account.minecraftUuid(), account.minecraftName(), account.discordUserId(),
                    false, false, "", 0L));
            if (minecraftBanned && !state.discordManaged() && !state.minecraftManaged()) {
                suppressedDiscordBans.put(account.discordUserId(), System.currentTimeMillis() + 120_000L);
                guild.ban(UserSnowflake.fromId(account.discordUserId()), 0, TimeUnit.SECONDS)
                        .reason(discordBanReason).queue(
                                ignored -> {
                                    plugin.recordFeatureUse("ban_sync");
                                    stateRepository.upsert(new BanSyncRepository.State(
                                                    account.minecraftUuid(), account.minecraftName(), account.discordUserId(),
                                                    state.minecraftManaged(), true, discordBanReason, System.currentTimeMillis()))
                                            .whenComplete((saved, stateError) -> {
                                                inFlightMinecraftOperations.remove(operationKey);
                                                if (stateError != null) warn("Discord ban applied but ownership state was not persisted", stateError);
                                            });
                                },
                                banError -> {
                                    inFlightMinecraftOperations.remove(operationKey);
                                    suppressedDiscordBans.remove(account.discordUserId());
                                    warn("Could not synchronize Minecraft ban to Discord for " + account.minecraftName(), banError);
                                });
            } else if (!minecraftBanned && state.discordManaged()) {
                suppressedDiscordUnbans.put(account.discordUserId(), System.currentTimeMillis() + 120_000L);
                guild.unban(UserSnowflake.fromId(account.discordUserId())).reason("CoreDSC Minecraft pardon sync").queue(
                        ignored -> {
                            plugin.recordFeatureUse("ban_sync");
                            updateOrDelete(stateRepository, state, false, false).whenComplete((saved, stateError) -> {
                                inFlightMinecraftOperations.remove(operationKey);
                                if (stateError != null) warn("Discord unban applied but ownership state was not persisted", stateError);
                            });
                        },
                        unbanError -> {
                            inFlightMinecraftOperations.remove(operationKey);
                            suppressedDiscordUnbans.remove(account.discordUserId());
                            warn("Could not synchronize Minecraft pardon to Discord for " + account.minecraftName(), unbanError);
                        });
            } else if (!minecraftBanned && state.minecraftManaged()) {
                updateOrDelete(stateRepository, state, false, false).whenComplete((ignored, stateError) -> {
                    inFlightMinecraftOperations.remove(operationKey);
                    if (stateError != null) warn("Minecraft pardon observed but ownership state was not persisted", stateError);
                });
            } else {
                inFlightMinecraftOperations.remove(operationKey);
            }
        });
    }

    private java.util.concurrent.CompletableFuture<Void> updateOrDelete(
            BanSyncRepository repository, BanSyncRepository.State state,
            boolean minecraftManaged, boolean discordManaged) {
        if (repository == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        if (!minecraftManaged && !discordManaged) return repository.delete(state.minecraftUuid());
        return repository.upsert(new BanSyncRepository.State(
                state.minecraftUuid(), state.minecraftName(), state.discordUserId(),
                minecraftManaged, discordManaged, state.reason(), System.currentTimeMillis()));
    }

    private Guild guild() {
        DiscordBotService discord = plugin.getDiscordService();
        JDA jda = discord == null ? null : discord.getJda();
        return jda == null || !discord.isReady() ? null : jda.getGuildById(guildId);
    }

    private static boolean consumeSuppression(Map<String, Long> suppressions, String userId) {
        Long until = suppressions.remove(userId);
        return until != null && until >= System.currentTimeMillis();
    }

    private void warn(String message, Throwable error) {
        if (error == null) plugin.recordModuleFailure("ban-sync", message);
        else plugin.recordModuleFailure("ban-sync", error);
        long now = System.currentTimeMillis();
        long previous = lastWarning.get();
        if (now - previous < 60_000L || !lastWarning.compareAndSet(previous, now)) return;
        plugin.getLogger().warning("[BanSync] " + message + (error == null ? "" : ": " + rootMessage(error)));
    }

    private static String sanitizeReason(String input) {
        String value = input == null ? "" : input.replace('\n', ' ').replace('\r', ' ').replace('\0', ' ').trim();
        if (value.isBlank()) value = "Synchronized by CoreDSC";
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private static String value(FileConfiguration config, String path, String fallback) {
        String value = config.getString(path, fallback);
        return value == null ? fallback : value.trim();
    }

    private static void validateSnowflake(String path, String value) {
        if (value.isBlank()) return;
        try { if (Long.parseUnsignedLong(value) == 0L) throw new NumberFormatException("zero"); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(path + " must be a positive Discord ID", exception); }
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = Objects.requireNonNullElseGet(throwable, () -> new IllegalStateException("unknown error"));
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Pair(
            java.util.Optional<LinkedAccountRepository.LinkedAccount> account,
            java.util.Optional<BanSyncRepository.State> state
    ) { }

    private record BanStatePair(
            java.util.Optional<BanSyncRepository.State> byMinecraft,
            java.util.Optional<BanSyncRepository.State> byDiscord
    ) { }
}
