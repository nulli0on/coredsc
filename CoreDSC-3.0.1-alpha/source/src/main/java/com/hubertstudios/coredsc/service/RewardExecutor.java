package com.hubertstudios.coredsc.service;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.storage.RewardClaimRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;


public final class RewardExecutor {
    private final CoreDSCPlugin plugin;
    private final RewardClaimRepository claims;
    private final ConcurrentHashMap<String, Boolean> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Runnable> completionCallbacks = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);

    public RewardExecutor(CoreDSCPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.claims = new RewardClaimRepository(plugin.getStorage());
    }

    public void grant(String claimKey, String rewardType, UUID minecraftUuid, String playerName,
                      String discordUserId, List<String> commands) {
        grant(claimKey, rewardType, minecraftUuid, playerName, discordUserId, commands, null);
    }

    public void grant(String claimKey, String rewardType, UUID minecraftUuid, String playerName,
                      String discordUserId, List<String> commands, Runnable onCompleted) {
        if (!active.get()) return;
        List<String> safeCommands = normalizedCommands(commands);
        if (safeCommands.isEmpty()) return;
        if (onCompleted != null) completionCallbacks.put(claimKey, onCompleted);
        long now = System.currentTimeMillis();
        claims.reserve(claimKey, rewardType, minecraftUuid.toString(), safe(discordUserId), safeCommands.size(), now)
                .whenComplete((claim, error) -> {
                    if (!active.get()) return;
                    if (error != null) {
                        completionCallbacks.remove(claimKey);
                        warn("Could not reserve reward claim " + claimKey, error);
                        return;
                    }
                    executeClaim(claim, playerName, safeCommands);
                });
    }

    public void resume(String rewardType, List<String> commands) {
        if (!active.get()) return;
        List<String> safeCommands = normalizedCommands(commands);
        if (safeCommands.isEmpty()) return;
        claims.findResumable(rewardType, 250).whenComplete((pending, error) -> {
            if (!active.get()) return;
            if (error != null) {
                warn("Could not read resumable " + rewardType + " rewards", error);
                return;
            }
            for (RewardClaimRepository.Claim claim : pending) {
                if (claim.totalSteps() != safeCommands.size()) {
                    quarantine(claim.claimKey(), "Configured reward command count changed; manual review required");
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(claim.minecraftUuid());
                } catch (IllegalArgumentException exception) {
                    quarantine(claim.claimKey(), "Invalid stored Minecraft UUID");
                    continue;
                }
                plugin.callSync(() -> {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                    return offline.getName();
                }).whenComplete((playerName, nameError) -> {
                    if (!active.get()) return;
                    if (nameError != null || playerName == null || playerName.isBlank()) {
                        quarantine(claim.claimKey(), "Minecraft name is unavailable; manual review required");
                        return;
                    }
                    executeClaim(claim, playerName, safeCommands);
                });
            }
        });
    }

    
    public void shutdown() {
        active.set(false);
        running.clear();
        completionCallbacks.clear();
    }

    private void executeClaim(RewardClaimRepository.Claim claim, String playerName, List<String> commands) {
        if (!active.get()) return;
        if (claim.status().equals("COMPLETED") || claim.nextStep() >= commands.size()) {
            complete(claim.claimKey());
            return;
        }
        if (!claim.status().equals("PENDING") || claim.inflightStep() >= 0) {
            completionCallbacks.remove(claim.claimKey());
            return;
        }
        if (running.putIfAbsent(claim.claimKey(), Boolean.TRUE) != null) return;
        executeStep(claim, playerName, commands, claim.nextStep());
    }

    private void executeStep(RewardClaimRepository.Claim claim, String playerName, List<String> commands, int step) {
        if (!active.get()) {
            running.remove(claim.claimKey());
            return;
        }
        if (step >= commands.size()) {
            complete(claim.claimKey());
            return;
        }
        String command = render(commands.get(step), playerName, claim.minecraftUuid(), claim.discordUserId());
        if (!validCommand(command)) {
            quarantineAndStop(claim.claimKey(), "Rejected unsafe or empty reward command at step " + step);
            return;
        }

        claims.beginStep(claim.claimKey(), step, System.currentTimeMillis()).whenComplete((began, beginError) -> {
            if (!active.get()) {
                running.remove(claim.claimKey());
                return;
            }
            if (beginError != null || !Boolean.TRUE.equals(began)) {
                quarantineAndStop(claim.claimKey(), beginError == null
                        ? "Reward progress changed concurrently before step " + step
                        : "Could not mark reward step " + step + " in-flight: " + rootMessage(beginError));
                return;
            }

            plugin.callSync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command))
                    .whenComplete((accepted, dispatchError) -> {
                        if (!active.get()) {
                            running.remove(claim.claimKey());
                            return;
                        }
                        if (dispatchError != null) {
                            quarantineAndStop(claim.claimKey(), "Ambiguous command dispatch at step " + step
                                    + ": " + rootMessage(dispatchError));
                            return;
                        }
                        if (!Boolean.TRUE.equals(accepted)) {
                            quarantineAndStop(claim.claimKey(), "Server rejected reward command at step " + step);
                            return;
                        }
                        claims.completeStep(claim.claimKey(), step, System.currentTimeMillis())
                                .whenComplete((completed, dbError) -> {
                                    if (!active.get()) {
                                        running.remove(claim.claimKey());
                                        return;
                                    }
                                    if (dbError != null || !Boolean.TRUE.equals(completed)) {
                                        quarantineAndStop(claim.claimKey(), dbError == null
                                                ? "Executed step " + step + " but persistent progress changed; manual review required"
                                                : "Executed step " + step + " but progress could not be persisted: " + rootMessage(dbError));
                                        return;
                                    }
                                    executeStep(claim, playerName, commands, step + 1);
                                });
                    });
        });
    }

    private void quarantineAndStop(String claimKey, String error) {
        running.remove(claimKey);
        completionCallbacks.remove(claimKey);
        quarantine(claimKey, error);
    }

    private void quarantine(String claimKey, String error) {
        claims.manualReview(claimKey, error, System.currentTimeMillis()).exceptionally(dbError -> {
            warn("Could not record manual-review reward claim " + claimKey, dbError);
            return null;
        });
        plugin.getLogger().warning("[Rewards] " + error + " (claim=" + claimKey + ")");
    }

    private void complete(String claimKey) {
        running.remove(claimKey);
        Runnable callback = completionCallbacks.remove(claimKey);
        if (callback == null || !active.get()) return;
        try {
            callback.run();
        } catch (RuntimeException exception) {
            warn("Reward completion callback failed for " + claimKey, exception);
        }
    }

    private void warn(String message, Throwable error) {
        plugin.getLogger().log(Level.WARNING, "[Rewards] " + message + ": " + rootMessage(error), error);
    }

    private static List<String> normalizedCommands(List<String> commands) {
        return commands == null ? List.of()
                : commands.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String render(String template, String playerName, String uuid, String discordId) {
        return template.replace("%player%", safe(playerName))
                .replace("%uuid%", safe(uuid))
                .replace("%discord_id%", safe(discordId));
    }

    private static boolean validCommand(String command) {
        return command != null && !command.isBlank() && command.length() <= 1000
                && command.indexOf('\n') < 0 && command.indexOf('\r') < 0 && command.indexOf('\0') < 0
                && !command.startsWith("/");
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('\0', ' ');
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = Objects.requireNonNullElseGet(throwable, () -> new IllegalStateException("unknown error"));
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
