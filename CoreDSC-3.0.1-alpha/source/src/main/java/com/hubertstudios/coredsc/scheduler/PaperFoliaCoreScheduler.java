package com.hubertstudios.coredsc.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


public final class PaperFoliaCoreScheduler implements CoreScheduler {
    private static final String FOLIA_SERVER =
            "io.papermc.paper.threadedregions.RegionizedServer";

    private final JavaPlugin plugin;
    private final SchedulerRuntime runtime;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<ScheduledTask> trackedTasks = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> trackedFutures = ConcurrentHashMap.newKeySet();

    public PaperFoliaCoreScheduler(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = classAvailable(FOLIA_SERVER) ? SchedulerRuntime.FOLIA : SchedulerRuntime.PAPER;
    }

    @Override
    public SchedulerRuntime runtime() {
        return runtime;
    }

    @Override
    public boolean isGlobalThread() {
        
        
        return runtime == SchedulerRuntime.PAPER && Bukkit.isPrimaryThread();
    }

    @Override
    public void runGlobal(Runnable task) {
        scheduleGlobal(task, 0L);
    }

    @Override
    public <T> CompletableFuture<T> callGlobal(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        CompletableFuture<T> future = trackedFuture();
        try {
            runGlobal(() -> complete(future, supplier));
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    @Override
    public CoreTask runGlobalLater(Runnable task, long delayTicks) {
        return scheduleGlobal(task, delayTicks);
    }

    @Override
    public CoreTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        if (periodTicks < 1L) {
            throw new IllegalArgumentException("periodTicks must be at least 1");
        }
        ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                ignored -> runGuarded(task),
                Math.max(1L, delayTicks),
                periodTicks);
        return track(scheduled);
    }

