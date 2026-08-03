package com.hubertstudios.coredsc.listener;

import com.hubertstudios.coredsc.scheduler.CoreScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Folia-safe PvP death adapter.
 *
 * <p>The event-owned {@link Player} references exist only inside the event
 * callback. Before any asynchronous persistence begins they are reduced to an
 * immutable snapshot of UUIDs, names and a timestamp. Completion handlers keep
 * only that snapshot. Optional player feedback is routed back through the
 * UUID-based entity scheduler boundary.</p>
 */
public final class PlayerDeathListener implements Listener {
    public record DeathSnapshot(
            UUID winnerId,
            String winnerName,
            UUID loserId,
            String loserName,
            long occurredAt
    ) {
        public DeathSnapshot {
            Objects.requireNonNull(winnerId, "winnerId");
            Objects.requireNonNull(loserId, "loserId");
            winnerName = Objects.requireNonNullElse(winnerName, "");
            loserName = Objects.requireNonNullElse(loserName, "");
            if (winnerId.equals(loserId)) {
                throw new IllegalArgumentException("A PvP death needs two different player UUIDs");
            }
        }
    }

    /** Detached response data; blank messages suppress player notification. */
    public record DeathProcessingResult(String winnerMessage, String loserMessage) {
        public DeathProcessingResult {
            winnerMessage = Objects.requireNonNullElse(winnerMessage, "");
            loserMessage = Objects.requireNonNullElse(loserMessage, "");
        }

        public static DeathProcessingResult silent() {
            return new DeathProcessingResult("", "");
        }
    }

    @FunctionalInterface
    public interface DeathProcessor {
        /** Must enqueue non-trivial work and return without blocking the event region. */
        CompletableFuture<DeathProcessingResult> process(DeathSnapshot snapshot);
    }

    private final CoreScheduler scheduler;
    private final BooleanSupplier enabled;
    private final DeathProcessor processor;
    private final Consumer<Throwable> errorHandler;

    public PlayerDeathListener(
            CoreScheduler scheduler,
            BooleanSupplier enabled,
            DeathProcessor processor,
            Consumer<Throwable> errorHandler
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!enabled.getAsBoolean()) {
            return;
        }

        // Region-owned Bukkit references stop being used at the end of this
        // callback. Only detached values enter the persistence future.
        Player deceased = event.getEntity();
        Player killer = deceased.getKiller();
        if (killer == null) {
            return;
        }
        UUID loserId = deceased.getUniqueId();
        UUID winnerId = killer.getUniqueId();
        if (winnerId.equals(loserId)) {
            return;
        }
        DeathSnapshot snapshot = new DeathSnapshot(
                winnerId,
                killer.getName(),
                loserId,
                deceased.getName(),
                System.currentTimeMillis());

        CompletableFuture<DeathProcessingResult> future;
        try {
            future = Objects.requireNonNull(
                    processor.process(snapshot),
                    "DeathProcessor returned null instead of a CompletableFuture");
        } catch (Throwable error) {
            report(error);
            return;
        }

        future.whenComplete((result, error) -> {
            if (error != null) {
                report(error);
                return;
            }
            if (!enabled.getAsBoolean() || result == null) {
                return;
            }
            deliver(snapshot.winnerId(), result.winnerMessage());
            deliver(snapshot.loserId(), result.loserMessage());
        });
    }

    private void deliver(UUID playerId, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        scheduler.runForPlayer(playerId, player -> player.sendMessage(message))
                .exceptionally(error -> {
                    if (enabled.getAsBoolean()) {
                        report(error);
                    }
                    return false;
                });
    }

    private void report(Throwable error) {
        try {
            errorHandler.accept(error);
        } catch (RuntimeException handlerFailure) {
            error.addSuppressed(handlerFailure);
        }
    }
}
