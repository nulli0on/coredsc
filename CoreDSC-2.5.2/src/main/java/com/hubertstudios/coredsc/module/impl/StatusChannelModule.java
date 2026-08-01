package com.hubertstudios.coredsc.module.impl;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import com.hubertstudios.coredsc.discord.DiscordBotService;
import com.hubertstudios.coredsc.module.CoreModule;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.RestAction;
import org.bukkit.configuration.file.FileConfiguration;
import com.hubertstudios.coredsc.scheduler.CoreTask;

import java.lang.management.ManagementFactory;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

                                                                            
public final class StatusChannelModule implements CoreModule {
    private static final String DEFAULT_OFFLINE_NAME = "Server is offline";

    private final CoreDSCPlugin plugin;
    private final List<ChannelConfig> channels = new ArrayList<>();
    private final Set<String> warnedMissingChannels = ConcurrentHashMap.newKeySet();
    private CoreTask task;
    private ListenerAdapter discordReadyListener;
    private long uniquePlayerRefreshMillis;
    private long lastUniquePlayerRefreshMillis;
    private int cachedUniquePlayers = -1;
    private int shutdownWaitSeconds = 3;

    public StatusChannelModule(CoreDSCPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "status-channels";
    }

    @Override
    public void enable() {
        DiscordBotService discord = plugin.getDiscordService();
        if (discord == null) {
            throw new IllegalStateException("Discord service is not initialised");
        }

        FileConfiguration config = plugin.getAppConfig();
        loadChannels(config);
        if (channels.isEmpty()) {
            plugin.getLogger().info("[StatusChannels] No valid channels are configured; the module is idle.");
            return;
        }

        long intervalSeconds = clamp(
                config.getLong("status-channels.update-interval-seconds", 600L),
                300L,
                86_400L
        );
        long uniqueRefreshSeconds = clamp(
                config.getLong("status-channels.unique-player-refresh-seconds", 3600L),
                300L,
                86_400L
        );
        uniquePlayerRefreshMillis = uniqueRefreshSeconds * 1000L;
        shutdownWaitSeconds = (int) clamp(
                config.getLong("status-channels.shutdown-wait-seconds", 3L),
                1L,
                15L
        );

        discordReadyListener = new ListenerAdapter() {
            @Override
            public void onReady(ReadyEvent event) {
                plugin.runSync(() -> updateOnlineChannels(discord));
            }
        };
        discord.addEventListener(discordReadyListener);

        task = plugin.getCoreScheduler().runGlobalTimer(
                                () -> updateOnlineChannels(discord),
                20L,
                intervalSeconds * 20L
        );
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        DiscordBotService discord = plugin.getDiscordService();
        if (discordReadyListener != null && discord != null) {
            discord.removeEventListener(discordReadyListener);
        }
        discordReadyListener = null;
        channels.clear();
        warnedMissingChannels.clear();
        cachedUniquePlayers = -1;
        lastUniquePlayerRefreshMillis = 0L;
        shutdownWaitSeconds = 3;
    }

     
                                                                                
                                                                              
                                                                              
      
                                                                             
                                                                           
       
    public boolean publishOfflineStatus() {
        DiscordBotService discord = plugin.getDiscordService();
        JDA jda = discord == null ? null : discord.getJda();
        if (jda == null) {
            plugin.recordModuleFailure("status-channels", "Discord is disconnected during offline status publication");
            plugin.getLogger().warning("[StatusChannels] Could not publish the offline status because Discord is disconnected.");
            return false;
        }

        List<RenameRequest> requests;
        try {
            requests = createRenameRequests(jda, false);
        } catch (Throwable throwable) {
            plugin.recordModuleFailure("status-channels", throwable);
            plugin.getLogger().warning("[StatusChannels] Could not prepare the offline status update: "
                    + rootMessage(throwable));
            return false;
        }
        if (requests.isEmpty()) {
            return true;
        }

        CountDownLatch completed = new CountDownLatch(requests.size());
        AtomicInteger failures = new AtomicInteger();
        for (RenameRequest request : requests) {
            try {
                request.action().queue(
                        ignored -> {
                            plugin.recordFeatureUse("status_update");
                            completed.countDown();
                        },
                        error -> {
                            failures.incrementAndGet();
                            plugin.recordModuleFailure("status-channels", error);
                            plugin.getLogger().warning("[StatusChannels] Could not publish offline name for "
                                    + request.channelId() + ": " + rootMessage(error));
                            completed.countDown();
                        }
                );
            } catch (Throwable throwable) {
                failures.incrementAndGet();
                completed.countDown();
                plugin.recordModuleFailure("status-channels", throwable);
                plugin.getLogger().warning("[StatusChannels] Could not queue offline name for "
                        + request.channelId() + ": " + rootMessage(throwable));
            }
        }

        try {
            boolean finished = completed.await(shutdownWaitSeconds, TimeUnit.SECONDS);
            if (!finished) {
                plugin.getLogger().warning("[StatusChannels] Offline status update exceeded the shutdown timeout; "
                        + completed.getCount() + " channel rename(s) may still be pending.");
            }
            return finished && failures.get() == 0;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("[StatusChannels] Offline status update was interrupted.");
            return false;
        }
    }

