package com.hubertstudios.coredsc.cloud;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.storage.CloudOperationRepository;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Reversible Discord channel mutations. Exact @everyone overwrites are persisted before changes. */
public final class ChannelOperationService {
    private final CoreDSCPlugin plugin;
    private final CloudOperationRepository repository;

    public ChannelOperationService(CoreDSCPlugin plugin, CloudOperationRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public CompletableFuture<Map<String, Object>> execute(
            String operation,
            Map<String, Object> payload,
            String operationId,
            String actor,
            String reason
    ) {
        try {
            return switch (operation) {
                case "channel.snapshot" -> CompletableFuture.completedFuture(snapshot(payload));
                case "channel.lock" -> deny(payload, Permission.MESSAGE_SEND, "MESSAGE_SEND", operationId, actor, reason);
                case "channel.unlock" -> restore(payload, Permission.MESSAGE_SEND, "MESSAGE_SEND", reason, "");
                case "channel.hide" -> deny(payload, Permission.VIEW_CHANNEL, "VIEW_CHANNEL", operationId, actor, reason);
                case "channel.reveal" -> restore(payload, Permission.VIEW_CHANNEL, "VIEW_CHANNEL", reason, "");
                case "channel.slowmode" -> slowmode(payload, operationId, actor, reason);
                case "channel.slowmode.restore" -> restoreSlowmode(payload, reason);
                case "channel.purge" -> purge(payload, reason);
                default -> CompletableFuture.failedFuture(new IllegalArgumentException(
                        "Unsupported channel operation " + operation));
            };
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletableFuture<Map<String, Object>> deny(
            Map<String, Object> payload,
            Permission permission,
            String permissionName,
            String operationId,
            String actor,
            String reason
    ) {
        TextChannel channel = requireChannel(payload);
        requireBotPermission(channel, Permission.MANAGE_PERMISSIONS);
        PermissionOverride existing = channel.getPermissionOverride(channel.getGuild().getPublicRole());
        CloudOperationRepository.ChannelSnapshot snapshot = new CloudOperationRepository.ChannelSnapshot(
                channel.getId(), permissionName, existing != null,
                existing == null ? 0L : existing.getAllowedRaw(),
                existing == null ? 0L : existing.getDeniedRaw(),
                channel.getSlowmode(), operationId, System.currentTimeMillis());
        return repository.findChannelSnapshot(channel.getId(), permissionName).thenCompose(previous -> {
            CompletableFuture<Void> persisted = previous.isPresent()
                    ? CompletableFuture.completedFuture(null)
                    : repository.saveChannelSnapshot(snapshot);
            return persisted.thenCompose(ignored -> channel
                            .upsertPermissionOverride(channel.getGuild().getPublicRole())
                            .deny(permission)
                            .reason(auditReason(actor, reason))
                            .submit())
                    .thenApply(ignored -> Map.of(
                            "channelId", channel.getId(),
                            "channel", channel.getName(),
                            "permission", permissionName,
                            "changed", true,
                            "snapshotReused", previous.isPresent()));
        });
    }

    private CompletableFuture<Map<String, Object>> restore(
            Map<String, Object> payload,
            Permission permission,
            String permissionName,
            String reason,
            String expectedOperationId
    ) {
        TextChannel channel = requireChannel(payload);
        requireBotPermission(channel, Permission.MANAGE_PERMISSIONS);
        return repository.findChannelSnapshot(channel.getId(), permissionName).thenCompose(stored -> {
            if (stored.isEmpty()) {
                if (expectedOperationId != null && !expectedOperationId.isBlank()) {
                    return CompletableFuture.completedFuture(Map.of(
                            "channelId", channel.getId(),
                            "channel", channel.getName(),
                            "permission", permission.getName(),
                            "restored", false,
                            "skipped", true,
                            "reason", "No operation-owned snapshot remains; the channel was already restored or never changed"));
                }
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "CoreDSC has no saved " + permissionName + " state for #" + channel.getName()
                                + "; refusing to guess the previous permissions"));
            }
            CloudOperationRepository.ChannelSnapshot snapshot = stored.get();
            if (expectedOperationId != null && !expectedOperationId.isBlank()
                    && !expectedOperationId.equals(snapshot.operationId())) {
                return CompletableFuture.completedFuture(Map.of(
                        "channelId", channel.getId(),
                        "channel", channel.getName(),
                        "permission", permission.getName(),
                        "restored", false,
                        "skipped", true,
                        "reason", "The saved channel state belongs to another CoreDSC operation"));
            }
            CompletionStage<?> restoreAction;
            if (snapshot.overwriteExisted()) {
                restoreAction = channel.upsertPermissionOverride(channel.getGuild().getPublicRole())
                        .setAllowed(snapshot.allowedRaw())
                        .setDenied(snapshot.deniedRaw())
                        .reason(auditReason("CoreDSC", reason))
                        .submit();
            } else {
                PermissionOverride current = channel.getPermissionOverride(channel.getGuild().getPublicRole());
                restoreAction = current == null
                        ? CompletableFuture.completedFuture(null)
                        : current.delete().reason(auditReason("CoreDSC", reason)).submit();
            }
            return restoreAction.toCompletableFuture()
                    .thenCompose(ignored -> repository.deleteChannelSnapshot(channel.getId(), permissionName))
                    .thenApply(ignored -> Map.of(
                            "channelId", channel.getId(),
                            "channel", channel.getName(),
                            "permission", permission.getName(),
                            "restored", true));
        });
    }

    private CompletableFuture<Map<String, Object>> slowmode(
            Map<String, Object> payload,
            String operationId,
            String actor,
            String reason
    ) {
        TextChannel channel = requireChannel(payload);
        requireBotPermission(channel, Permission.MANAGE_CHANNEL);
        int seconds = integer(payload.get("seconds"), 0, 21_600, "seconds");
        CloudOperationRepository.ChannelSnapshot snapshot = new CloudOperationRepository.ChannelSnapshot(
                channel.getId(), "SLOWMODE", false, 0L, 0L,
                channel.getSlowmode(), operationId, System.currentTimeMillis());
        return repository.findChannelSnapshot(channel.getId(), "SLOWMODE").thenCompose(previous -> {
            CompletableFuture<Void> persisted = previous.isPresent()
                    ? CompletableFuture.completedFuture(null)
                    : repository.saveChannelSnapshot(snapshot);
            return persisted.thenCompose(ignored -> channel.getManager().setSlowmode(seconds)
                            .reason(auditReason(actor, reason)).submit())
                    .thenApply(ignored -> Map.of(
                            "channelId", channel.getId(), "channel", channel.getName(),
                            "slowmodeSeconds", seconds, "previousSeconds", snapshot.slowmodeSeconds()));
        });
    }

    private CompletableFuture<Map<String, Object>> restoreSlowmode(
            Map<String, Object> payload,
            String reason
    ) {
        TextChannel channel = requireChannel(payload);
        requireBotPermission(channel, Permission.MANAGE_CHANNEL);
        return repository.findChannelSnapshot(channel.getId(), "SLOWMODE").thenCompose(stored -> {
            if (stored.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "CoreDSC has no saved slowmode state for #" + channel.getName()
                                + "; refusing to guess the previous value"));
            }
            int previousSeconds = stored.get().slowmodeSeconds();
            return channel.getManager().setSlowmode(previousSeconds)
                    .reason(auditReason("CoreDSC", reason)).submit()
                    .thenCompose(ignored -> repository.deleteChannelSnapshot(channel.getId(), "SLOWMODE"))
                    .thenApply(ignored -> Map.of(
                            "channelId", channel.getId(),
                            "channel", channel.getName(),
                            "slowmodeSeconds", previousSeconds,
                            "restored", true));
        });
    }

    private CompletableFuture<Map<String, Object>> purge(Map<String, Object> payload, String reason) {
        TextChannel channel = requireChannel(payload);
        requireBotPermission(channel, Permission.MESSAGE_MANAGE);
        int count = integer(payload.get("count"), 1, 100, "count");
        return channel.getHistory().retrievePast(count).submit().thenCompose(messages -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            int[] deleted = {0};
            for (Message message : messages) {
                chain = chain.thenCompose(ignored -> message.delete()
                        .reason(auditReason("CoreDSC", reason)).submit()
                        .thenAccept(nothing -> deleted[0]++));
            }
            return chain.thenApply(ignored -> Map.of(
                    "channelId", channel.getId(), "channel", channel.getName(),
                    "requested", count, "deleted", deleted[0]));
        });
    }

    private Map<String, Object> snapshot(Map<String, Object> payload) {
        TextChannel channel = requireChannel(payload);
        PermissionOverride overwrite = channel.getPermissionOverride(channel.getGuild().getPublicRole());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channelId", channel.getId());
        result.put("name", channel.getName());
        result.put("slowmodeSeconds", channel.getSlowmode());
        result.put("everyoneOverwriteExists", overwrite != null);
        result.put("everyoneAllowedRaw", overwrite == null ? 0L : overwrite.getAllowedRaw());
        result.put("everyoneDeniedRaw", overwrite == null ? 0L : overwrite.getDeniedRaw());
        result.put("botPermissions", channel.getGuild().getSelfMember().getPermissions(channel)
                .stream().map(Permission::name).sorted().toList());
        return Map.copyOf(result);
    }

    public CompletableFuture<List<Map<String, Object>>> lockConfiguredChannels(
            List<String> channelIds,
            String operationId,
            String actor,
            String reason
    ) {
        CompletableFuture<List<Map<String, Object>>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (String channelId : channelIds.stream().distinct().limit(25).toList()) {
            chain = chain.thenCompose(results -> deny(Map.of("channelId", channelId),
                    Permission.MESSAGE_SEND, "MESSAGE_SEND", operationId, actor, reason)
                    .thenApply(result -> {
                        results.add(result);
                        return results;
                    }));
        }
        return chain.thenApply(List::copyOf);
    }

    public CompletableFuture<List<Map<String, Object>>> restoreConfiguredChannels(
            List<String> channelIds,
            String reason,
            String expectedOperationId
    ) {
        CompletableFuture<List<Map<String, Object>>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (String channelId : channelIds.stream().distinct().limit(25).toList()) {
            chain = chain.thenCompose(results -> restore(Map.of("channelId", channelId),
                    Permission.MESSAGE_SEND, "MESSAGE_SEND", reason, expectedOperationId)
                    .handle((result, error) -> {
                        if (error == null) results.add(result);
                        else results.add(Map.of("channelId", channelId, "restored", false,
                                "error", rootMessage(error)));
                        return results;
                    }));
        }
        return chain.thenApply(List::copyOf);
    }

    public CompletableFuture<List<Map<String, Object>>> restoreConfiguredChannels(
            List<String> channelIds,
            String reason
    ) {
        return restoreConfiguredChannels(channelIds, reason, "");
    }

    public CompletableFuture<List<Map<String, Object>>> restoreConfiguredChannelsStrict(
            List<String> channelIds,
            String reason,
            String expectedOperationId
    ) {
        return restoreConfiguredChannels(channelIds, reason, expectedOperationId).thenCompose(results -> {
            long failures = results.stream().filter(result -> result.containsKey("error")).count();
            if (failures > 0L) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Could not restore " + failures + " incident channel(s); the incident remains active for retry"));
            }
            return CompletableFuture.completedFuture(results);
        });
    }

    private TextChannel requireChannel(Map<String, Object> payload) {
        String channelId = text(payload.get("channelId"));
        if (!channelId.matches("[0-9]{15,22}")) {
            throw new IllegalArgumentException("channelId must be a Discord channel ID");
        }
        DiscordBotService service = plugin.getDiscordService();
        if (service == null || !service.isReady() || service.getJda() == null) {
            throw new IllegalStateException("Discord is not ready");
        }
        TextChannel channel = service.getJda().getTextChannelById(channelId);
        if (channel == null) throw new IllegalArgumentException("The bot cannot see channel " + channelId);
        Guild guild = channel.getGuild();
        if (service.getConfiguredGuildId() > 0L && guild.getIdLong() != service.getConfiguredGuildId()) {
            throw new SecurityException("Channel is outside the configured CoreDSC guild");
        }
        return channel;
    }

    private static void requireBotPermission(TextChannel channel, Permission permission) {
        if (!channel.getGuild().getSelfMember().hasPermission(channel, permission)) {
            throw new SecurityException("The Discord bot needs " + permission.getName()
                    + " in #" + channel.getName() + " for this operation");
        }
    }

    private static int integer(Object value, int minimum, int maximum, String field) {
        long number = value instanceof Number raw ? raw.longValue() : -1L;
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
        return (int) number;
    }

    private static String auditReason(String actor, String reason) {
        return truncate("CoreDSC · " + actor + " · " + reason, 500);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
