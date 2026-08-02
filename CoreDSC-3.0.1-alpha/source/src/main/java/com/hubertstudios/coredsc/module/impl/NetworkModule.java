package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.event.AccountLinkedEvent;
import com.hubertstudios.coredsc.event.AccountUnlinkedEvent;
import com.hubertstudios.coredsc.event.ReportCreateEvent;
import com.hubertstudios.coredsc.event.TicketCreateEvent;
import com.hubertstudios.coredsc.module.CoreModule;
import com.hubertstudios.coredsc.network.NetworkBus;
import com.hubertstudios.coredsc.network.RedisNetworkBus;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


public final class NetworkModule implements CoreModule {
    private final CoreDSCPlugin plugin;
    private NetworkBus bus;
    private Listener listener;
    private String networkId;
    private String serverId;
    private long linkTtlSeconds;

    public NetworkModule(CoreDSCPlugin plugin) { this.plugin = plugin; }
    @Override public String id() { return "network"; }

    @Override
    public void enable() {
        FileConfiguration c = plugin.getAppConfig();
        String mode = c.getString("network.mode", "local").trim().toLowerCase(java.util.Locale.ROOT);
        networkId = c.getString("network.network-id", "default").trim();
        serverId = c.getString("network.server-id", plugin.getServer().getName()).trim();
        linkTtlSeconds = Math.max(0L, c.getLong("network.shared-links.ttl-seconds", 0L));
        if (!"redis".equals(mode)) throw new IllegalArgumentException("network.mode must be redis when module is enabled");
        bus = new RedisNetworkBus(
                c.getString("network.redis.host", "127.0.0.1"),
                c.getInt("network.redis.port", 6379),
                c.getBoolean("network.redis.tls", false),
                c.getString("network.redis.username", ""),
                resolvePassword(c),
                c.getInt("network.redis.database", 0),
                "coredsc:" + networkId + ":events",
                (int) Math.max(1000L, c.getLong("network.redis.timeout-millis", 5000L))
        );
        bus.subscribe(this::receive);
        listener = new Listener() {
            @EventHandler public void linked(AccountLinkedEvent e) {
                Map<String,String> data = base();
                data.put("minecraft_uuid", e.minecraftUuid().toString());
                data.put("minecraft_name", e.minecraftName());
                data.put("discord_user_id", e.discordUserId());
                publishSafely("ACCOUNT_LINKED", data);
                if (c.getBoolean("network.shared-links.enabled", true)) {
                    String value = e.minecraftUuid() + ":" + e.minecraftName();
                    bus.put(linkDiscordKey(e.discordUserId()), value, linkTtlSeconds);
                    bus.put(linkMinecraftKey(e.minecraftUuid().toString()), e.discordUserId(), linkTtlSeconds);
                }
            }
            @EventHandler public void unlinked(AccountUnlinkedEvent e) {
                Map<String,String> data = base();
                data.put("minecraft_uuid", e.minecraftUuid().toString()); data.put("discord_user_id", e.discordUserId());
                publishSafely("ACCOUNT_UNLINKED", data);
                bus.delete(linkDiscordKey(e.discordUserId()));
                bus.delete(linkMinecraftKey(e.minecraftUuid().toString()));
            }
            @EventHandler public void ticket(TicketCreateEvent e) {
                Map<String,String> data = base(); data.put("id", Long.toString(e.ticketId()));
                data.put("minecraft_uuid", e.minecraftUuid().toString()); data.put("reason", e.reason());
                publishSafely("TICKET_CREATED", data);
            }
            @EventHandler public void report(ReportCreateEvent e) {
                Map<String,String> data = base(); data.put("id", Long.toString(e.reportId()));
                data.put("reporter_uuid", e.reporterUuid().toString()); data.put("target_uuid", e.targetUuid().toString());
                data.put("reason", e.reason()); publishSafely("REPORT_CREATED", data);
            }
        };
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void disable() {
        if (listener != null) HandlerList.unregisterAll(listener);
        listener = null;
        if (bus != null) bus.close();
        bus = null;
    }

    @Override public String statusDetail() { return "Redis network " + networkId + " as " + serverId; }
    public String serverId() { return serverId == null || serverId.isBlank() ? "unknown" : serverId; }
    public boolean connected() { return bus != null && bus.isConnected(); }

    public CompletableFuture<Optional<String>> findSharedDiscordLink(String discordId) {
        return bus == null ? CompletableFuture.completedFuture(Optional.empty()) : bus.get(linkDiscordKey(discordId));
    }

    public CompletableFuture<Optional<String>> findSharedMinecraftLink(String uuid) {
        return bus == null ? CompletableFuture.completedFuture(Optional.empty()) : bus.get(linkMinecraftKey(uuid));
    }

    public CompletableFuture<Void> publish(String type, Map<String,String> data) {
        return bus == null ? CompletableFuture.completedFuture(null) : bus.publish(type, data);
    }

    private void receive(String type, Map<String,String> data) {
        if (serverId.equals(data.get("origin"))) return;
        WorkflowModule workflows = plugin.getModuleManager() == null ? null
                : plugin.getModuleManager().getModule(WorkflowModule.class);
        if (workflows != null) {
            Map<String,String> values = new LinkedHashMap<>(data);
            values.put("network_event", type);
            plugin.runSync(() -> workflows.trigger("NETWORK_EVENT", values));
        }
    }

    private Map<String,String> base() {
        Map<String,String> values = new LinkedHashMap<>();
        values.put("origin", serverId); values.put("network", networkId);
        values.put("timestamp", Long.toString(System.currentTimeMillis()));
        return values;
    }

    private void publishSafely(String type, Map<String,String> data) {
        bus.publish(type, data).exceptionally(error -> {
            plugin.getLogger().warning("[Network] Publish failed: " + rootMessage(error)); return null;
        });
    }

    private String linkDiscordKey(String id) { return "coredsc:" + networkId + ":link:discord:" + id; }
    private String linkMinecraftKey(String id) { return "coredsc:" + networkId + ":link:minecraft:" + id; }

    private String resolvePassword(FileConfiguration c) {
        String env = c.getString("network.redis.password-env", "COREDSC_REDIS_PASSWORD");
        String value = env == null || env.isBlank() ? "" : System.getenv(env);
        return value == null ? "" : value;
    }
    private static String rootMessage(Throwable t) {
        Throwable c=t; while(c.getCause()!=null)c=c.getCause(); return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();
    }
}
