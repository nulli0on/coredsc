package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.event.AccountLinkedEvent;
import com.hubertstudios.coredsc.event.DiscordReadyEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.service.RewardExecutor;
import com.hubertstudios.coredsc.storage.BoosterStateRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import com.hubertstudios.coredsc.scheduler.CoreTask;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Grants persistent rewards to linked Discord server boosters. */
public final class BoosterRewardsModule implements CoreModule, Listener {
    private final CoreDSCPlugin plugin;
    private final AtomicLong lastWarning = new AtomicLong();
    private LinkedAccountRepository accounts;
    private BoosterStateRepository states;
    private RewardExecutor rewards;
    private ListenerAdapter discordListener;
    private CoreTask reconciliationTask;
    private String guildId;
    private String boosterRoleId;
    private List<String> commands = List.of();
    private long periodMillis;
    private volatile boolean active;

    public BoosterRewardsModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "booster-rewards";
    }

    @Override
    public void enable() {
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null) throw new IllegalStateException("Discord service is not initialised");
        FileConfiguration config = plugin.getAppConfig();
        guildId = value(config, "booster-rewards.guild-id", value(config, "discord.guild-id", ""));
        boosterRoleId = value(config, "booster-rewards.booster-role-id", "");
        validateSnowflake("booster-rewards.guild-id", guildId);
        validateSnowflake("booster-rewards.booster-role-id", boosterRoleId);
        if (guildId.isBlank() || boosterRoleId.isBlank()) {
            throw new IllegalArgumentException("booster-rewards.guild-id and booster-role-id are required");
        }
        commands = config.getStringList("booster-rewards.commands").stream()
                .filter(java.util.Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("booster-rewards.commands must contain at least one command");
        }
        long periodDays = clamp(config.getLong("booster-rewards.reward-period-days", 30L), 1L, 3650L);
        periodMillis = periodDays * 86_400_000L;
        accounts = new LinkedAccountRepository(plugin.getStorage());
        states = new BoosterStateRepository(plugin.getStorage());
        rewards = new RewardExecutor(plugin);
        active = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        discordListener = new ListenerAdapter() {
            @Override
            public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
                if (active && event.getGuild().getId().equals(guildId)
                        && event.getRoles().stream().anyMatch(role -> role.getId().equals(boosterRoleId))) {
                    reconcileMember(event.getMember(), true);
                }
            }

            @Override
            public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
                if (active && event.getGuild().getId().equals(guildId)
                        && event.getRoles().stream().anyMatch(role -> role.getId().equals(boosterRoleId))) {
                    reconcileMember(event.getMember(), false);
                }
            }
        };
        discord.addEventListener(discordListener);

        long intervalTicks = clamp(config.getLong("booster-rewards.reconcile-interval-ticks", 72_000L), 1_200L, 1_728_000L);
        reconciliationTask = plugin.getCoreScheduler().runGlobalTimer(this::reconcileAll,
                200L, intervalTicks);
        if (discord.isReady()) reconcileAll();
        rewards.resume("BOOSTER", commands);
    }

    @Override
    public String statusDetail() {
        return "guild=" + guildId + ", role=" + boosterRoleId + ", commands=" + commands.size();
    }

    @Override
    public void disable() {
        active = false;
        HandlerList.unregisterAll(this);
        if (reconciliationTask != null) {
            reconciliationTask.cancel();
            reconciliationTask = null;
        }
        DiscordBotService discord = plugin.getDiscordService();
        if (discordListener != null && discord != null) discord.removeEventListener(discordListener);
        discordListener = null;
        commands = List.of();
        accounts = null;
        states = null;
        if (rewards != null) rewards.shutdown();
        rewards = null;
    }

    @EventHandler
    public void onDiscordReady(DiscordReadyEvent event) {
        if (active) reconcileAll();
    }

    @EventHandler
    public void onAccountLinked(AccountLinkedEvent event) {
        if (!active) return;
        Guild guild = guild();
        if (guild == null) return;
        guild.retrieveMemberById(event.discordUserId()).queue(
                member -> reconcileMember(member, hasBoosterRole(member)),
                error -> { }
        );
    }

    private void reconcileAll() {
        if (!active) return;
        Guild guild = guild();
        LinkedAccountRepository repository = accounts;
        if (guild == null || repository == null) return;
        repository.findAll().whenComplete((linked, error) -> {
            if (!active) return;
            if (error != null) {
                warn("Could not list linked accounts", error);
                return;
            }
            for (LinkedAccountRepository.LinkedAccount account : linked) {
                guild.retrieveMemberById(account.discordUserId()).queue(
                        member -> reconcileMember(member, hasBoosterRole(member)),
                        retrieveError -> warn("Could not retrieve linked Discord member " + account.discordUserId(), retrieveError)
                );
            }
        });
    }

    private void reconcileMember(Member member, boolean boosting) {
        LinkedAccountRepository accountRepository = accounts;
        BoosterStateRepository stateRepository = states;
        RewardExecutor executor = rewards;
        if (!active || accountRepository == null || stateRepository == null || executor == null) return;
        String discordId = member.getId();
        accountRepository.findByDiscordUserId(discordId).thenCombine(
                stateRepository.find(discordId), (account, state) -> new Pair(account, state)
        ).whenComplete((pair, error) -> {
            if (!active) return;
            if (error != null) {
                warn("Could not reconcile booster " + discordId, error);
                return;
            }
            if (pair.account().isEmpty()) {
                markInactive(discordId, "");
                return;
            }
            LinkedAccountRepository.LinkedAccount account = pair.account().get();
            long now = System.currentTimeMillis();
            if (!boosting) {
                long boostedAt = pair.state().map(BoosterStateRepository.BoosterState::boostedAt).orElse(0L);
                long latestPeriod = pair.state().map(BoosterStateRepository.BoosterState::lastRewardPeriod).orElse(-1L);
                stateRepository.upsert(discordId, account.minecraftUuid(), false, boostedAt, latestPeriod, now);
                return;
            }
            long reportedBoostedAt = member.getTimeBoosted() == null
                    ? now : member.getTimeBoosted().toInstant().toEpochMilli();
            final long observedBoostedAt = reportedBoostedAt <= 0L || reportedBoostedAt > now
                    ? now : reportedBoostedAt;
            boolean continuingCycle = pair.state().filter(BoosterStateRepository.BoosterState::active)
                    .filter(state -> state.boostedAt() > 0L)
                    .filter(state -> Math.abs(state.boostedAt() - observedBoostedAt) < 60_000L)
                    .isPresent();
            long boostedAt = continuingCycle
                    ? pair.state().orElseThrow().boostedAt()
                    : observedBoostedAt;
            long period = Math.max(0L, (now - boostedAt) / periodMillis);
            // A new boost cycle starts its own reward sequence. Reusing the old
            // period counter would incorrectly suppress the first new-cycle rewards.
            long rewarded = continuingCycle
                    ? pair.state().orElseThrow().lastRewardPeriod()
                    : -1L;
            stateRepository.upsert(discordId, account.minecraftUuid(), true, boostedAt, rewarded, now)
                    .whenComplete((ignored, stateError) -> {
                        if (!active) return;
                        if (stateError != null) {
                            warn("Could not persist booster state for " + discordId, stateError);
                            return;
                        }
                        if (period <= rewarded) return;
                        UUID uuid;
                        try { uuid = UUID.fromString(account.minecraftUuid()); }
                        catch (IllegalArgumentException exception) {
                            warn("Stored Minecraft UUID is invalid for booster " + discordId, exception);
                            return;
                        }
                        executor.grant(
                                "booster:" + discordId + ':' + boostedAt + ':' + period,
                                "BOOSTER",
                                uuid,
                                account.minecraftName(),
                                discordId,
                                commands,
                                () -> {
                                    plugin.recordFeatureUse("booster_reward");
                                    stateRepository.upsert(discordId, account.minecraftUuid(), true,
                                                    boostedAt, period, System.currentTimeMillis())
                                            .exceptionally(updateError -> {
                                                warn("Reward completed but booster period could not be persisted for " + discordId, updateError);
                                                return null;
                                            });
                                }
                        );
                    });
        });
    }

    private void markInactive(String discordId, String minecraftUuid) {
        BoosterStateRepository repository = states;
        if (repository == null) return;
        repository.find(discordId).whenComplete((existing, error) -> {
            if (!active || error != null) return;
            long boostedAt = existing.map(BoosterStateRepository.BoosterState::boostedAt).orElse(0L);
            long period = existing.map(BoosterStateRepository.BoosterState::lastRewardPeriod).orElse(-1L);
            repository.upsert(discordId, minecraftUuid, false, boostedAt, period, System.currentTimeMillis());
        });
    }

    private boolean hasBoosterRole(Member member) {
        return member.getRoles().stream().anyMatch(role -> role.getId().equals(boosterRoleId));
    }

    private Guild guild() {
        DiscordBotService discord = plugin.getDiscordService();
        JDA jda = discord == null ? null : discord.getJda();
        return jda == null || !discord.isReady() ? null : jda.getGuildById(guildId);
    }

    private void warn(String message, Throwable error) {
        if (error == null) plugin.recordModuleFailure("booster-rewards", message);
        else plugin.recordModuleFailure("booster-rewards", error);
        long now = System.currentTimeMillis();
        long previous = lastWarning.get();
        if (now - previous < 60_000L || !lastWarning.compareAndSet(previous, now)) return;
        plugin.getLogger().warning("[BoosterRewards] " + message + ": " + rootMessage(error));
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
            java.util.Optional<BoosterStateRepository.BoosterState> state
    ) { }
}
