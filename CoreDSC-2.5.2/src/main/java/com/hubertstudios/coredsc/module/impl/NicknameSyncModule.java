package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.event.AccountLinkedEvent;
import com.hubertstudios.coredsc.event.AccountUnlinkedEvent;
import com.hubertstudios.coredsc.event.DiscordReadyEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.NicknameStateRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

                                                                                     
public final class NicknameSyncModule implements CoreModule, Listener {
    private final CoreDSCPlugin plugin;
    private final AtomicLong lastWarning = new AtomicLong();
    private final AtomicLong operationSequence = new AtomicLong();
    private final ConcurrentHashMap<String, Long> operationVersions = new ConcurrentHashMap<>();
    private LinkedAccountRepository accounts;
    private NicknameStateRepository states;
    private ListenerAdapter discordListener;
    private String guildId;
    private String format;
    private boolean restoreOnUnlink;
    private volatile boolean active;

    public NicknameSyncModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "nickname-sync";
    }

    @Override
    public void enable() {
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null) throw new IllegalStateException("Discord service is not initialised");
        FileConfiguration config = plugin.getAppConfig();
        guildId = value(config, "nickname-sync.guild-id", value(config, "discord.guild-id", ""));
        validateSnowflake("nickname-sync.guild-id", guildId);
        if (guildId.isBlank()) throw new IllegalArgumentException("nickname-sync.guild-id or discord.guild-id is required");
        format = value(config, "nickname-sync.format", "%player%");
        if (format.isBlank() || containsControl(format)) {
            throw new IllegalArgumentException("nickname-sync.format must be non-empty and contain no control characters");
        }
        restoreOnUnlink = config.getBoolean("nickname-sync.restore-on-unlink", true);
        accounts = new LinkedAccountRepository(plugin.getStorage());
        states = new NicknameStateRepository(plugin.getStorage());
        active = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        discordListener = new ListenerAdapter() {
            @Override
            public void onGuildMemberJoin(GuildMemberJoinEvent event) {
                if (!active || !event.getGuild().getId().equals(guildId)) return;
                LinkedAccountRepository repository = accounts;
                if (repository == null) return;
                repository.findByDiscordUserId(event.getUser().getId()).whenComplete((account, error) -> {
                    if (!active) return;
                    if (error != null) {
                        warn("Could not look up linked account for a joining Discord member", error);
                    } else if (account.isPresent()) {
                        LinkedAccountRepository.LinkedAccount linked = account.get();
                        sync(linked.discordUserId(), linked.minecraftName());
                    } else {
                        restore(event.getUser().getId());
                    }
                });
            }
        };
        discord.addEventListener(discordListener);
        if (discord.isReady()) syncAll();
    }

    @Override
    public String statusDetail() {
        return "guild=" + guildId + ", restore-on-unlink=" + restoreOnUnlink;
    }

    @Override
    public void disable() {
        active = false;
        HandlerList.unregisterAll(this);
        DiscordBotService discord = plugin.getDiscordService();
        if (discordListener != null && discord != null) discord.removeEventListener(discordListener);
        discordListener = null;
        operationVersions.clear();
        accounts = null;
        states = null;
    }

    @EventHandler
    public void onDiscordReady(DiscordReadyEvent event) {
        if (active) syncAll();
    }

    @EventHandler
    public void onAccountLinked(AccountLinkedEvent event) {
        if (active) sync(event.discordUserId(), event.minecraftName());
    }

    @EventHandler
    public void onAccountUnlinked(AccountUnlinkedEvent event) {
        if (active) restore(event.discordUserId());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        LinkedAccountRepository repository = accounts;
        if (!active || repository == null) return;
        UUID uuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();
        repository.findByMinecraftUuid(uuid.toString()).whenComplete((account, error) -> {
            if (!active) return;
            if (error != null) {
                warn("Could not look up nickname sync account for " + playerName, error);
                return;
            }
            account.ifPresent(value -> repository.updateMinecraftName(uuid.toString(), playerName)
                    .whenComplete((ignored, updateError) -> {
                        if (!active) return;
                        if (updateError != null) {
                            warn("Could not update the stored Minecraft name for " + playerName, updateError);
                            return;
                        }
                        sync(value.discordUserId(), playerName);
                    }));
        });
    }

    private void syncAll() {
        LinkedAccountRepository repository = accounts;
        if (repository == null) return;
        repository.findAll().whenComplete((linked, error) -> {
            if (!active) return;
            if (error != null) {
                warn("Could not list linked accounts for nickname sync", error);
                return;
            }
            for (LinkedAccountRepository.LinkedAccount account : linked) {
                sync(account.discordUserId(), account.minecraftName());
            }
        });
    }

    private void sync(String discordUserId, String minecraftName) {
        LinkedAccountRepository accountRepository = accounts;
        NicknameStateRepository repository = states;
        if (!active || accountRepository == null || repository == null
                || minecraftName == null || minecraftName.isBlank() || !validSnowflake(discordUserId)) return;
        long operation = beginOperation(discordUserId);
                                                                                   
                                                                          
        accountRepository.findByDiscordUserId(discordUserId).whenComplete((linked, linkError) -> {
            if (!isCurrent(discordUserId, operation)) return;
            if (linkError != null) {
                warn("Could not verify nickname link for " + discordUserId, linkError);
                return;
            }
            if (linked.isEmpty()) {
                restore(discordUserId);
                return;
            }
            Guild guild = guild();
            if (guild == null) return;
            guild.retrieveMemberById(discordUserId).queue(member -> {
                if (!isCurrent(discordUserId, operation)) return;
                if (!canModify(guild, member)) {
                    warn("Cannot change nickname for Discord member " + discordUserId + " due to role hierarchy", null);
                    return;
                }
                String nickname = sanitizeNickname(format.replace("%player%", minecraftName)
                        .replace("%discord_id%", discordUserId));
                if (nickname.isBlank()) {
                    warn("Rendered nickname is empty for Discord member " + discordUserId, null);
                    return;
                }
                String original = member.getNickname() == null ? "" : member.getNickname();
                repository.saveOriginalIfAbsent(discordUserId, original, nickname, System.currentTimeMillis())
                        .whenComplete((ignored, dbError) -> {
                            if (dbError != null) {
                                warn("Could not save original nickname for " + discordUserId, dbError);
                                return;
                            }
                            if (!isCurrent(discordUserId, operation)) return;
                            guild.modifyNickname(member, nickname).reason("CoreDSC nickname synchronization")
                                    .queue(done -> {
                                                plugin.recordFeatureUse("nickname_sync");
                                                if (isCurrent(discordUserId, operation)) {
                                                    repository.updateSynced(discordUserId, nickname, System.currentTimeMillis())
                                                            .exceptionally(updateError -> {
                                                                warn("Nickname changed but sync state could not be persisted for " + discordUserId, updateError);
                                                                return null;
                                                            });
                                                } else {
                                                    reconcileCurrent(discordUserId);
                                                }
                                            },
                                            error -> warn("Could not synchronize nickname for " + discordUserId, error));
                        });
            }, error -> warn("Discord member " + discordUserId + " is not available for nickname sync", error));
        });
    }

    private void restore(String discordUserId) {
        NicknameStateRepository repository = states;
        if (!active || repository == null || !validSnowflake(discordUserId)) return;
        long operation = beginOperation(discordUserId);
        repository.find(discordUserId).whenComplete((stored, dbError) -> {
            if (!isCurrent(discordUserId, operation)) return;
            if (dbError != null) {
                warn("Could not read nickname restoration state for " + discordUserId, dbError);
                return;
            }
            if (stored.isEmpty()) return;
            if (!restoreOnUnlink) {
                repository.delete(discordUserId);
                return;
            }
            Guild guild = guild();
            if (guild == null) return;
            guild.retrieveMemberById(discordUserId).queue(member -> {
                if (!isCurrent(discordUserId, operation)) return;
                if (!canModify(guild, member)) {
                    warn("Cannot restore nickname for Discord member " + discordUserId + " due to role hierarchy", null);
                    return;
                }
                String current = member.getNickname() == null ? "" : member.getNickname();
                                                                                             
                if (!current.equals(stored.get().syncedNickname())) {
                    repository.delete(discordUserId);
                    return;
                }
                String original = stored.get().originalNickname().isBlank() ? null : stored.get().originalNickname();
                guild.modifyNickname(member, original).reason("CoreDSC account unlink")
                        .queue(ignored -> {
                                    plugin.recordFeatureUse("nickname_sync");
                                    if (isCurrent(discordUserId, operation)) repository.delete(discordUserId);
                                    else reconcileCurrent(discordUserId);
                                },
                                error -> warn("Could not restore nickname for " + discordUserId, error));
            }, error -> warn("Discord member " + discordUserId + " is unavailable for nickname restoration", error));
        });
    }

    private void reconcileCurrent(String discordUserId) {
        LinkedAccountRepository repository = accounts;
        if (!active || repository == null || !validSnowflake(discordUserId)) return;
        repository.findByDiscordUserId(discordUserId).whenComplete((linked, error) -> {
            if (!active) return;
            if (error != null) {
                warn("Could not reconcile current nickname state for " + discordUserId, error);
            } else if (linked.isPresent()) {
                sync(discordUserId, linked.get().minecraftName());
            } else {
                restore(discordUserId);
            }
        });
    }

    private long beginOperation(String discordUserId) {
        long operation = operationSequence.incrementAndGet();
        operationVersions.put(discordUserId, operation);
        return operation;
    }

    private boolean isCurrent(String discordUserId, long operation) {
        return active && Objects.equals(operationVersions.get(discordUserId), operation);
    }

    private Guild guild() {
        DiscordBotService discord = plugin.getDiscordService();
        JDA jda = discord == null ? null : discord.getJda();
        if (jda == null || !discord.isReady()) return null;
        return jda.getGuildById(guildId);
    }

    private static boolean canModify(Guild guild, Member member) {
        return !member.isOwner()
                && guild.getSelfMember().hasPermission(Permission.NICKNAME_MANAGE)
                && guild.getSelfMember().canInteract(member);
    }

    private void warn(String message, Throwable error) {
        if (error == null) plugin.recordModuleFailure("nickname-sync", message);
        else plugin.recordModuleFailure("nickname-sync", error);
        long now = System.currentTimeMillis();
        long previous = lastWarning.get();
        if (now - previous < 60_000L || !lastWarning.compareAndSet(previous, now)) return;
        plugin.getLogger().warning("[NicknameSync] " + message + (error == null ? "" : ": " + rootMessage(error)));
    }

    private static String value(FileConfiguration config, String path, String fallback) {
        String value = config.getString(path, fallback);
        return value == null ? fallback : value.trim();
    }

    private static boolean validSnowflake(String value) {
        if (value == null || value.isBlank()) return false;
        try { return Long.parseUnsignedLong(value) != 0L; }
        catch (NumberFormatException exception) { return false; }
    }

    private static void validateSnowflake(String path, String value) {
        if (value.isBlank()) return;
        try { if (Long.parseUnsignedLong(value) == 0L) throw new NumberFormatException("zero"); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(path + " must be a positive Discord ID", exception); }
    }

    private static String sanitizeNickname(String value) {
        String clean = value == null ? "" : value.replace('\0', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        int codePoints = clean.codePointCount(0, clean.length());
        if (codePoints <= 32) return clean;
        return clean.substring(0, clean.offsetByCodePoints(0, 32));
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = Objects.requireNonNull(throwable);
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
