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

    





    CompletableFuture<Boolean> runForPlayer(UUID playerId, Consumer<Player> action);

    




    <T> CompletableFuture<Optional<T>> callForPlayer(UUID playerId, Function<Player, T> function);

    CoreTask runAtLocation(Location location, Runnable task);

    <T> CompletableFuture<T> callAtLocation(Location location, Supplier<T> supplier);

    CoreTask runAtLocationLater(Location location, Runnable task, long delayTicks);

    void shutdown();
}
