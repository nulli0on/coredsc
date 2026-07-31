package com.hubertstudios.coredsc.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

                                                            
public final class PaperCoreScheduler implements CoreScheduler {
    private final JavaPlugin plugin;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PaperCoreScheduler(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
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
    public CoreTask runForEntity(Entity entity, Runnable task) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
                                                                            
                                                          
        if (Bukkit.isPrimaryThread()) {
            ensureOpen();
            task.run();
            return CoreTask.noop();
        }
        return runGlobalLater(task, 0L);
    }

    @Override
    public CoreTask runAtLocation(Location location, Runnable task) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(task, "task");
                                                                            
                                                          
        if (Bukkit.isPrimaryThread()) {
            ensureOpen();
            task.run();
            return CoreTask.noop();
        }
        return runGlobalLater(task, 0L);
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
