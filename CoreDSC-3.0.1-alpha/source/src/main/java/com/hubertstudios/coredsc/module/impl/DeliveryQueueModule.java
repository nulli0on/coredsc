package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.storage.OutboxRepository;
import com.hubertstudios.coredsc.storage.OutboxRepository.OutboxMessage;
import com.hubertstudios.coredsc.storage.SQLiteStorage;
import com.hubertstudios.coredsc.util.TextUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import com.hubertstudios.coredsc.scheduler.CoreTask;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;


public final class DeliveryQueueModule implements CoreModule {
    private final CoreDSCPlugin plugin;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private OutboxRepository repository;
    private CoreTask task;
    private int batchSize;
    private int maxAttempts;
    private int maximumPending;
    private long maximumBackoffMillis;

    public DeliveryQueueModule(CoreDSCPlugin plugin) { this.plugin = plugin; }
    @Override public String id() { return "delivery-queue"; }

    @Override
    public void enable() {
        SQLiteStorage storage = plugin.getStorage();
        if (storage == null || storage.getState() != SQLiteStorage.State.READY) {
            throw new IllegalStateException("SQLite storage is not ready");
        }
        repository = new OutboxRepository(storage);
        active.set(true);
        batchSize = (int) clamp(plugin.getAppConfig().getLong("delivery-queue.batch-size", 20L), 1L, 100L);
        maxAttempts = (int) clamp(plugin.getAppConfig().getLong("delivery-queue.max-attempts", 12L), 1L, 100L);
        maximumPending = (int) clamp(plugin.getAppConfig().getLong("delivery-queue.maximum-pending", 5000L), 100L, 100_000L);
        maximumBackoffMillis = clamp(plugin.getAppConfig().getLong(
                "delivery-queue.maximum-backoff-seconds", 3600L), 30L, 86_400L) * 1000L;
        long interval = clamp(plugin.getAppConfig().getLong("delivery-queue.flush-interval-seconds", 15L), 5L, 300L);
        long now = System.currentTimeMillis();
        repository.recoverInterrupted()
                .thenCompose(recovered -> repository.cleanupSent(now - 7L * 24L * 60L * 60L * 1000L))
                .exceptionally(error -> {
                    plugin.recordModuleFailure("delivery-queue", error);
                    plugin.getLogger().warning("[DeliveryQueue] Recovery/cleanup failed: " + rootMessage(error));
                    return 0;
                });
        task = plugin.getCoreScheduler().runGlobalTimer(this::flush, 20L * 5L, 20L * interval);
    }

    @Override
    public void disable() {
        active.set(false);
        if (task != null) task.cancel();
        task = null;
        flushing.set(false);
    }

    @Override public String statusDetail() { return "persistent Discord outbox"; }

    public CompletableFuture<Long> enqueue(String channelId, String message, int priority, String dedupeKey) {
        if (!active.get() || repository == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Queue is disabled"));
        }
        if (!TextUtil.isPositiveSnowflake(channelId)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "A valid Discord channel ID is required"));
        }
        String safe = TextUtil.truncate(TextUtil.sanitizeMassMentions(message), 2000);
        if (safe.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "A non-blank Discord message is required"));
        }
        String safeDedupeKey = dedupeKey == null || dedupeKey.isBlank()
                ? null : TextUtil.truncate(TextUtil.singleLine(dedupeKey), 200);
        int safePriority = Math.max(0, Math.min(100, priority));
        return repository.enqueue(safeDedupeKey, channelId.trim(), safe, safePriority,
                System.currentTimeMillis(), maximumPending)
                .thenApply(id -> {
                    plugin.recordFeatureUse("delivery_queued");
                    return id;
                });
    }

    public CompletableFuture<int[]> counts() {
        return !active.get() || repository == null
                ? CompletableFuture.completedFuture(new int[]{0, 0})
                : repository.counts();
    }

    public CompletableFuture<Integer> retryFailed() {
        return !active.get() || repository == null
                ? CompletableFuture.completedFuture(0)
                : repository.retryFailed(System.currentTimeMillis());
    }

    public CompletableFuture<Integer> clearFailed() {
        return !active.get() || repository == null
                ? CompletableFuture.completedFuture(0)
                : repository.clearFailed();
    }

    public void flush() {
        if (!active.get() || repository == null || !flushing.compareAndSet(false, true)) return;
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null || !discord.isReady() || discord.getJda() == null) {
            flushing.set(false);
            return;
        }
        repository.due(System.currentTimeMillis(), batchSize)
                .thenCompose(this::deliverSequentially)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        plugin.recordModuleFailure("delivery-queue", error);
                        plugin.getLogger().warning("[DeliveryQueue] Flush failed: " + rootMessage(error));
                    }
                    flushing.set(false);
                });
    }

    private CompletableFuture<Void> deliverSequentially(List<OutboxMessage> messages) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (OutboxMessage message : messages) {
            chain = chain.thenCompose(ignored -> deliver(message));
        }
        return chain;
    }

    private CompletableFuture<Void> deliver(OutboxMessage message) {
        if (!active.get()) {
            return CompletableFuture.completedFuture(null);
        }
        return repository.claim(message.id()).thenCompose(claimed -> {
            if (!active.get()) {
                return CompletableFuture.completedFuture(null);
            }
            if (!claimed) return CompletableFuture.completedFuture(null);
            DiscordBotService discord = plugin.getDiscordService();
            JDA jda = discord == null ? null : discord.getJda();
            TextChannel text = jda == null ? null : jda.getTextChannelById(message.channelId());
            ThreadChannel thread = jda == null ? null : jda.getThreadChannelById(message.channelId());
            MessageChannel channel = text != null ? text : thread;
            if (channel == null) {
                return fail(message, new IllegalStateException("Discord channel is not visible"));
            }
            try {
                return channel.sendMessage(message.message())
                        .setAllowedMentions(java.util.Collections.emptyList())
                        .submit()
                        .thenCompose(ignored -> repository.delivered(message.id()))
                        .exceptionallyCompose(error -> fail(message, error));
            } catch (RuntimeException error) {
                return fail(message, error);
            }
        });
    }

    private CompletableFuture<Void> fail(OutboxMessage message, Throwable error) {
        int attempts = message.attempts() + 1;
        long backoff = Math.min(maximumBackoffMillis, 5_000L * (1L << Math.min(16, attempts - 1)));
        return repository.failed(message.id(), attempts, System.currentTimeMillis() + backoff,
                TextUtil.truncate(rootMessage(error), 500), maxAttempts);
    }

    private static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