    @Override
    public CoreTask runAsync(Runnable task) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        return trackOneShot((ref, completed) -> Bukkit.getAsyncScheduler().runNow(
                plugin,
                ignored -> finishOneShot(ref, completed, task)));
    }

    @Override
    public CoreTask runAsyncLater(Runnable task, long delayTicks) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        if (delayTicks <= 0L) {
            return runAsync(task);
        }
        long delayMillis = Math.max(1L, Math.multiplyExact(delayTicks, 50L));
        return trackOneShot((ref, completed) -> Bukkit.getAsyncScheduler().runDelayed(
                plugin,
                ignored -> finishOneShot(ref, completed, task),
                delayMillis,
                TimeUnit.MILLISECONDS));
    }

    @Override
    public CoreTask runForEntity(Entity entity, Runnable task) {
        return scheduleEntity(entity, task, 0L);
    }

    @Override
    public <T> CompletableFuture<T> callForEntity(Entity entity, Supplier<T> supplier) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(supplier, "supplier");
        CompletableFuture<T> future = trackedFuture();
        try {
            ensureOpen();
            AtomicReference<ScheduledTask> ref = new AtomicReference<>();
            AtomicBoolean completed = new AtomicBoolean();
            ScheduledTask scheduled = entity.getScheduler().run(
                    plugin,
                    ignored -> {
                        try {
                            complete(future, supplier);
                        } finally {
                            finishTracking(ref, completed);
                        }
                    },
                    () -> {
                        future.completeExceptionally(new IllegalStateException(
                                "Entity retired before the scheduled CoreDSC operation could run"));
                        finishTracking(ref, completed);
                    });
            registerOneShot(ref, completed, scheduled, future);
            return future;
        } catch (Throwable error) {
            future.completeExceptionally(error);
            return future;
        }
    }

    @Override
    public CoreTask runForEntityLater(Entity entity, Runnable task, long delayTicks) {
        return scheduleEntity(entity, task, delayTicks);
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
        CompletableFuture<Optional<T>> future = trackedFuture();
        try {
            ensureOpen();
            AtomicReference<ScheduledTask> lookupRef = new AtomicReference<>();
            AtomicBoolean lookupCompleted = new AtomicBoolean();
            ScheduledTask lookup = Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> {
                try {
                    if (closed.get() || !plugin.isEnabled()) {
                        future.completeExceptionally(new IllegalStateException(
                                "CoreDSC stopped before the player lookup could run"));
                        return;
                    }
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null) {
                        future.complete(Optional.empty());
                        return;
                    }
                    scheduleResolvedPlayer(player, function, future);
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                } finally {
                    finishTracking(lookupRef, lookupCompleted);
                }
            });
            registerOneShot(lookupRef, lookupCompleted, lookup, future);
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    @Override
    public CoreTask runAtLocation(Location location, Runnable task) {
        return scheduleLocation(location, task, 0L);
    }

    @Override
    public <T> CompletableFuture<T> callAtLocation(Location location, Supplier<T> supplier) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(location.getWorld(), "location.world");
        Objects.requireNonNull(supplier, "supplier");
        CompletableFuture<T> future = trackedFuture();
        try {
            scheduleLocation(location, () -> complete(future, supplier), 0L);
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    @Override
    public CoreTask runAtLocationLater(Location location, Runnable task, long delayTicks) {
        return scheduleLocation(location, task, delayTicks);
    }

    @Override
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IllegalStateException shutdown = new IllegalStateException(
                "CoreDSC scheduler shut down before the operation completed");
        for (CompletableFuture<?> future : Set.copyOf(trackedFutures)) {
            future.completeExceptionally(shutdown);
        }
        trackedFutures.clear();
        for (ScheduledTask task : Set.copyOf(trackedTasks)) {
            try {
                task.cancel();
            } catch (RuntimeException error) {
                plugin.getLogger().fine("Could not cancel a CoreDSC scheduler task: " + error.getMessage());
            }
        }
        trackedTasks.clear();
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    private CoreTask scheduleGlobal(Runnable task, long delayTicks) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        return trackOneShot((ref, completed) -> delayTicks <= 0L
                ? Bukkit.getGlobalRegionScheduler().run(
                        plugin, ignored -> finishOneShot(ref, completed, task))
                : Bukkit.getGlobalRegionScheduler().runDelayed(
                        plugin, ignored -> finishOneShot(ref, completed, task), delayTicks));
    }

    private CoreTask scheduleEntity(Entity entity, Runnable task, long delayTicks) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        ensureOpen();
        AtomicReference<ScheduledTask> ref = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        Runnable retired = () -> finishTracking(ref, completed);
        ScheduledTask scheduled = delayTicks <= 0L
                ? entity.getScheduler().run(
                        plugin,
                        ignored -> finishOneShot(ref, completed, task),
                        retired)
                : entity.getScheduler().runDelayed(
                        plugin,
                        ignored -> finishOneShot(ref, completed, task),
                        retired,
                        delayTicks);
        return registerOneShot(ref, completed, scheduled, null);
    }

    private CoreTask scheduleLocation(Location location, Runnable task, long delayTicks) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(location.getWorld(), "location.world");
        Objects.requireNonNull(task, "task");
        ensureOpen();
        return trackOneShot((ref, completed) -> delayTicks <= 0L
                ? Bukkit.getRegionScheduler().run(
                        plugin, location, ignored -> finishOneShot(ref, completed, task))
                : Bukkit.getRegionScheduler().runDelayed(
                        plugin, location, ignored -> finishOneShot(ref, completed, task), delayTicks));
    }

    private <T> void scheduleResolvedPlayer(
            Player player,
            Function<Player, T> function,
            CompletableFuture<Optional<T>> future
    ) {
        AtomicReference<ScheduledTask> entityRef = new AtomicReference<>();
        AtomicBoolean entityCompleted = new AtomicBoolean();
        ScheduledTask scheduled = player.getScheduler().run(
                plugin,
                ignored -> {
                    try {
                        if (closed.get() || !plugin.isEnabled()) {
                            future.completeExceptionally(new IllegalStateException(
                                    "CoreDSC stopped before the player operation could run"));
                        } else if (!player.isOnline()) {
                            future.complete(Optional.empty());
                        } else {
                            future.complete(Optional.ofNullable(function.apply(player)));
                        }
                    } catch (Throwable error) {
                        future.completeExceptionally(error);
                    } finally {
                        finishTracking(entityRef, entityCompleted);
                    }
                },
                () -> {
                    future.complete(Optional.empty());
                    finishTracking(entityRef, entityCompleted);
                });
        registerOneShot(entityRef, entityCompleted, scheduled, future);
    }

    private CoreTask trackOneShot(TaskFactory factory) {
        AtomicReference<ScheduledTask> ref = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        ScheduledTask task = factory.create(ref, completed);
        return registerOneShot(ref, completed, task, null);
    }

    private CoreTask registerOneShot(
            AtomicReference<ScheduledTask> ref,
            AtomicBoolean completed,
            ScheduledTask task,
            CompletableFuture<?> future
    ) {
        if (task == null) {
            if (future != null) {
                future.completeExceptionally(new IllegalStateException(
                        "Scheduler rejected the task because its entity is retired"));
            }
            return CoreTask.noop();
        }
        ref.set(task);
        trackedTasks.add(task);
        if (completed.get()) {
            trackedTasks.remove(task);
        }
        return () -> {
            trackedTasks.remove(task);
            task.cancel();
        };
    }

    private CoreTask track(ScheduledTask task) {
        trackedTasks.add(task);
        return () -> {
            trackedTasks.remove(task);
            task.cancel();
        };
    }

    private void finishOneShot(
            AtomicReference<ScheduledTask> ref,
            AtomicBoolean completed,
            Runnable task
    ) {
        try {
            runGuarded(task);
        } finally {
            finishTracking(ref, completed);
        }
    }

    private void finishTracking(AtomicReference<ScheduledTask> ref, AtomicBoolean completed) {
        completed.set(true);
        ScheduledTask current = ref.get();
        if (current != null) {
            trackedTasks.remove(current);
        }
    }

    private void runGuarded(Runnable task) {
        if (!closed.get() && plugin.isEnabled()) {
            task.run();
        }
    }

    private void ensureOpen() {
        if (closed.get() || !plugin.isEnabled()) {
            throw new IllegalStateException("CoreDSC scheduler is unavailable");
        }
    }

    private <T> CompletableFuture<T> trackedFuture() {
        CompletableFuture<T> future = new CompletableFuture<>();
        trackedFutures.add(future);
        future.whenComplete((ignored, error) -> trackedFutures.remove(future));
        return future;
    }

    private static <T> void complete(CompletableFuture<T> future, Supplier<T> supplier) {
        try {
            future.complete(supplier.get());
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
    }

    private static boolean classAvailable(String name) {
        try {
            Class.forName(name, false, PaperFoliaCoreScheduler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    @FunctionalInterface
    private interface TaskFactory {
        ScheduledTask create(
                AtomicReference<ScheduledTask> reference,
                AtomicBoolean completed
        );
    }
}
