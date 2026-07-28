package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.module.DiscordCommandContributor;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository.LinkedAccount;
import com.hubertstudios.coredsc.storage.PasswordResetLogRepository;
import com.hubertstudios.coredsc.storage.PasswordResetLogRepository.ResetReservation;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Optional AuthMeReloaded password-reset integration. */
public final class AuthMeModule implements CoreModule, DiscordCommandContributor {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final CoreDSCPlugin plugin;
    private LinkedAccountRepository linkedAccounts;
    private PasswordResetLogRepository resetLogs;
    private Object authMeApi;
    private Method changePassword;
    private ListenerAdapter slashListener;
    private long cooldownMillis;
    private int temporaryPasswordLength;
    private boolean onlyWhenPlayerOffline;
    private boolean auditLog;

    public AuthMeModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "authme";
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

        Plugin authMePlugin = plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if (authMePlugin == null || !authMePlugin.isEnabled()) {
            throw new IllegalStateException("AuthMeReloaded is not installed or not enabled");
        }

        try {
            Class<?> apiClass = Class.forName(
                    "fr.xephi.authme.api.v3.AuthMeApi",
                    true,
                    authMePlugin.getClass().getClassLoader());
            authMeApi = apiClass.getMethod("getInstance").invoke(null);
            changePassword = apiClass.getMethod("changePassword", String.class, String.class);
            if (authMeApi == null) {
                throw new IllegalStateException("AuthMeApi.getInstance() returned null");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("AuthMe v3 API is unavailable", exception);
        }

        FileConfiguration config = plugin.getAppConfig();
        long cooldownHours = clamp(config.getLong("authme.reset-cooldown-hours", 24L), 0L, 720L);
        cooldownMillis = cooldownHours * 3_600_000L;
        temporaryPasswordLength = (int) clamp(
                config.getLong("authme.temporary-password-length", 12L), 8L, 64L);
        onlyWhenPlayerOffline = config.getBoolean("authme.only-when-player-offline", true);
        auditLog = config.getBoolean("authme.audit-log", true);

        linkedAccounts = new LinkedAccountRepository(storage);
        resetLogs = new PasswordResetLogRepository(storage);
        slashListener = new ListenerAdapter() {
            @Override
            public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
                if (event.getName().equals("resetpassword")) {
                    handleReset(event);
                }
            }
        };
        discord.addEventListener(slashListener);
    }


    @Override
    public List<CommandData> slashCommands() {
        return List.of(Commands.slash("resetpassword",
                "Reset the password of your linked Minecraft account"));
    }

    @Override
    public void disable() {
        DiscordBotService discord = plugin.getDiscordService();
        if (slashListener != null && discord != null) {
            discord.removeEventListener(slashListener);
        }
        slashListener = null;
        authMeApi = null;
        changePassword = null;
    }

    private void handleReset(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook ->
                linkedAccounts.findByDiscordUserId(event.getUser().getId())
                        .whenComplete((account, error) -> {
                            if (error != null) {
                                plugin.getLogger().log(Level.WARNING,
                                        "[AuthMe] Linked-account lookup failed", error);
                                edit(hook, "CoreDSC could not retrieve your linked account.");
                                return;
                            }
                            if (account.isEmpty()) {
                                edit(hook, "Your Discord account is not linked. Link it before resetting a password.");
                                return;
                            }
                            reserveAndReset(event, hook, account.get());
                        }),
                error -> plugin.getLogger().warning("[AuthMe] Could not defer /resetpassword: "
                        + rootMessage(error))
        );
    }

    private void reserveAndReset(
            SlashCommandInteractionEvent event,
            InteractionHook hook,
            LinkedAccount account
    ) {
        long now = System.currentTimeMillis();

        resetLogs.reserveReset(account.minecraftUuid(), event.getUser().getId(), now, cooldownMillis)
                .whenComplete((reservation, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "[AuthMe] Could not reserve a password reset", error);
                        edit(hook, "CoreDSC could not process the password-reset cooldown.");
                        return;
                    }
                    if (!reservation.allowed()) {
                        edit(hook, "You must wait " + formatRemaining(reservation.remainingMillis())
                                + " before resetting this password again.");
                        return;
                    }
                    executeReset(event, hook, account, reservation);
                });
    }

    private void executeReset(
            SlashCommandInteractionEvent event,
            InteractionHook hook,
            LinkedAccount account,
            ResetReservation reservation
    ) {
        String temporaryPassword = randomPassword(temporaryPasswordLength);

        CompletableFuture<ResetExecution> execution = plugin.callSync(() -> {
            UUID minecraftUuid;
            try {
                minecraftUuid = UUID.fromString(account.minecraftUuid());
            } catch (IllegalArgumentException exception) {
                return ResetExecution.failure("The stored Minecraft UUID is invalid.");
            }

            if (onlyWhenPlayerOffline
                    && plugin.getServer().getPlayer(minecraftUuid) != null) {
                return ResetExecution.failure(
                        "Log out of the Minecraft server before resetting your password.");
            }

            OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(minecraftUuid);
            String currentName = offlinePlayer.getName();
            String playerName = currentName == null || currentName.isBlank()
                    ? account.minecraftName()
                    : currentName;
            if (playerName == null || playerName.isBlank()) {
                return ResetExecution.failure(
                        "CoreDSC does not know the Minecraft name for this linked account.");
            }

            try {
                changePassword.invoke(authMeApi, playerName, temporaryPassword);
                return ResetExecution.success(playerName);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("AuthMe API access was denied", exception);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                throw new IllegalStateException("AuthMe rejected the password change: "
                        + rootMessage(cause), cause);
            }
        });

        execution.whenComplete((result, error) -> {
            if (error != null || result == null || !result.success()) {
                resetLogs.removeReservation(account.minecraftUuid(), reservation.resetAt())
                        .exceptionally(cleanupError -> {
                            plugin.getLogger().warning("[AuthMe] Could not roll back a failed reset reservation: "
                                    + rootMessage(cleanupError));
                            return null;
                        });
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE,
                            "[AuthMe] Password reset failed", error);
                    edit(hook, "AuthMe could not reset the password. Contact a server administrator.");
                } else {
                    edit(hook, result.message());
                }
                return;
            }

            if (auditLog) {
                plugin.getLogger().info("[AuthMe] Password reset requested for "
                        + result.playerName() + " by Discord user "
                        + event.getUser().getName() + " (" + event.getUser().getId() + ").");
            }
            edit(hook, "CoreDSC submitted the reset to AuthMe. Temporary password: **"
                    + temporaryPassword
                    + "**\nChange it after logging in. If AuthMe rejects the request, contact an administrator.");
        });
    }

    private static String randomPassword(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return builder.toString();
    }

    private static String formatRemaining(long milliseconds) {
        long totalMinutes = Math.max(1L, (milliseconds + 59_999L) / 60_000L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours == 0L) {
            return minutes + " minute(s)";
        }
        return hours + " hour(s) and " + minutes + " minute(s)";
    }

    private void edit(InteractionHook hook, String message) {
        hook.editOriginal(message).queue(
                ignored -> { },
                error -> plugin.getLogger().warning(
                        "[AuthMe] Could not deliver a Discord interaction response: "
                                + rootMessage(error))
        );
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
        return message == null ? current.getClass().getSimpleName() : message;
    }

    private record ResetExecution(boolean success, String playerName, String message) {
        private static ResetExecution success(String playerName) {
            return new ResetExecution(true, playerName, "");
        }

        private static ResetExecution failure(String message) {
            return new ResetExecution(false, "", message);
        }
    }
}
