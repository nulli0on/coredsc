package com.hubertstudios.coredsc.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * CoreDSC scheduling boundary.
 *
 * <p>Global, region, entity and asynchronous work are deliberately separate.
 * A global-region callback is <strong>not</strong> an entity callback on Folia.
 * Callers which touch a player/entity or a location-owned world object must use
 * the matching entity/region method.</p>
 */
public interface CoreScheduler {
    SchedulerRuntime runtime();

    boolean isGlobalThread();

    void runGlobal(Runnable task);

    <T> CompletableFuture<T> callGlobal(Supplier<T> supplier);

    CoreTask runGlobalLater(Runnable task, long delayTicks);

    CoreTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks);

    CoreTask runAsync(Runnable task);

    CoreTask runAsyncLater(Runnable task, long delayTicks);

    CoreTask runForEntity(Entity entity, Runnable task);

    <T> CompletableFuture<T> callForEntity(Entity entity, Supplier<T> supplier);

    CoreTask runForEntityLater(Entity entity, Runnable task, long delayTicks);

    /**
     * Resolves an online player by UUID and runs the action on the scheduler
     * which owns that player. The caller never receives or retains a Bukkit
     * Player reference. The future is {@code false} when the player is offline
     * or retires before the action can run.
     */
    CompletableFuture<Boolean> runForPlayer(UUID playerId, Consumer<Player> action);

    /**
     * Resolves an online player by UUID, evaluates the function on the player's
     * owning scheduler and returns only the detached result. An empty optional
     * means that the player was offline or retired before execution.
     */
    <T> CompletableFuture<Optional<T>> callForPlayer(UUID playerId, Function<Player, T> function);

    CoreTask runAtLocation(Location location, Runnable task);

    <T> CompletableFuture<T> callAtLocation(Location location, Supplier<T> supplier);

    CoreTask runAtLocationLater(Location location, Runnable task, long delayTicks);

    void shutdown();
}
