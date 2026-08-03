package com.hubertstudios.coredsc.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Bukkit/Spigot fallback used only when Paper's scheduler family is absent. */
public final class PaperCoreScheduler implements CoreScheduler {
    private final JavaPlugin plugin;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PaperCoreScheduler(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public SchedulerRuntime runtime() {
        return SchedulerRuntime.SPIGOT;
    }

    @Override
    public boolean isGlobalThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void runGlobal(Runnable task) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public <T> CompletableFuture<T> callGlobal(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            runGlobal(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    @Override
    public CoreTask runGlobalLater(Runnable task, long delayTicks) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(
                plugin, task, Math.max(0L, delayTicks));
        return scheduled::cancel;
    }

    @Override
    public CoreTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        if (periodTicks < 1L) {
            throw new IllegalArgumentException("periodTicks must be at least 1");
        }
        BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(
                plugin, task, Math.max(0L, delayTicks), periodTicks);
        return scheduled::cancel;
    }

    @Override
    public CoreTask runAsync(Runnable task) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        BukkitTask scheduled = Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        return scheduled::cancel;
    }

    @Override
    public CoreTask runAsyncLater(Runnable task, long delayTicks) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        BukkitTask scheduled = Bukkit.getScheduler().runTaskLaterAsynchronously(
                plugin, task, Math.max(0L, delayTicks));
        return scheduled::cancel;
    }

    @Override
    public CoreTask runForEntity(Entity entity, Runnable task) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        // Paper has one global tick thread. A Folia implementation will route
        // this method through the entity scheduler instead.
        if (Bukkit.isPrimaryThread()) {
            ensureOpen();
            task.run();
            return CoreTask.noop();
        }
        return runGlobalLater(task, 0L);
    }

    @Override
    public <T> CompletableFuture<T> callForEntity(Entity entity, Supplier<T> supplier) {
        Objects.requireNonNull(entity, "entity");
        return callGlobal(supplier);
    }

    @Override
    public CoreTask runForEntityLater(Entity entity, Runnable task, long delayTicks) {
        Objects.requireNonNull(entity, "entity");
        return runGlobalLater(task, delayTicks);
    }

    @Override
    public CompletableFuture<Boolean> runForPlayer(UUID playerId, Consumer<Player> action) {
        Objects.requireNonNull(action, "action");
        return callForPlayer(playerId, player -> {
            action.accept(player);
            return Boolean.TRUE;
        }).thenApply(Optional::isPresent);
    }

    @Override
    public <T> CompletableFuture<Optional<T>> callForPlayer(
            UUID playerId,
            Function<Player, T> function
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(function, "function");
        return callGlobal(() -> {
            Player player = Bukkit.getPlayer(playerId);
            return player == null || !player.isOnline()
                    ? Optional.empty()
                    : Optional.ofNullable(function.apply(player));
        });
    }

    @Override
    public CoreTask runAtLocation(Location location, Runnable task) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(task, "task");
        // Paper has one global tick thread. A Folia implementation will route
        // this method through the region scheduler instead.
        if (Bukkit.isPrimaryThread()) {
            ensureOpen();
            task.run();
            return CoreTask.noop();
        }
        return runGlobalLater(task, 0L);
    }

    @Override
    public <T> CompletableFuture<T> callAtLocation(Location location, Supplier<T> supplier) {
        Objects.requireNonNull(location, "location");
        return callGlobal(supplier);
    }

    @Override
    public CoreTask runAtLocationLater(Location location, Runnable task, long delayTicks) {
        Objects.requireNonNull(location, "location");
        return runGlobalLater(task, delayTicks);
    }

    @Override
    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    private void ensureOpen() {
        if (closed.get() || !plugin.isEnabled()) {
            throw new IllegalStateException("CoreDSC scheduler is unavailable");
        }
    }
}