    private void loadChannels(FileConfiguration config) {
        channels.clear();
        Set<String> seenChannelIds = new HashSet<>();
        String defaultOfflineName = config.getString(
                "status-channels.default-offline-name", DEFAULT_OFFLINE_NAME);
        if (defaultOfflineName == null || defaultOfflineName.isBlank()) {
            defaultOfflineName = DEFAULT_OFFLINE_NAME;
        }

        List<Map<?, ?>> configured = config.getMapList("status-channels.channels");
        for (Map<?, ?> entry : configured) {
            String id = string(entry.get("id")).trim();
            String type = string(entry.get("type")).trim().toLowerCase(Locale.ROOT);
            if (type.isBlank()) {
                type = "voice";
            }
                                                                         
            String onlineTemplate = firstNonBlank(entry.get("online-name"), entry.get("name"));
            String offlineTemplate = string(entry.get("offline-name"));
            if (offlineTemplate.isBlank()) {
                offlineTemplate = defaultOfflineName;
            }
            if (id.isBlank() || onlineTemplate.isBlank()) {
                continue;
            }
            if (!isPositiveSnowflake(id)) {
                plugin.getLogger().warning("[StatusChannels] Ignoring invalid Discord channel ID: " + id);
                continue;
            }
            if (!type.equals("voice") && !type.equals("text")) {
                plugin.getLogger().warning("[StatusChannels] Ignoring channel " + id
                        + " because type must be 'voice' or 'text'.");
                continue;
            }
            if (!seenChannelIds.add(id)) {
                plugin.getLogger().warning("[StatusChannels] Ignoring duplicate channel ID: " + id);
                continue;
            }
            channels.add(new ChannelConfig(id, type, onlineTemplate, offlineTemplate));
        }
    }

    private void updateOnlineChannels(DiscordBotService discord) {
        JDA jda = discord.getJda();
        if (!discord.isReady() || jda == null) {
            return;
        }
        List<RenameRequest> requests;
        try {
            requests = createRenameRequests(jda, true);
        } catch (Throwable throwable) {
            plugin.recordModuleFailure("status-channels", throwable);
            plugin.getLogger().warning("[StatusChannels] Could not prepare the online status update: "
                    + rootMessage(throwable));
            return;
        }
        for (RenameRequest request : requests) {
            try {
                request.action().queue(
                        ignored -> plugin.recordFeatureUse("status_update"),
                        error -> {
                            plugin.recordModuleFailure("status-channels", error);
                            plugin.getLogger().warning("[StatusChannels] Could not rename "
                                    + request.channelId() + ": " + rootMessage(error));
                        }
                );
            } catch (Throwable throwable) {
                plugin.recordModuleFailure("status-channels", throwable);
                plugin.getLogger().warning("[StatusChannels] Could not queue rename for "
                        + request.channelId() + ": " + rootMessage(throwable));
            }
        }
    }

    private List<RenameRequest> createRenameRequests(JDA jda, boolean online) {
        StatusSnapshot snapshot = captureSnapshot(online);
        List<RenameRequest> requests = new ArrayList<>();
        for (ChannelConfig channel : channels) {
            String template = online ? channel.onlineTemplate() : channel.offlineTemplate();
            String resolved = resolve(template, snapshot, online);
            resolved = plugin.getPlaceholderService().apply(null, resolved);
            String name = sanitizeChannelName(resolved, channel.type());

            if (channel.type().equals("voice")) {
                VoiceChannel voice = jda.getVoiceChannelById(channel.id());
                if (voice == null) {
                    warnMissing(channel);
                    continue;
                }
                warnedMissingChannels.remove(channel.id());
                if (!voice.getName().equals(name)) {
                    requests.add(new RenameRequest(channel.id(), voice.getManager().setName(name)));
                }
            } else {
                TextChannel text = jda.getTextChannelById(channel.id());
                if (text == null) {
                    warnMissing(channel);
                    continue;
                }
                warnedMissingChannels.remove(channel.id());
                if (!text.getName().equals(name)) {
                    requests.add(new RenameRequest(channel.id(), text.getManager().setName(name)));
                }
            }
        }
        return requests;
    }

