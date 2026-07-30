package com.hubertstudios.coredsc.api;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.module.impl.NetworkModule;
import com.hubertstudios.coredsc.module.impl.ReportModule;
import com.hubertstudios.coredsc.module.impl.PythonBotModule;
import com.hubertstudios.coredsc.module.impl.TicketModule;
import com.hubertstudios.coredsc.storage.LinkedAccountRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Internal provider registered through Bukkit ServicesManager. */
public final class CoreDSCApiImpl implements CoreDSCApi {
    private final CoreDSCPlugin plugin;
    private final LinkedAccountRepository links;
    private final String fallbackServerId;

    public CoreDSCApiImpl(CoreDSCPlugin plugin) {
        this.plugin = plugin;
        this.links = new LinkedAccountRepository(plugin.getStorage());
        String configured = plugin.getAppConfig().getString("network.server-id", "");
        this.fallbackServerId = configured == null || configured.isBlank()
                ? plugin.getServer().getName()
                : configured.trim();
    }

    @Override public CompletableFuture<Optional<LinkedAccountView>> findLinkedAccount(UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Minecraft UUID is required"));
        }
        return links.findByMinecraftUuid(uuid.toString()).thenApply(value -> value.map(CoreDSCApiImpl::view));
    }
    @Override public CompletableFuture<Optional<LinkedAccountView>> findLinkedAccount(String discordId) {
        if (discordId == null || discordId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Discord user ID is required"));
        }
        try {
            long parsed = Long.parseUnsignedLong(discordId.trim());
            if (parsed == 0L) throw new NumberFormatException("zero ID");
        } catch (NumberFormatException error) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Discord user ID must be a positive snowflake", error));
        }
        return links.findByDiscordUserId(discordId.trim()).thenApply(value -> value.map(CoreDSCApiImpl::view));
    }
    @Override public CompletableFuture<CreateResult> createTicket(UUID uuid, String reason, String message) {
        if (uuid == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Minecraft UUID is required"));
        }
        if (reason == null || message == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ticket reason and message are required"));
        }
        TicketModule module = plugin.getModuleManager() == null ? null : plugin.getModuleManager().getModule(TicketModule.class);
        return module == null ? CompletableFuture.completedFuture(new CreateResult(false,0,"Ticket module unavailable"))
                : module.createTicketForPlayer(uuid,reason,message);
    }
    @Override public CompletableFuture<CreateResult> createReport(UUID reporter, UUID target, String reason, String message) {
        if (reporter == null || target == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Reporter and target UUIDs are required"));
        }
        if (reason == null || message == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Report reason and message are required"));
        }
        ReportModule module = plugin.getModuleManager() == null ? null : plugin.getModuleManager().getModule(ReportModule.class);
        return module == null ? CompletableFuture.completedFuture(new CreateResult(false,0,"Report module unavailable"))
                : module.createReport(reporter,target,reason,message);
    }
    @Override public CompletableFuture<Void> publishModerationAction(ModerationAuditService.ModerationAction action) {
        if (action == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Moderation action is required"));
        return plugin.callSync(() -> {
            ModerationAuditService service = plugin.getServer().getServicesManager().load(ModerationAuditService.class);
            if (service == null) throw new IllegalStateException("Moderation bridge unavailable");
            service.report(action);
            return null;
        });
    }
    @Override public CompletableFuture<Boolean> publishPythonEvent(String eventName, Map<String, ?> data) {
        PythonBotModule module = plugin.getModuleManager() == null
                ? null : plugin.getModuleManager().getModule(PythonBotModule.class);
        return module == null
                ? CompletableFuture.completedFuture(false)
                : module.publishExternalEvent(eventName, data, "java-api");
    }
    @Override public boolean isDiscordReady() { return plugin.getDiscordService()!=null&&plugin.getDiscordService().isReady(); }
    @Override public String serverId() {
        NetworkModule network=plugin.getModuleManager()==null?null:plugin.getModuleManager().getModule(NetworkModule.class);
        return network==null?fallbackServerId:network.serverId();
    }
    private static LinkedAccountView view(LinkedAccountRepository.LinkedAccount account) {
        try {
            String discordId = account.discordUserId();
            if (discordId == null || discordId.isBlank()
                    || Long.parseUnsignedLong(discordId.trim()) == 0L) {
                throw new IllegalArgumentException("invalid Discord user ID");
            }
            return new LinkedAccountView(
                    UUID.fromString(account.minecraftUuid()),
                    account.minecraftName(),
                    discordId.trim(),
                    account.linkedAt());
        } catch (RuntimeException error) {
            throw new IllegalStateException("Stored linked account contains invalid identifiers", error);
        }
    }
}