    private StatusSnapshot captureSnapshot(boolean online) {
        int onlinePlayers = online ? plugin.getServer().getOnlinePlayers().size() : 0;
        int maximum = plugin.getServer().getMaxPlayers();
        double tps = 0.0;
        if (online) {
            double[] tpsValues = plugin.getServer().getTPS();
            tps = tpsValues.length == 0 ? 20.0 : Math.max(0.0, Math.min(20.0, tpsValues[0]));
        }
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
        long usedRamMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                / (1024L * 1024L);
        long maxRamMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);

        boolean needsUniquePlayers = channels.stream().anyMatch(channel ->
                channel.onlineTemplate().contains("%unique_players%")
                        || channel.offlineTemplate().contains("%unique_players%"));
        int uniquePlayers = -1;
        if (needsUniquePlayers) {
            long now = System.currentTimeMillis();
            if (cachedUniquePlayers < 0
                    || now - lastUniquePlayerRefreshMillis >= uniquePlayerRefreshMillis) {
                cachedUniquePlayers = plugin.getServer().getOfflinePlayers().length;
                lastUniquePlayerRefreshMillis = now;
            }
            uniquePlayers = cachedUniquePlayers;
        }

        return new StatusSnapshot(
                onlinePlayers,
                maximum,
                tps,
                plugin.getServer().getVersion(),
                formatUptime(uptimeSeconds),
                usedRamMb,
                maxRamMb,
                plugin.getServer().getWorlds().size(),
                uniquePlayers
        );
    }

    private static String resolve(String template, StatusSnapshot snapshot, boolean online) {
        String value = template
                .replace("%online_players%", Integer.toString(snapshot.onlinePlayers()))
                .replace("%max_players%", Integer.toString(snapshot.maxPlayers()))
                .replace("%tps%", String.format(Locale.US, "%.1f", snapshot.tps()))
                .replace("%server_status%", online ? "Online" : "Offline")
                .replace("%server_version%", snapshot.serverVersion())
                .replace("%uptime%", snapshot.uptime())
                .replace("%ram_used%", Long.toString(snapshot.usedRamMb()))
                .replace("%ram_max%", Long.toString(snapshot.maxRamMb()))
                .replace("%world_count%", Integer.toString(snapshot.worldCount()));
        if (snapshot.uniquePlayers() >= 0) {
            value = value.replace("%unique_players%", Integer.toString(snapshot.uniquePlayers()));
        }
        return value;
    }

    private void warnMissing(ChannelConfig channel) {
        if (warnedMissingChannels.add(channel.id())) {
            plugin.getLogger().warning("[StatusChannels] Configured " + channel.type()
                    + " channel " + channel.id() + " is not visible to the bot.");
        }
    }

    private static String sanitizeChannelName(String name, String type) {
        String compact = name.replace('\n', ' ').replace('\r', ' ').trim();
        if ("text".equals(type)) {
            compact = Normalizer.normalize(compact, Normalizer.Form.NFKD)
                    .replaceAll("\\p{M}+", "")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
        } else {
            compact = compact.replaceAll("\\s+", " ");
        }
        if (compact.isEmpty()) {
            compact = "status";
        }
        return truncateCodePoints(compact, 100);
    }

    private static String truncateCodePoints(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maximumCodePoints);
        return value.substring(0, end);
    }

    private static String formatUptime(long totalSeconds) {
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        return hours + "h " + minutes + "m";
    }

    private static boolean isPositiveSnowflake(String value) {
        try {
            return Long.parseLong(value) > 0L;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String firstNonBlank(Object first, Object second) {
        String firstValue = string(first);
        return firstValue.isBlank() ? string(second) : firstValue;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private record ChannelConfig(
            String id,
            String type,
            String onlineTemplate,
            String offlineTemplate
    ) { }

    private record RenameRequest(String channelId, RestAction<Void> action) { }

    private record StatusSnapshot(
            int onlinePlayers,
            int maxPlayers,
            double tps,
            String serverVersion,
            String uptime,
            long usedRamMb,
            long maxRamMb,
            int worldCount,
            int uniquePlayers
    ) { }
}
